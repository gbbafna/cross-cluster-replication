/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.replication.task.autofollow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opensearch.OpenSearchException
import org.opensearch.OpenSearchSecurityException
import org.opensearch.action.admin.indices.get.GetIndexRequest
import org.opensearch.action.support.IndicesOptions
import org.opensearch.client.Client
import org.opensearch.cluster.ClusterState
import org.opensearch.cluster.ClusterStateObserver
import org.opensearch.cluster.RestoreInProgress
import org.opensearch.cluster.metadata.DataStream
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.io.stream.StreamInput
import org.opensearch.common.io.stream.StreamOutput
import org.opensearch.common.logging.Loggers
import org.opensearch.common.regex.Regex
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentBuilder
import org.opensearch.persistent.PersistentTaskState
import org.opensearch.replication.ReplicationException
import org.opensearch.replication.ReplicationSettings
import org.opensearch.replication.action.index.ReplicateIndexAction
import org.opensearch.replication.action.index.ReplicateIndexRequest
import org.opensearch.replication.metadata.ReplicationMetadataManager
import org.opensearch.replication.repository.REMOTE_SNAPSHOT_NAME
import org.opensearch.replication.repository.RemoteClusterRepository
import org.opensearch.replication.task.CrossClusterReplicationTask
import org.opensearch.replication.task.ReplicationState
import org.opensearch.replication.task.index.IndexReplicationExecutor
import org.opensearch.replication.util.stackTraceToString
import org.opensearch.replication.util.suspendExecute
import org.opensearch.replication.util.suspending
import org.opensearch.replication.util.waitForNextChange
import org.opensearch.rest.RestStatus
import org.opensearch.tasks.Task
import org.opensearch.tasks.TaskId
import org.opensearch.threadpool.Scheduler
import org.opensearch.threadpool.ThreadPool
import java.util.*
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.collections.HashMap
import kotlin.collections.set

class AutoFollowTask(id: Long, type: String, action: String, description: String, parentTask: TaskId,
                     headers: Map<String, String>,
                     executor: String,
                     clusterService: ClusterService,
                     threadPool: ThreadPool,
                     client: Client,
                     replicationMetadataManager: ReplicationMetadataManager,
                     val params: AutoFollowParams,
                     replicationSettings: ReplicationSettings) :
    CrossClusterReplicationTask(id, type, action, description, parentTask, headers,
                                executor, clusterService, threadPool, client, replicationMetadataManager, replicationSettings) {

    override val leaderAlias = params.leaderCluster
    val patternName = params.patternName
    override val followerIndexName: String = params.patternName //Special case for auto follow
    override val log = Loggers.getLogger(javaClass, leaderAlias)
    private var trackingIndicesOnTheCluster = setOf<String>()
    private var failedIndices = ConcurrentSkipListSet<String>() // Failed indices for replication from this autofollow task
    private var retryScheduler: Scheduler.ScheduledCancellable? = null
    lateinit var stat: AutoFollowStat
    val cso = ClusterStateObserver(clusterService, IndexReplicationExecutor.log, threadPool.threadContext)

    override suspend fun execute(scope: CoroutineScope, initialState: PersistentTaskState?) {
        stat = AutoFollowStat(params.patternName, replicationMetadata.leaderContext.resource)
        while (scope.isActive) {
            try {
                addRetryScheduler()
                autoFollow()
                delay(replicationSettings.autofollowFetchPollDuration.millis)
            }
            catch(e: OpenSearchException) {
                // Any transient error encountered during auto follow execution should be re-tried
                val status = e.status().status
                if(status < 500 && status != RestStatus.TOO_MANY_REQUESTS.status) {
                    log.error("Exiting autofollow task", e)
                    throw e
                }
                log.debug("Encountered transient error while running autofollow task", e)
                delay(replicationSettings.autofollowFetchPollDuration.millis)
            }
        }
    }

    private fun addRetryScheduler() {
        if(retryScheduler != null && !retryScheduler!!.isCancelled) {
            return
        }
        retryScheduler = try {
            threadPool.schedule({ failedIndices.clear() }, replicationSettings.autofollowRetryPollDuration, ThreadPool.Names.GENERIC)
        } catch (e: Exception) {
            log.error("Error scheduling retry on failed autofollow indices ${e.stackTraceToString()}")
            null
        }
    }

    override suspend fun cleanup() {
        retryScheduler?.cancel()
    }

    private suspend fun autoFollow() {
        log.info("Checking $leaderAlias under pattern name $patternName for new indices to auto follow")
        val entry = replicationMetadata.leaderContext.resource

        // Fetch remote indices matching auto follow pattern
        var remoteIndices = Iterable { emptyArray<String>().iterator() }
        val remoteClient = client.getRemoteClusterClient(leaderAlias)
        try {
            val indexReq = GetIndexRequest().features(*emptyArray())
                    .indices(entry)
                    .indicesOptions(IndicesOptions.lenientExpandOpen())
            val response = remoteClient.suspending(remoteClient.admin().indices()::getIndex, true)(indexReq)
            remoteIndices = response.indices.asIterable()

        } catch (e: Exception) {
            // Ideally, Calls to the remote cluster shouldn't fail and autofollow task should be able to pick-up the newly created indices
            // matching the pattern. Should be safe to retry after configured delay.
            stat.failedLeaderCall++
            if(stat.failedLeaderCall > 0 && stat.failedLeaderCall.rem(10) == 0L) {
                log.error("Fetching remote indices failed with error - ${e.stackTraceToString()}")
            }
        }

        var currentIndices = clusterService.state().metadata().concreteAllIndices.asIterable() // All indices - open and closed on the cluster
        if(remoteIndices.intersect(currentIndices).isNotEmpty()) {
            // Log this once when we see any update on indices on the follower cluster to prevent log flood
            if(currentIndices.toSet() != trackingIndicesOnTheCluster) {
                log.info("Cannot initiate replication for the following indices from leader ($leaderAlias) as indices with " +
                        "same name already exists on the cluster ${remoteIndices.intersect(currentIndices)}")
                trackingIndicesOnTheCluster = currentIndices.toSet()
            }
        }
        remoteIndices = remoteIndices.minus(currentIndices).minus(failedIndices)

        stat.failCounterForRun = 0
        for (newRemoteIndex in remoteIndices) {
            //ToDo skip datastreams
            if (!newRemoteIndex.startsWith(".")){
                startReplication(newRemoteIndex)
            }
        }

        val clusterStateRequest = remoteClient.admin().cluster().prepareState()
                .clear()
                .setMetadata(true)
                .request()
        val remoteState = remoteClient.suspending(remoteClient.admin().cluster()::state,
                injectSecurityContext = true, defaultContext = true)(clusterStateRequest).state

        val leaderDataStreams :HashMap<String, DataStream> = HashMap()
        val followerDataStreams: HashSet<String> = HashSet()
        for ((dataStreamName, ds) in remoteState.metadata().dataStreams()) {
            if (Regex.simpleMatch(entry, dataStreamName)) {
                leaderDataStreams[dataStreamName] = ds;
            }
        }
        
        for  (dataStreamName in clusterService.state().metadata().dataStreams().keys) {
            followerDataStreams.add(dataStreamName)
        }

        try{
            for (ds in leaderDataStreams) {
                if (!followerDataStreams.contains(ds.key)) {
                    // find the template used on leader
                    // can the template change ?
                    log.warn("gbbafna will start replication for ${ds}")

                    val restoreRequest = client.admin().cluster()
                            .prepareRestoreSnapshot(RemoteClusterRepository.repoForCluster(leaderAlias), REMOTE_SNAPSHOT_NAME)
                            .setIndices(ds.key)
                            .request()

                    val response = client.suspending(client.admin().cluster()::restoreSnapshot, defaultContext = true)(restoreRequest)
                    if (response.restoreInfo != null) {
                        if (response.restoreInfo.failedShards() != 0) {
                            throw ReplicationException("Restore failed: $response")
                        }
                        log.error("restpre failed")
                    }
                    cso.waitForNextChange("remote restore start") { inProgressRestore(it) != null }

                } else {
                    // Spawn a management job if it doesn't exist
                    // For now do it here .
                }
            }

        } catch (e: Exception){
            log.info("Failed to restore  with error $e")

        }

        stat.failCount = stat.failCounterForRun
    }

    private fun inProgressRestore(cs: ClusterState): RestoreInProgress.Entry? {
        return cs.custom<RestoreInProgress>(RestoreInProgress.TYPE).singleOrNull { entry ->
            entry.snapshot().repository == RemoteClusterRepository.repoForCluster(leaderAlias) &&
                    entry.indices().singleOrNull { idx -> idx == followerIndexName } != null
        }
    }


    private suspend fun startReplication(leaderIndex: String) {
        if (clusterService.state().metadata().hasIndex(leaderIndex)) {
            log.info("""Cannot replicate $leaderAlias:$leaderIndex as an index with the same name already 
                        |exists.""".trimMargin())
            return
        }

        var successStart = false

        try {
            log.info("Auto follow starting replication from ${leaderAlias}:$leaderIndex -> $leaderIndex")
            val request = ReplicateIndexRequest(leaderIndex, leaderAlias, leaderIndex )
            request.isAutoFollowRequest = true
            val followerRole = replicationMetadata.followerContext?.user?.roles?.get(0)
            val leaderRole = replicationMetadata.leaderContext?.user?.roles?.get(0)
            if(followerRole != null && leaderRole != null) {
                request.useRoles = HashMap<String, String>()
                request.useRoles!![ReplicateIndexRequest.FOLLOWER_CLUSTER_ROLE] = followerRole
                request.useRoles!![ReplicateIndexRequest.LEADER_CLUSTER_ROLE] = leaderRole
            }
            request.settings = replicationMetadata.settings
            val response = client.suspendExecute(replicationMetadata, ReplicateIndexAction.INSTANCE, request)
            if (!response.isAcknowledged) {
                throw ReplicationException("Failed to auto follow leader index $leaderIndex")
            }
            successStart = true
        } catch (e: OpenSearchSecurityException) {
            // For permission related failures, Adding as part of failed indices as autofollow role doesn't have required permissions.
            log.trace("Cannot start replication on $leaderIndex due to missing permissions $e")
            failedIndices.add(leaderIndex)

        } catch (e: Exception) {
            // Any failure other than security exception can be safely retried and not adding to the failed indices
            log.warn("Failed to start replication for $leaderAlias:$leaderIndex -> $leaderIndex.", e)
        } finally {
            if (successStart) {
                stat.successCount++
                stat.failedIndices.remove(leaderIndex)
            } else {
                stat.failCounterForRun++
                stat.failedIndices.add(leaderIndex)
            }
        }
    }


    override fun toString(): String {
        return "AutoFollowTask(from=${leaderAlias} with pattern=${params.patternName})"
    }

    override fun replicationTaskResponse(): CrossClusterReplicationTaskResponse {
        return CrossClusterReplicationTaskResponse(ReplicationState.COMPLETED.name)
    }

    override fun getStatus(): AutoFollowStat {
        return stat
    }

    override suspend fun setReplicationMetadata() {
        this.replicationMetadata = replicationMetadataManager.getAutofollowMetadata(followerIndexName, leaderAlias, fetch_from_primary = true)
    }
}

class AutoFollowStat: Task.Status {
    companion object {
        val NAME = "autofollow_stat"
    }

    val name :String
    val pattern :String
    var failCount: Long=0
    var failedIndices :MutableSet<String> = mutableSetOf()
    var failCounterForRun :Long=0
    var successCount: Long=0
    var failedLeaderCall :Long=0


    constructor(name: String, pattern: String) {
        this.name = name
        this.pattern = pattern
    }

    constructor(inp: StreamInput) {
        name = inp.readString()
        pattern = inp.readString()
        failCount = inp.readLong()
        failedIndices = inp.readSet(StreamInput::readString)
        successCount = inp.readLong()
        failedLeaderCall = inp.readLong()
    }

    override fun writeTo(out: StreamOutput) {
       out.writeString(name)
       out.writeString(pattern)
       out.writeLong(failCount)
       out.writeCollection(failedIndices, StreamOutput::writeString)
       out.writeLong(successCount)
       out.writeLong(failedLeaderCall)
    }

    override fun getWriteableName(): String {
        return NAME
    }

    override fun toXContent(builder: XContentBuilder, p1: ToXContent.Params?): XContentBuilder {
        builder.startObject()
        builder.field("name", name)
        builder.field("pattern", pattern)
        builder.field("num_success_start_replication", successCount)
        builder.field("num_failed_start_replication", failCount)
        builder.field("num_failed_leader_calls", failedLeaderCall)
        builder.field("failed_indices", failedIndices)
        return builder.endObject()
    }
}
