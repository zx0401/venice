package com.linkedin.venice.controller;

import com.linkedin.venice.helix.HelixStatusMessageChannel;
import com.linkedin.venice.meta.OfflinePushStrategy;
import com.linkedin.venice.status.StoreStatusMessage;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixAdapterSerializer;
import com.linkedin.venice.helix.HelixInstanceConverter;
import com.linkedin.venice.helix.HelixJobRepository;
import com.linkedin.venice.helix.HelixReadWriteStoreRepository;
import com.linkedin.venice.helix.HelixRoutingDataRepository;
import com.linkedin.venice.job.Job;
import com.linkedin.venice.job.ExecutionStatus;
import com.linkedin.venice.job.OfflineJob;
import com.linkedin.venice.job.Task;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.VersionStatus;
import com.linkedin.venice.utils.FlakyTestRetryAnalyzer;
import com.linkedin.venice.utils.MockTestStateModel;
import com.linkedin.venice.utils.MockTestStateModelFactory;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.LiveInstanceInfoProvider;
import org.apache.helix.ZNRecord;
import org.apache.helix.controller.HelixControllerMain;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixManager;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.ZkServerWrapper;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;


/**
 * Test cases for Venice job manager.
 */
public class TestVeniceJobManager {
  private VeniceJobManager jobManager;
  private HelixJobRepository jobRepository;
  private HelixReadWriteStoreRepository metadataRepository;

  private String zkAddress;
  private ZkServerWrapper zkServerWrapper;
  private ZkClient zkClient;
  private String cluster = "jobTestCluster";
  private HelixRoutingDataRepository routingDataRepository;
  private HelixAdmin admin;
  private HelixManager controller;
  private HelixManager manager;
  private String storeName = "ts1";
  private String nodeId;
  private int httpPort = 9985;
  private int adminPort = 12345;

  private Store store;
  private Version version;

  @BeforeMethod
  public void setup()
      throws Exception {
    nodeId = Utils.getHelixNodeIdentifier(httpPort);
    zkServerWrapper = ServiceFactory.getZkServer();
    zkAddress = zkServerWrapper.getAddress();

    store = TestUtils.createTestStore(storeName, "test", System.currentTimeMillis());
    version = store.increaseVersion();

    admin = new ZKHelixAdmin(zkAddress);
    admin.addCluster(cluster);
    HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).
        forCluster(cluster).build();
    Map<String, String> helixClusterProperties = new HashMap<String, String>();
    helixClusterProperties.put(ZKHelixManager.ALLOW_PARTICIPANT_AUTO_JOIN, String.valueOf(true));
    admin.setConfig(configScope, helixClusterProperties);
    admin.addStateModelDef(cluster, MockTestStateModel.UNIT_TEST_STATE_MODEL,
        MockTestStateModel.getDefinition());

    admin.addResource(cluster, version.kafkaTopicName(), 1, MockTestStateModel.UNIT_TEST_STATE_MODEL,
        IdealState.RebalanceMode.FULL_AUTO.toString());
    admin.rebalance(cluster, version.kafkaTopicName(), 1);

    controller = HelixControllerMain
        .startHelixController(zkAddress, cluster, Utils.getHelixNodeIdentifier(adminPort), HelixControllerMain.STANDALONE);
    manager = TestUtils.getParticipant(cluster, nodeId, zkAddress, httpPort,
        MockTestStateModel.UNIT_TEST_STATE_MODEL);
    manager.connect();
    Thread.sleep(1000l);
    routingDataRepository = new HelixRoutingDataRepository(controller);
    routingDataRepository.refresh();

    zkClient = new ZkClient(zkAddress);
    zkClient.createPersistent("/" + cluster + "stores");
    HelixAdapterSerializer adapterSerializer = new HelixAdapterSerializer();
    jobRepository = new HelixJobRepository(zkClient, adapterSerializer, cluster);
    jobRepository.refresh();
    metadataRepository = new HelixReadWriteStoreRepository(zkClient, adapterSerializer, cluster);
    metadataRepository.refresh();
    long jobTimeOut = 5000;
    jobManager = new VeniceJobManager(cluster, 1, jobRepository, metadataRepository, routingDataRepository, jobTimeOut);
  }

  @AfterMethod
  public void cleanup() {
    jobRepository.clear();
    metadataRepository.clear();
    routingDataRepository.clear();
    manager.disconnect();
    controller.disconnect();
    admin.dropCluster(cluster);
    admin.close();
    zkClient.deleteRecursive("/" + cluster + "stores");
    zkClient.close();
    zkServerWrapper.close();
  }

  @DataProvider(name = "offlinePushStrategies")
  public static Object[][] offlinePushStrategies() {
    return new Object[][]{{OfflinePushStrategy.WAIT_ALL_REPLICAS}, {OfflinePushStrategy.WAIT_N_MINUS_ONE_REPLCIA_PER_PARTITION}};
  }

  @Test(timeOut = 15000, dataProvider = "offlinePushStrategies")
  public void testHandleMessage(OfflinePushStrategy offlinePushStrategy)
      throws InterruptedException {

    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1, offlinePushStrategy);

    StoreStatusMessage message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);
    Job job = jobRepository.getRunningJobOfTopic(version.kafkaTopicName()).get(0);
    Assert.assertEquals(jobRepository.getJobStatus(job.getJobId(), job.getKafkaTopic()), ExecutionStatus.STARTED,
        "Job should be started.");

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.COMPLETED);
    jobManager.handleMessage(message);
    //Wait ZK notification.
    Thread.sleep(1000l);
    Store updatedStore = metadataRepository.getStore(storeName);
    Assert.assertEquals(updatedStore.getCurrentVersion(), version.getNumber(),
        "Push has been done, store's current should be updated.");

    Assert.assertEquals(updatedStore.getVersions().get(0).getStatus(), VersionStatus.ONLINE,
        "Push has been done. Version should be activated.");
    jobManager.archiveJobs(version.kafkaTopicName());
    try {
      jobRepository.getJob(job.getJobId(), job.getKafkaTopic());
      Assert.fail("Job should be archived.");
    } catch (VeniceException e) {
      //expected.
    }
  }

  @Test(timeOut = 15000, dataProvider = "offlinePushStrategies")
  public void testHandleMessageWhenTaskFailed(OfflinePushStrategy offlinePushStrategy)
      throws InterruptedException {
    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1, offlinePushStrategy);

    StoreStatusMessage message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);
    Job job = jobRepository.getRunningJobOfTopic(version.kafkaTopicName()).get(0);
    Assert.assertEquals(jobRepository.getJobStatus(job.getJobId(), job.getKafkaTopic()), ExecutionStatus.STARTED,
        "Job should be started.");

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.ERROR);
    jobManager.handleMessage(message);
    //Wait ZK notification.
    Thread.sleep(1000l);
    Store updatedStore = metadataRepository.getStore(storeName);
    Assert.assertEquals(updatedStore.getCurrentVersion(), 0,
        "Push was failed. No current version is active for this store.");
    Assert.assertEquals(updatedStore.getVersions().get(0).getStatus(), VersionStatus.ERROR,
        "Push was failed. Version should not be activated.");
    jobManager.archiveJobs(version.kafkaTopicName());
    try {
      jobRepository.getJob(job.getJobId(), job.getKafkaTopic());
      Assert.fail("Job should be archived.");
    } catch (VeniceException e) {
      //expected.
    }
  }

  @Test (timeOut = 15000, dataProvider = "offlinePushStrategies")
  public void testGetOfflineJobStatus(OfflinePushStrategy offlinePushStrategy) {
    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1, offlinePushStrategy);
    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.STARTED,
        "Job should be started.");
    long totalProgress = VeniceJobManager.aggregateProgress(jobManager.getOfflineJobProgress(version.kafkaTopicName()));
    Assert.assertEquals(totalProgress, 0L, "new job should have 0 progress");

    StoreStatusMessage message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.PROGRESS);
    message.setOffset(123);
    jobManager.handleMessage(message);

    totalProgress = VeniceJobManager.aggregateProgress(jobManager.getOfflineJobProgress(version.kafkaTopicName()));
    Assert.assertEquals(totalProgress, 123L, "job with progress should have progress");

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.COMPLETED);
    jobManager.handleMessage(message);

    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.COMPLETED);
    totalProgress = VeniceJobManager.aggregateProgress(jobManager.getOfflineJobProgress(version.kafkaTopicName()));
    Assert.assertEquals(totalProgress, 123L,
        "completed job with progress should retain progress");
  }

  @Test(timeOut = 15000, dataProvider = "offlinePushStrategies")
  public void testGetOfflineJobStatusWhenTaskFailed(OfflinePushStrategy offlinePushStrategy) {
    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1, offlinePushStrategy);
    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.STARTED,
        "Job should be started.");

    StoreStatusMessage message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.ERROR);
    jobManager.handleMessage(message);

    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.ERROR);
  }

  @Test(timeOut = 15000, dataProvider = "offlinePushStrategies")
  public void testExecutorFailedDuringPush(OfflinePushStrategy offlinePushStrategy)
      throws Exception {
    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1, offlinePushStrategy);
    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.STARTED,
        "Job should be started.");
    StoreStatusMessage message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);
    Job job = jobRepository.getRunningJobOfTopic(version.kafkaTopicName()).get(0);
    Assert.assertEquals(job.tasksInPartition(0).size(), 1, "One executor is running now.");
    // Node failed
    this.manager.disconnect();
    Thread.sleep(1000L);
    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.ERROR,
        "Job should be terminated with ERROR. Because one of node is failed.");
  }

  @Test(timeOut = 15000, dataProvider = "offlinePushStrategies")
  public void testHandleOutOfOrderMessages(OfflinePushStrategy offlinePushStrategy)
      throws IOException, InterruptedException {
    metadataRepository.addStore(store);
    HelixStatusMessageChannel controllerChannel = new HelixStatusMessageChannel(controller);
    controllerChannel.registerHandler(StoreStatusMessage.class, jobManager);
    HelixStatusMessageChannel nodeChannel = new HelixStatusMessageChannel(manager);

    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1, offlinePushStrategy);
    StoreStatusMessage message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    nodeChannel.sendToController(message);
    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.COMPLETED);
    nodeChannel.sendToController(message);
    // Wait until job manager has processed update message.
    long startTime = System.currentTimeMillis();
    do {
      Thread.sleep(300);
      if (System.currentTimeMillis() - startTime > 3000) {
        Assert.fail("Time out when waiting receiving status udpate message");
      }
    } while (!jobManager.getOfflineJobStatus(version.kafkaTopicName()).equals(ExecutionStatus.COMPLETED));
    //Send message again after job is completed. Exception will be catch and process dose not exit.
    nodeChannel.sendToController(message);

    //Start a new push
    store = metadataRepository.getStore(storeName);
    Version newVersion = store.increaseVersion();
    Assert.assertEquals(newVersion.getNumber(), version.getNumber() + 1);
    metadataRepository.updateStore(store);
    admin.addResource(cluster, newVersion.kafkaTopicName(), 1,
        MockTestStateModel.UNIT_TEST_STATE_MODEL,
        IdealState.RebalanceMode.FULL_AUTO.toString());
    admin.rebalance(cluster, newVersion.kafkaTopicName(), 1);
    Assert.assertEquals(metadataRepository.getStore(storeName).getCurrentVersion(), version.getNumber());
    jobManager.startOfflineJob(newVersion.kafkaTopicName(), 1, 1, offlinePushStrategy);
    message = new StoreStatusMessage(newVersion.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    nodeChannel.sendToController(message);
    Assert.assertEquals(jobManager.getOfflineJobStatus(newVersion.kafkaTopicName()), ExecutionStatus.STARTED);
    message = new StoreStatusMessage(newVersion.kafkaTopicName(), 0, nodeId, ExecutionStatus.COMPLETED);
    nodeChannel.sendToController(message);
    startTime = System.currentTimeMillis();
    do {
      store = metadataRepository.getStore(storeName);
      Thread.sleep(300);
      if (System.currentTimeMillis() - startTime > 3000) {
        Assert.fail("Time out when waiting receiving status udpate message");
      }
    } while (store.getCurrentVersion() != newVersion.getNumber());
    //Assert everything works well for the new push.
    Assert.assertEquals(jobManager.getOfflineJobStatus(newVersion.kafkaTopicName()), ExecutionStatus.COMPLETED);
    Assert.assertEquals(metadataRepository.getStore(storeName).getCurrentVersion(), newVersion.getNumber());
  }

  @Test(dataProvider = "offlinePushStrategies")
  public void testLoadJobsFromZKWithCompletedTask(OfflinePushStrategy offlinePushStrategy) {
    testLoadJobsFromZk(ExecutionStatus.COMPLETED, offlinePushStrategy);
  }

  @Test(dataProvider = "offlinePushStrategies")
  public void testLoadJobsFromZkWithErrorTask(OfflinePushStrategy offlinePushStrategy){
    testLoadJobsFromZk(ExecutionStatus.ERROR, offlinePushStrategy);
  }

  private void testLoadJobsFromZk(ExecutionStatus taskStatus, OfflinePushStrategy offlinePushStrategy) {
    metadataRepository.addStore(store);

    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1, offlinePushStrategy);
    OfflineJob job = (OfflineJob) jobRepository.getRunningJobOfTopic(version.kafkaTopicName()).get(0);
    Task task = new Task(job.generateTaskId(0, nodeId), 0, nodeId, ExecutionStatus.STARTED);
    job.updateTaskStatus(task);
    task = new Task(job.generateTaskId(0, nodeId), 0, nodeId, taskStatus);
    // Mock the situation that all of tasks are completed, but do not update job's status because controller is failed.
    jobRepository.updateTaskStatus(job.getJobId(), version.kafkaTopicName(), task);

    //Refresh repository to load data from ZK again
    jobRepository.refresh();
    Assert.assertEquals(jobRepository.getRunningJobOfTopic(version.kafkaTopicName()).size(), 1);
    jobManager.checkAllExistingJobs();
    // After checking all existing jobs, job status should be updated corespondingly.
    Assert.assertEquals(jobRepository.getRunningJobOfTopic(version.kafkaTopicName()).size(), 0);
    Assert.assertEquals(jobRepository.getTerminatedJobOfTopic(version.kafkaTopicName()).size(), 1);
  }



  /**
   * Test receive status message for the job which has not been started. Basic idea is create a new version with 1
   * partition and 2 replicas per partition, but only start one participant at first. Then start a offline push job for
   * this new version. In that case, job should be blocked on waiting another participant up. During the waiting time,
   * send the status message to controller. Start up the second participant. If the retry mechanism works well, status
   * message will be processed eventually.
   *
   * TODO: Fix this test. It is flaky.
   */
  @Test(dataProvider = "offlinePushStrategies", retryAnalyzer = FlakyTestRetryAnalyzer.class)
  public void testReceiveMessageBeforeJobStart(OfflinePushStrategy offlinePushStrategy)
      throws InterruptedException {
    int partitionCount = 1;
    int replciaCount = 2;
    HelixStatusMessageChannel controllerChannel = new HelixStatusMessageChannel(controller);
    controllerChannel.registerHandler(StoreStatusMessage.class, jobManager);

    Version newVersion = store.increaseVersion();
    metadataRepository.addStore(store);
    //create a helix resource with 1 partition and 2 replica per partition.
    admin.addResource(cluster, newVersion.kafkaTopicName(), partitionCount, MockTestStateModel.UNIT_TEST_STATE_MODEL,
        IdealState.RebalanceMode.FULL_AUTO.toString());
    admin.rebalance(cluster, newVersion.kafkaTopicName(), replciaCount);

    //Create a thread to send status message before job is started.
    Thread sendThread = new Thread(new Runnable() {
      @Override
      public void run() {
        HelixStatusMessageChannel channel = new HelixStatusMessageChannel(manager);
        StoreStatusMessage veniceMessage = new StoreStatusMessage(newVersion.kafkaTopicName(), 0, manager.getInstanceName(), ExecutionStatus.STARTED);
        try {
          channel.sendToController(veniceMessage, 20, 200l);
          Assert.assertEquals(routingDataRepository.getPartitionAssignments(newVersion.kafkaTopicName())
              .getAssignedNumberOfPartitions(), partitionCount);
          Assert.assertEquals(routingDataRepository.getPartitionAssignments(newVersion.kafkaTopicName())
              .getPartition(0)
              .getBootstrapAndReadyToServeInstances()
              .size(), replciaCount);
        } catch (VeniceException e) {
          Assert.fail("Message should be handled correclty before job starting.");
        }
      }
    });
    sendThread.start();

    // Create a new participant
    final String newNodeId = Utils.getHelixNodeIdentifier(13467);
    HelixManager newParticipant = TestUtils.getParticipant(cluster, newNodeId, zkAddress, httpPort,
        MockTestStateModel.UNIT_TEST_STATE_MODEL);
    Thread newParticipantThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          //Delayed start participant to keep job waiting for starting.
          Thread.sleep(1000l);
          newParticipant.connect();
        } catch (Exception e) {
          Assert.fail("New participant can not be started.");
        }
      }
    });
    newParticipantThread.start();

    try {
      jobManager.startOfflineJob(newVersion.kafkaTopicName(), partitionCount, replciaCount, offlinePushStrategy);
      Set<String> nodeIdSet = new HashSet<>();
      for (Instance instance : routingDataRepository.getPartitionAssignments(newVersion.kafkaTopicName())
          .getPartition(0)
          .getBootstrapAndReadyToServeInstances()) {
        nodeIdSet.add(instance.getNodeId());
      }
      // Ensure after job is started, two participants are allocated for this job.
      Assert.assertTrue(nodeIdSet.contains(newNodeId));
      sendThread.join();
    } finally {
      newParticipant.disconnect();
    }
  }

  @Test(dataProvider = "offlinePushStrategies")
  public void testReceiveMessageWhenStorageRestarting(OfflinePushStrategy offlinePushStrategy)
      throws Exception {
    int partitionCount = 1;
    int replcaCount = 2;
    Version newVersion = store.increaseVersion();
    metadataRepository.addStore(store);
    //create a helix resource with 1 partition and 2 replica per partition.
    admin.addResource(cluster, newVersion.kafkaTopicName(), partitionCount,
        MockTestStateModel.UNIT_TEST_STATE_MODEL,
        IdealState.RebalanceMode.FULL_AUTO.toString());
    admin.rebalance(cluster, newVersion.kafkaTopicName(), replcaCount);

    HelixStatusMessageChannel controllerChannel = new HelixStatusMessageChannel(controller);
    controllerChannel.registerHandler(StoreStatusMessage.class, jobManager);

    Thread startThread = new Thread(new Runnable() {
      @Override
      public void run() {
        StoreStatusMessage veniceMessage =
            new StoreStatusMessage(newVersion.kafkaTopicName(), 0, manager.getInstanceName(), ExecutionStatus.STARTED);
        //Send start at first
        HelixStatusMessageChannel channel = new HelixStatusMessageChannel(manager);
        channel.sendToController(veniceMessage, 20, 500);
      }
    });
    startThread.start();
    HelixManager newParticipant =
        TestUtils.getParticipant(cluster, Utils.getHelixNodeIdentifier(13467), zkAddress, httpPort,
            MockTestStateModel.UNIT_TEST_STATE_MODEL);
    newParticipant.connect();
    jobManager.startOfflineJob(newVersion.kafkaTopicName(), 1, 1, offlinePushStrategy);
    startThread.join();

    OfflineJob job = (OfflineJob) jobRepository.getRunningJobOfTopic(newVersion.kafkaTopicName()).get(0);
    Assert.assertEquals(job.getTaskStatus(0, job.generateTaskId(0, nodeId)), ExecutionStatus.STARTED);
    manager.disconnect();

    // ensure task has been deleted
    TestUtils.waitForNonDeterministicCompletion(2, TimeUnit.SECONDS,
        () -> job.getTask(0, job.generateTaskId(0, nodeId)) == null);

    //Restart participant
    manager = TestUtils.getParticipant(cluster, nodeId, zkAddress, httpPort,
        MockTestStateModel.UNIT_TEST_STATE_MODEL);
    Thread restartThread = new Thread(new Runnable() {
      @Override
      public void run() {
        HelixStatusMessageChannel channel = new HelixStatusMessageChannel(manager);
        StoreStatusMessage veniceMessage =
            new StoreStatusMessage(newVersion.kafkaTopicName(), 0, manager.getInstanceName(), ExecutionStatus.STARTED);
        channel.sendToController(veniceMessage, 20, 500);
      }
    });
    restartThread.start();
    manager.connect();
    restartThread.join();
    //Ensure the started message send to controller correctly after restarting.
    Assert.assertEquals(job.getTaskStatus(0, job.generateTaskId(0, nodeId)), ExecutionStatus.STARTED);
    newParticipant.disconnect();
  }

  @Test(expectedExceptions = VeniceException.class, dataProvider = "offlinePushStrategies")
  public void testHandleRoutingDataChangedJobNotStart(OfflinePushStrategy offlinePushStrategy) {
    metadataRepository.addStore(store);
    // Starting a job with 1 partition and 2 replicas, as we only started one participant in setup method, so the job
    // can not be started due to not enough executor.
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 2, offlinePushStrategy);

    Store updatedStore = metadataRepository.getStore(storeName);
    Assert.assertEquals(updatedStore.getVersions().get(0).getStatus(), VersionStatus.ERROR);
  }

  @Test(dataProvider = "offlinePushStrategies")
  public void testHandleRoutingDataChangedJobIsRunning(OfflinePushStrategy offlinePushStrategy)
      throws Exception {

    Version newVersion = store.increaseVersion();
    metadataRepository.addStore(store);
    //create a helix resource with 1 partition and 2 replica per partition.
    admin.addResource(cluster, newVersion.kafkaTopicName(), 1 , MockTestStateModel.UNIT_TEST_STATE_MODEL,
        IdealState.RebalanceMode.FULL_AUTO.toString());
    admin.rebalance(cluster, newVersion.kafkaTopicName(), 2);


    int newPort = httpPort + 1;
    HelixManager newParticipant =
        HelixManagerFactory.getZKHelixManager(cluster, Utils.getHelixNodeIdentifier(newPort), InstanceType.PARTICIPANT,
            zkAddress);
    newParticipant.getStateMachineEngine()
        .registerStateModelFactory(MockTestStateModel.UNIT_TEST_STATE_MODEL,
            new MockTestStateModelFactory());
    Instance instance = new Instance(Utils.getHelixNodeIdentifier(newPort), Utils.getHostName(), newPort);
    newParticipant.setLiveInstanceInfoProvider(new LiveInstanceInfoProvider() {
      @Override
      public ZNRecord getAdditionalLiveInstanceInfo() {
        return HelixInstanceConverter.convertInstanceToZNRecord(instance);
      }
    });
    newParticipant.connect();

    jobManager.startOfflineJob(newVersion.kafkaTopicName(), 1, 2, offlinePushStrategy);

    newParticipant.disconnect();
    TestUtils.waitForNonDeterministicCompletion(2, TimeUnit.SECONDS, () ->
        routingDataRepository.getPartitionAssignments(newVersion.kafkaTopicName())
            .getPartition(0)
            .getBootstrapAndReadyToServeInstances().size() == 1);
    //Lost one replica, job is still running.
    Assert.assertEquals(ExecutionStatus.STARTED, jobManager.getOfflineJobStatus(newVersion.kafkaTopicName()));

    HelixStatusMessageChannel controllerChannel = new HelixStatusMessageChannel(controller);
    controllerChannel.registerHandler(StoreStatusMessage.class, jobManager);
    HelixStatusMessageChannel channel = new HelixStatusMessageChannel(manager);
    StoreStatusMessage veniceMessage = new StoreStatusMessage(newVersion.kafkaTopicName(), 0, manager.getInstanceName(), ExecutionStatus.STARTED);
    channel.sendToController(veniceMessage);
    veniceMessage = new StoreStatusMessage(newVersion.kafkaTopicName(), 0, manager.getInstanceName(), ExecutionStatus.ERROR);
    channel.sendToController(veniceMessage);
    //Lost one replica, and another replica report ERROR status.

    TestUtils.waitForNonDeterministicCompletion(2, TimeUnit.SECONDS,
        () -> jobManager.getOfflineJobStatus(newVersion.kafkaTopicName()).equals(ExecutionStatus.ERROR));

  }
}
