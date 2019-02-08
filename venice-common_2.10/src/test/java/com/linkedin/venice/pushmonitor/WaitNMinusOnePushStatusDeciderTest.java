package com.linkedin.venice.pushmonitor;

import com.linkedin.venice.helix.HelixState;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.Partition;
import com.linkedin.venice.meta.PartitionAssignment;
import java.util.HashMap;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.linkedin.venice.pushmonitor.ExecutionStatus.*;


public class WaitNMinusOnePushStatusDeciderTest extends TestPushStatusDecider{
  private WaitNMinusOnePushStatusDecider statusDecider = new WaitNMinusOnePushStatusDecider();
  private OfflinePushStatus pushStatus;

  @BeforeMethod
  public void setup() {
    numberOfPartition = 2;
    replicationFactor = 3;
    topic = "WaitNMinusOnePushStatusDeciderTest";
    pushStatus = new OfflinePushStatus(topic, numberOfPartition, replicationFactor, statusDecider.getStrategy());
    partitionAssignment = new PartitionAssignment(topic, numberOfPartition);
    createPartitions(numberOfPartition, replicationFactor);
  }

  @Test
  public void testCheckPushStatus() {
    // All replica are in bootstrap status, so the push is still in STARTED status.
    Assert.assertEquals(statusDecider.checkPushStatus(pushStatus, partitionAssignment), ExecutionStatus.STARTED);

    int partitionId = 0;
    for (int j = 0; j < replicationFactor - 1; j++) {
      Partition oldPartition = partitionAssignment.getPartition(partitionId);
      Partition newPartition = changeReplicaState(oldPartition, nodeId + j, HelixState.ONLINE);
      partitionAssignment.addPartition(newPartition);
    }
    // N-1 replicas in partition 0 are completed, but replicas in partition 1 are still in started.
    Assert.assertEquals(statusDecider.checkPushStatus(pushStatus, partitionAssignment), ExecutionStatus.STARTED);

    partitionId = 1;
    for (int j = 0; j < replicationFactor - 1; j++) {
      Partition oldPartition = partitionAssignment.getPartition(partitionId);
      Partition newPartition = changeReplicaState(oldPartition, nodeId + j, HelixState.ONLINE);
      partitionAssignment.addPartition(newPartition);
    }
    // In both partition, n-1 replicas are ONLINE
    Assert.assertEquals(statusDecider.checkPushStatus(pushStatus, partitionAssignment), ExecutionStatus.COMPLETED);

    for (partitionId = 0; partitionId < numberOfPartition; partitionId++) {
      Partition oldPartition = partitionAssignment.getPartition(partitionId);
      Partition newPartition = oldPartition.withRemovedInstance(nodeId + (replicationFactor - 1));
      partitionAssignment.addPartition(newPartition);
    }
    // One of instance is disconnected. But rest of replicas satisfy n-1 requirement.
    Assert.assertEquals(statusDecider.checkPushStatus(pushStatus, partitionAssignment), ExecutionStatus.COMPLETED);
  }

  @Test
  public void testCheckPushStatusReturnError() {
    int partitionId = 0;
    // One of replica is error
    Partition oldPartition = partitionAssignment.getPartition(partitionId);
    Partition newPartition = changeReplicaState(oldPartition, nodeId + 0, HelixState.ERROR);
    partitionAssignment.addPartition(newPartition);
    Assert.assertEquals(statusDecider.checkPushStatus(pushStatus, partitionAssignment), ExecutionStatus.STARTED);
    // Two error replicas but in different partitions.
    partitionId = 1;
    oldPartition = partitionAssignment.getPartition(partitionId);
    newPartition = changeReplicaState(oldPartition, nodeId + 0, HelixState.ERROR);
    partitionAssignment.addPartition(newPartition);
    Assert.assertEquals(statusDecider.checkPushStatus(pushStatus, partitionAssignment), ExecutionStatus.STARTED);
    // Two error replicas under the same partition.
    oldPartition = partitionAssignment.getPartition(partitionId);
    newPartition = changeReplicaState(oldPartition, nodeId + 1, HelixState.ERROR);
    partitionAssignment.addPartition(newPartition);
    Assert.assertEquals(statusDecider.checkPushStatus(pushStatus, partitionAssignment), ExecutionStatus.ERROR);
  }

  @Test
  public void testGetPartitionStatus() {
    PartitionStatus partitionStatus = new PartitionStatus(0);

    Map<Instance, String> instanceToStateMap = new HashMap<>();
    instanceToStateMap.put(new Instance("instance0", "host0", 1), HelixState.STANDBY_STATE);
    instanceToStateMap.put(new Instance("instance1", "host1", 1), HelixState.STANDBY_STATE);
    instanceToStateMap.put(new Instance("instance2", "host2", 1), HelixState.LEADER_STATE);

    //Not enough replicas
    partitionStatus.updateReplicaStatus("instance0", COMPLETED);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), STARTED);

    //have n - 1 nodes finished, but leader is still bootstrapping
    partitionStatus.updateReplicaStatus("instance1", COMPLETED);
    partitionStatus.updateReplicaStatus("instance2", STARTED);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), STARTED);

    //have n - 1 nodes finished (include leader)
    partitionStatus.updateReplicaStatus("instance1", STARTED);
    partitionStatus.updateReplicaStatus("instance2", COMPLETED);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), COMPLETED);

    //all the replicas have finished
    partitionStatus.updateReplicaStatus("instance1", COMPLETED);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), COMPLETED);

    //one of the replicas failed
    partitionStatus.updateReplicaStatus("instance1", ERROR);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), COMPLETED);

    //another has also failed
    partitionStatus.updateReplicaStatus("instance0", ERROR);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), ERROR);

    //a new replica joined but yet registered in external view
    partitionStatus.updateReplicaStatus("instance3", STARTED);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), ERROR);

    instanceToStateMap.put(new Instance("instance3", "host3", 1), HelixState.STANDBY_STATE);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), STARTED);

    //new replica has finished
    partitionStatus.updateReplicaStatus("instance3", COMPLETED);
    Assert.assertEquals(statusDecider.getPartitionStatus(partitionStatus, replicationFactor, instanceToStateMap), COMPLETED);
  }
}
