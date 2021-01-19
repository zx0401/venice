package com.linkedin.venice.endToEnd;

import com.linkedin.davinci.client.DaVinciClient;
import com.linkedin.venice.common.VeniceSystemStoreUtils;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.hadoop.KafkaPushJob;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import java.io.File;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.linkedin.venice.ConfigKeys.*;
import static com.linkedin.venice.hadoop.KafkaPushJob.*;
import static com.linkedin.venice.integration.utils.VeniceClusterWrapper.*;
import static com.linkedin.venice.utils.TestPushUtils.*;
import static org.testng.Assert.*;


public class PushStatusStoreTest {
  private VeniceClusterWrapper cluster;
  private VeniceControllerWrapper parentController;
  private ControllerClient parentControllerClient;

  @BeforeMethod
  public void setup() {
    Properties extraProperties = new Properties();
    extraProperties.setProperty(SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, Long.toString(1L));
    Utils.thisIsLocalhost();
    cluster = ServiceFactory.getVeniceCluster(
        1,
        1,
        1,
        1,
        10000,
        false,
        false,
        extraProperties);
    Properties controllerConfig = new Properties();
    parentController =
        ServiceFactory.getVeniceParentController(cluster.getClusterName(), ServiceFactory.getZkServer().getAddress(), cluster.getKafka(),
            cluster.getVeniceControllers().toArray(new VeniceControllerWrapper[0]),
            new VeniceProperties(controllerConfig), false);
    parentControllerClient = new ControllerClient(cluster.getClusterName(), parentController.getControllerUrl());
  }

  @AfterMethod
  public void cleanup() {
    IOUtils.closeQuietly(parentControllerClient);
    IOUtils.closeQuietly(parentController);
    IOUtils.closeQuietly(cluster);
  }

  @Test(timeOut = 60 * Time.MS_PER_SECOND)
  public void testKafkaPushJob() throws Exception {
    String storeName = TestUtils.getUniqueString("store");
    String owner = "test";
    String zkSharedPushStatusStoreName = VeniceSystemStoreUtils.getSharedZkNameForDaVinciPushStatusStore(cluster.getClusterName());
    parentControllerClient.createNewZkSharedStoreWithDefaultConfigs(zkSharedPushStatusStoreName, owner);
    parentControllerClient.newZkSharedStoreVersion(zkSharedPushStatusStoreName);
    parentControllerClient.createNewStore(storeName, owner, DEFAULT_KEY_SCHEMA, DEFAULT_VALUE_SCHEMA).isError();
    parentControllerClient.updateStore(storeName, new UpdateStoreQueryParams()
        .setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA));
    parentControllerClient.createDaVinciPushStatusStore(storeName).isError();

    // Produce input data.
    File inputDir = getTempDataDirectory();
    String inputDirPath = "file://" + inputDir.getAbsolutePath();
    writeSimpleAvroFileWithIntToIntSchema(inputDir, true);

    // Setup H2V job properties.
    Properties h2vProperties = defaultH2VProps(cluster, inputDirPath, storeName);
    // setup initial version
    runH2V(h2vProperties, 1, cluster);

    DaVinciClient daVinciClient = ServiceFactory.getGenericAvroDaVinciClient(storeName, cluster);
    daVinciClient.subscribeAll().get();
    runH2V(h2vProperties, 2, cluster);

    // clean up
    daVinciClient.close();

    String pushStatusStoreTopic =
        Version.composeKafkaTopic(VeniceSystemStoreUtils.getDaVinciPushStatusStoreName(storeName), 1);
    assertTrue(cluster.getVeniceControllers().get(0).getVeniceAdmin().isResourceStillAlive(pushStatusStoreTopic));
    assertFalse(cluster.getVeniceControllers().get(0).getVeniceAdmin().isTopicTruncated(pushStatusStoreTopic));
    parentControllerClient.deleteDaVinciPushStatusStore(storeName);

    TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, () -> {
      assertFalse(cluster.getVeniceControllers().get(0).getVeniceAdmin().isResourceStillAlive(pushStatusStoreTopic));
      assertTrue(!cluster.getVeniceControllers().get(0).getVeniceAdmin().getTopicManager().containsTopic(pushStatusStoreTopic)
          || cluster.getVeniceControllers().get(0).getVeniceAdmin().isTopicTruncated(pushStatusStoreTopic));
    });
  }

  @Test(timeOut = 30 * Time.MS_PER_SECOND)
  public void testIncrementalPush() throws Exception {
    String storeName = TestUtils.getUniqueString("store");
    String owner = "test";
    String zkSharedPushStatusStoreName = VeniceSystemStoreUtils.getSharedZkNameForDaVinciPushStatusStore(cluster.getClusterName());
    parentControllerClient.createNewZkSharedStoreWithDefaultConfigs(zkSharedPushStatusStoreName, owner);
    parentControllerClient.newZkSharedStoreVersion(zkSharedPushStatusStoreName);
    parentControllerClient.createNewStore(storeName, owner, DEFAULT_KEY_SCHEMA, DEFAULT_VALUE_SCHEMA).isError();
    parentControllerClient.updateStore(storeName, new UpdateStoreQueryParams()
        .setIncrementalPushEnabled(true)
        .setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA));
    parentControllerClient.createDaVinciPushStatusStore(storeName);

    // Produce input data.
    File inputDir = getTempDataDirectory();
    String inputDirPath = "file://" + inputDir.getAbsolutePath();
    writeSimpleAvroFileWithIntToIntSchema(inputDir, true);

    // Setup H2V job properties.
    Properties h2vProperties = defaultH2VProps(cluster, inputDirPath, storeName);
    // setup initial version
    runH2V(h2vProperties, 1, cluster);

    DaVinciClient daVinciClient = ServiceFactory.getGenericAvroDaVinciClient(storeName, cluster);
    daVinciClient.subscribeAll().get();
    h2vProperties.setProperty(INCREMENTAL_PUSH, "true");
    runH2V(h2vProperties, 1, cluster);

    // clean up
    daVinciClient.close();
  }

  private static void runH2V(Properties h2vProperties, int expectedVersionNumber, VeniceClusterWrapper cluster) {
    long h2vStart = System.currentTimeMillis();
    String jobName = TestUtils.getUniqueString("batch-job-" + expectedVersionNumber);
    KafkaPushJob job = new KafkaPushJob(jobName, h2vProperties);
    job.run();
    String storeName = (String) h2vProperties.get(KafkaPushJob.VENICE_STORE_NAME_PROP);
    cluster.waitVersion(storeName, expectedVersionNumber);
    logger.info("**TIME** H2V" + expectedVersionNumber + " takes " + (System.currentTimeMillis() - h2vStart));
  }

}
