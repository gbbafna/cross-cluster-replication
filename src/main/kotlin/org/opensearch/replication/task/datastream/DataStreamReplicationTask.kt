package org.opensearch.replication.task.datastream

import kotlinx.coroutines.CoroutineScope
import org.apache.logging.log4j.Logger
import org.opensearch.client.Client
import org.opensearch.cluster.ClusterChangedEvent
import org.opensearch.cluster.ClusterStateListener
import org.opensearch.cluster.ClusterStateObserver
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.SettingsModule
import org.opensearch.persistent.PersistentTaskState
import org.opensearch.persistent.PersistentTasksService
import org.opensearch.replication.ReplicationSettings
import org.opensearch.replication.metadata.ReplicationMetadataManager
import org.opensearch.replication.task.CrossClusterReplicationTask
import org.opensearch.replication.task.index.IndexReplicationParams
import org.opensearch.tasks.TaskId
import org.opensearch.threadpool.ThreadPool

class DataStreamReplicationTask(id: Long, type: String, action: String, description: String,
                           parentTask: TaskId,
                           executor: String,
                           clusterService: ClusterService,
                           threadPool: ThreadPool,
                           client: Client,
                           params: IndexReplicationParams,
                           private val persistentTasksService: PersistentTasksService,
                           replicationMetadataManager: ReplicationMetadataManager,
                           replicationSettings: ReplicationSettings,
                           val settingsModule: SettingsModule,
                           val cso: ClusterStateObserver)
    : CrossClusterReplicationTask(id, type, action, description, parentTask, emptyMap(), executor,
        clusterService, threadPool, client, replicationMetadataManager, replicationSettings), ClusterStateListener
{
    override val log: Logger
        get() = TODO("Not yet implemented")
    override val followerIndexName: String
        get() = TODO("Not yet implemented")
    override val leaderAlias: String
        get() = TODO("Not yet implemented")

    override fun replicationTaskResponse(): CrossClusterReplicationTaskResponse {
        TODO("Not yet implemented")
    }

    override suspend fun execute(scope: CoroutineScope, initialState: PersistentTaskState?) {
        TODO("Not yet implemented")
    }

    override suspend fun setReplicationMetadata() {
        TODO("Not yet implemented")
    }

    override fun clusterChanged(event: ClusterChangedEvent?) {
        TODO("Not yet implemented")
    }

}