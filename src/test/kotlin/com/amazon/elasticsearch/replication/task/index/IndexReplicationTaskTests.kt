package com.amazon.elasticsearch.replication.task.index


import com.amazon.elasticsearch.replication.ReplicationPlugin
import com.amazon.elasticsearch.replication.ReplicationSettings
import com.amazon.elasticsearch.replication.action.index.block.UpdateIndexBlockAction
import com.amazon.elasticsearch.replication.metadata.ReplicationMetadataManager
import com.amazon.elasticsearch.replication.metadata.store.ReplicationContext
import com.amazon.elasticsearch.replication.metadata.store.ReplicationMetadata
import com.amazon.elasticsearch.replication.repository.REMOTE_REPOSITORY_PREFIX
import com.amazon.elasticsearch.replication.task.shard.ShardReplicationExecutor
import com.amazon.elasticsearch.replication.task.shard.ShardReplicationParams
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doAnswer
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.stub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.elasticsearch.Version
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.ActionType
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotAction
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse
import org.elasticsearch.action.admin.indices.recovery.RecoveryAction
import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsAction
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsAction
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.ClusterStateObserver
import org.elasticsearch.cluster.RestoreInProgress
import org.elasticsearch.cluster.metadata.IndexMetadata
import org.elasticsearch.cluster.metadata.Metadata
import org.elasticsearch.cluster.routing.RoutingTable
import org.elasticsearch.common.UUIDs
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.settings.SettingsModule
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.Index
import org.elasticsearch.index.IndexSettings
import org.elasticsearch.index.shard.ShardId
import org.elasticsearch.indices.recovery.RecoveryState
import org.elasticsearch.persistent.PersistentTaskParams
import org.elasticsearch.persistent.PersistentTaskResponse
import org.elasticsearch.persistent.PersistentTasksCustomMetadata
import org.elasticsearch.persistent.PersistentTasksCustomMetadata.PersistentTask
import org.elasticsearch.persistent.PersistentTasksService
import org.elasticsearch.persistent.StartPersistentTaskAction
import org.elasticsearch.persistent.UpdatePersistentTaskStatusAction
import org.elasticsearch.snapshots.RestoreInfo
import org.elasticsearch.snapshots.Snapshot
import org.elasticsearch.snapshots.SnapshotId
import org.elasticsearch.tasks.TaskId.EMPTY_TASK_ID
import org.elasticsearch.tasks.TaskManager
import org.elasticsearch.test.ClusterServiceUtils
import org.elasticsearch.test.ClusterServiceUtils.setState
import org.elasticsearch.test.ESTestCase
import org.elasticsearch.test.ESTestCase.assertBusy
import org.elasticsearch.test.client.NoOpNodeClient
import org.elasticsearch.threadpool.TestThreadPool
import org.junit.Test
import org.mockito.Mockito
import java.lang.reflect.Field
import java.util.*
import java.util.concurrent.TimeUnit

object MockitoHelper {
    fun <T> anyObject(): T {
        Mockito.any<T>()
        return uninitialized()
    }
    @Suppress("UNCHECKED_CAST")
    fun <T> uninitialized(): T =  null as T
}


//@RunWith(RandomizedRunner::class)
//@ThreadLeakLingering(linger = 0) // wait for "Connection worker" to die
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
class IndexReplicationTaskTests : ESTestCase() {

    companion object {
        var currentTaskState :IndexReplicationState = InitialState
        var stateChanges :Int = 0
        var restoreNotNull = false

        var followerIndex = "follower-index"
        var connectionName = "leader-cluster"
        var remoteCluster = "remote-cluster"
    }

    var threadPool = TestThreadPool("MultiSearchActionTookTests")
    var clusterService  = ClusterServiceUtils.createClusterService(threadPool)

    @Test
     fun testKotlinExec() = runBlocking {
        val mockedObject: IndexReplicationTask = spy(createIndexReplicationTask())

        mockedObject.stub {
            onBlocking { SuspendF() }.doReturn("abc")
           // onBlocking { setupAndStartRestore() }.doReturn(FailedState(mapOf(), ""))
        }

        doReturn("abc").`when`(mockedObject).notSuspend()

        //Works for non suspend function
        var ns = mockedObject.notSuspend()
        assert(ns == "abc")

        //Doesn't work for non suspend function
        ns = mockedObject.SuspendF()
        assert(ns == "abc")

        //doReturn("abc").`when`(mockedObject).SuspendF()
        //doReturn(FailedState(mapOf(), "")).`when`(mockedObject).setupAndStartRestore()

        mockedObject.execute(this, InitialState)
        var a = mockedObject.setupAndStartRestore()
        assert(a == FollowingState(emptyMap()))
    }


    @Test
    fun testExec_Normal() = runBlocking {
        val replicationTask: IndexReplicationTask = spy(createIndexReplicationTask())
        var tm = Mockito.mock(TaskManager::class.java)
        replicationTask.setPersistent(tm)
        var rc = ReplicationContext("index")
        var rm = ReplicationMetadata("con", "a", "run", "reason", rc, rc, Settings.EMPTY)
        replicationTask.setReplicationMetadata(rm)


        //delay(1000)

        //Update ClusterState to say restore started
        val state: ClusterState = clusterService.state()

        var newClusterState: ClusterState

        // Updating cluster state
        var builder: ClusterState.Builder = ClusterState.builder(state)
        val indices: MutableList<String> = ArrayList()
        indices.add(followerIndex)
        val snapshot = Snapshot("$REMOTE_REPOSITORY_PREFIX$connectionName", SnapshotId("randomAlphaOfLength", "randomAlphaOfLength"))
        val restoreEntry = RestoreInProgress.Entry("restoreUUID", snapshot, RestoreInProgress.State.INIT, Collections.unmodifiableList(ArrayList<String>(indices)),
                null)
        builder.putCustom(RestoreInProgress.TYPE, RestoreInProgress.Builder(
                state.custom(RestoreInProgress.TYPE, RestoreInProgress.EMPTY)).add(restoreEntry).build())

        newClusterState = builder.build()
        setState(clusterService, newClusterState)

        val job = this.launch{
            replicationTask.execute(this, InitialState)
        }

        delay(1000)

        // Assert we move to RESTORING .. This is blocking and won't let the test run
        assertBusy({
            assertThat(currentTaskState == RestoreState).isTrue()
        },  1, TimeUnit.SECONDS)


        //Complete the Restore


        val metaBuilder = Metadata.builder()
        metaBuilder.put(IndexMetadata.builder(followerIndex).settings(ESTestCase.settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0))
        val metadata = metaBuilder.build()
        val routingTableBuilder = RoutingTable.builder()
        routingTableBuilder.addAsNew(metadata.index(followerIndex))
        val routingTable = routingTableBuilder.build()

        builder = ClusterState.builder(state).routingTable(routingTable)
        builder.putCustom(RestoreInProgress.TYPE, RestoreInProgress.Builder(
                state.custom(RestoreInProgress.TYPE, RestoreInProgress.EMPTY)).build())

        newClusterState = builder.build()
        setState(clusterService, newClusterState)

        delay(1000)

        assertBusy {
            assertThat(currentTaskState == MonitoringState).isTrue()
        }

        job.cancel()

    }

    open class NoOpNodeClient1(testName :String) : NoOpNodeClient(testName) {
        @Override
        override fun <Request : ActionRequest, Response : ActionResponse> doExecute(action: ActionType<Response>?, request: Request?, listener: ActionListener<Response>) {
            if (action == UpdateSettingsAction.INSTANCE) {
                //Update setting to prevent pruning on leader
                var settingResponse = AcknowledgedResponse(true)
                listener.onResponse(settingResponse as Response)
            } else if (action == RestoreSnapshotAction.INSTANCE) {
                //begin snapshot operation
                var snapResponse = RestoreSnapshotResponse(null as RestoreInfo?)
                if (restoreNotNull) {
                    snapResponse = RestoreSnapshotResponse(RestoreInfo("name", emptyList(), 1, 1))
                }
                listener.onResponse(snapResponse as Response)
            } else if (action == UpdatePersistentTaskStatusAction.INSTANCE) {
                // update status of index replication task
                var r = request as UpdatePersistentTaskStatusAction.Request
                val obj: Class<*> = r.javaClass
                // access the private variable "state"
                val field: Field = obj.getDeclaredField("state")
                field.setAccessible(true)
                val taskState = field.get(r) as IndexReplicationState

                currentTaskState = taskState
                stateChanges++

                var t = Mockito.mock(PersistentTasksCustomMetadata.PersistentTask::class.java)
                var t1 = Mockito.mock(PersistentTaskResponse::class.java)
                doReturn(t).`when`(t1).task
                doReturn(taskState).`when`(t).getState()
                //var settingResponse = PersistentTaskResponse(true)
                listener.onResponse(t1 as Response)
            } else if (action == UpdateIndexBlockAction.INSTANCE) {
                // applies index block
                var settingResponse = AcknowledgedResponse(true)
                listener.onResponse(settingResponse as Response)
            }  else if (action == StartPersistentTaskAction.INSTANCE) {
                var sId = ShardId(Index(followerIndex, "_na_"), 0)
                var t1 = PersistentTaskResponse(
                        PersistentTask<ShardReplicationParams>(UUIDs.base64UUID(), ShardReplicationExecutor.TASK_NAME,
                                ShardReplicationParams(remoteCluster, sId, sId),
                                randomLong(), PersistentTasksCustomMetadata.INITIAL_ASSIGNMENT))

                listener.onResponse(t1 as Response)
            } else if (action == GetSettingsAction.INSTANCE) {
                //called in doesValidIndexExists after restore is complete
                val desiredSettingsBuilder = Settings.builder()
                desiredSettingsBuilder.put(ReplicationPlugin.REPLICATED_INDEX_SETTING.key, "true")

                val indexToSettings = HashMap<String, Settings>()
                indexToSettings["follower-index"] =  desiredSettingsBuilder.build()

                val settingsMap = ImmutableOpenMap.builder<String, Settings>().putAll(indexToSettings).build()
                var settingResponse = GetSettingsResponse(settingsMap, settingsMap)
                listener.onResponse(settingResponse as Response)
            } else if (action == RecoveryAction.INSTANCE) {
                val shardRecoveryStates: MutableMap<String, List<RecoveryState>> = HashMap()
                val recoveryStates: MutableList<RecoveryState> = ArrayList()
                recoveryStates.add(Mockito.mock(RecoveryState::class.java))
                shardRecoveryStates.put("follower-index", recoveryStates)
                var recoveryResponse = RecoveryResponse(1,1, 1, shardRecoveryStates, listOf())
                listener.onResponse(recoveryResponse as Response)
            }
        }
    }

    private fun createIndexReplicationTask() : IndexReplicationTask {
        var threadPool = TestThreadPool("MultiSearchActionTookTests")
        clusterService = ClusterServiceUtils.createClusterService(threadPool)
        val settingsModule = Mockito.mock(SettingsModule::class.java)

        val replicationMetadataManager = Mockito.mock(ReplicationMetadataManager::class.java)

        val spyClient = Mockito.spy<NoOpNodeClient1>(NoOpNodeClient1("testName"))
        var persist = PersistentTasksService(clusterService, threadPool, spyClient)
        val state: ClusterState = clusterService.state()
        val tasks = PersistentTasksCustomMetadata.builder()
        var sId = ShardId(Index(followerIndex, "_na_"), 0)
        tasks.addTask<PersistentTaskParams>( "replication:0", ShardReplicationExecutor.TASK_NAME, ShardReplicationParams("remoteCluster", sId,  sId),
                PersistentTasksCustomMetadata.Assignment("other_node_", "test assignment on other node"))

        val metadata = Metadata.builder(state.metadata())
        metadata.putCustom(PersistentTasksCustomMetadata.TYPE, tasks.build())
        val newClusterState: ClusterState = ClusterState.builder(state).metadata(metadata).build()

        setState(clusterService, newClusterState)

        doAnswer{  invocation -> spyClient }.`when`(spyClient).getRemoteClusterClient(any())
        assert(spyClient.getRemoteClusterClient("remoteCluster") == spyClient)

        //val replicationSettings = mockk<ReplicationSettings>()
        //val replicationSettings = ReplicationSettings(clusterService)

        val replicationSettings = Mockito.mock(ReplicationSettings::class.java)
        replicationSettings.metadataSyncInterval = TimeValue(100, TimeUnit.MILLISECONDS)

        //val cso = Mockito.mock(ClusterStateObserver::class.java)
        val cso = ClusterStateObserver(clusterService, logger, threadPool.threadContext)

        //Issue :   Mockito.any() pass Interface with Generics
        /*
        doAnswer {
            invocation -> (invocation.arguments[0] as ClusterStateObserver.Listener).onNewClusterState(newClusterState)
        }.`when`(cso).waitForNextChange(Mockito.any(ClusterStateObserver.Listener::class.java), ArgumentMatcher.<Predicate<ClusterState>>any())
        //, Mockito.any(Predicate<ClusterState>::class.java)

         */


        //val cso = ClusterStateObserver(clusterService, null, null, threadPool.getThreadContext())
        val indexReplicationTask = IndexReplicationTask(1, "type", "action", "Description" , EMPTY_TASK_ID,
                ReplicationPlugin.REPLICATION_EXECUTOR_NAME_FOLLOWER, clusterService , threadPool, spyClient, IndexReplicationParams(connectionName, Index(followerIndex, "0"), followerIndex),
                persist, replicationMetadataManager, replicationSettings, settingsModule,cso)


        var settingResponse = AcknowledgedResponse(true)

        //Not working -- Not sure why
        doAnswer {
            invocation -> (invocation.arguments[2] as ActionListener<AcknowledgedResponse>).onResponse(settingResponse)
        }.`when`(spyClient).doExecute(Mockito.eq(UpdateSettingsAction.INSTANCE) , MockitoHelper.anyObject(), MockitoHelper.anyObject())

        //Test doAnswer
        val settingsBuilder = Settings.builder().put(IndexSettings.INDEX_TRANSLOG_RETENTION_LEASE_PRUNING_ENABLED_SETTING.key, true)
        val updateSettingsRequest = spyClient.getRemoteClusterClient("abc").admin().indices().prepareUpdateSettings().setSettings(settingsBuilder).setIndices("a").request()
        val updateResponse = spyClient.admin().indices().updateSettings(updateSettingsRequest).actionGet()
        //NPE Here
        //assertEquals(updateResponse.isAcknowledged, true)

        // This works perfectly fine
        spyClient.doExecute(UpdateSettingsAction.INSTANCE, updateSettingsRequest, object : ActionListener<AcknowledgedResponse> {
            override fun onResponse(response: AcknowledgedResponse?) {
                    var resp = response
                if (resp != null) {
                    assertEquals(resp.isAcknowledged, true)
                }
            }

            override fun onFailure(t: Exception) {
            }
        })


//        doAnswer { invocation -> (invocation.arguments[2] as PlainActionFuture<RestoreSnapshotResponse>).onResponse(snapResponse) }.`when`(spyClient).
//        doExecute(RestoreSnapshotAction.INSTANCE , MockitoHelper.anyObject(), MockitoHelper.anyObject())

        //`when`(spyClient.admin().indices().updateSettings(any())).thenReturn(listener)
        //doReturn(listener).`when`(spyClient.execute(Mockito.eq(UpdateSettingsAction.INSTANCE), Mockito.any(), Mockito.any()))
        //`when`(spyClient.execute(Mockito.eq(UpdateSettingsAction.INSTANCE), Mockito.any(), Mockito.any())).thenReturn(null)
        //doReturn(listener).`when`(spyClient.admin().indices().updateSettings(any()))

        return indexReplicationTask
    }


    @Test
    fun calculateAddsValues() {
        val doc1 = Dependency1(5)
        val doc2 = Dependency2("6")

        val sut = SystemUnderTest(doc1, doc2)

        //assertEquals(11, sut.calculate())

        //val doc3 = mockk<Dependency1>()
    }

    /*
    @Test
    fun calculateAddsValues2() {
        val doc1 = mockk<Dependency1>()
        val doc2 = mockk<Dependency2>()

        every { doc1.value1 } returns 5
        every { doc2.value2 } returns "6"

        val sut = SystemUnderTest(doc1, doc2)

        //assertEquals(11, sut.calculate())
    }

     */


}
