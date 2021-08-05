package com.amazon.elasticsearch.replication.task.index


import io.mockk.every
import io.mockk.mockk
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.test.ESTestCase
import org.junit.Test

class IndexReplicationTaskTests {

    @Test
    fun testBasicExecute() {
        // hello

        try {
            val processBuilder = ProcessBuilder(
                "/Library/Java/JavaVirtualMachines/amazon-corretto-15.jdk/Contents/Home/bin/java",
                "",
                "",
                "name"
            ).start().waitFor();
        } catch (e: Exception) {
            print(e)
        }
        //val replicationMetadataManager = Mockito.mock(ReplicationMetadataManager::class.java)
        //val replicationMetadataManager2 = mockk<ReplicationMetadataManager>()
        // INIT -> setupAndStartRestore -> waitForRestore -> startShardFollowTasks -> FOLLOWING state
//        val task:IndexReplicationTask = createIndexReplicationTask()
//        runBlockingTest {
//            task.execute(this, InitialState)
//        }
    }


//    private fun createIndexReplicationTask() : IndexReplicationTask {
//        val clusterService = Mockito.mock(ClusterService::class.java)
//        val settingsModule = Mockito.mock(SettingsModule::class.java)
//        val replicationMetadataManager = mockk<ReplicationMetadataManager>()
////        val a := IndexReplicationTask(1, "type", "action", "Description" , TaskId.EMPTY_TASK_ID,
////                executor, clusterService , client, requireNotNull(taskInProgress.params),
////                persistentTasksService, replicationMetadataManager, replicationSettings, settingsModule)
//
//        return  object : IndexReplicationTask() {
//            override suspend fun setupAndStartRestore(): IndexReplicationState {
//                return FollowingState(emptyMap())
//            }
//
//             override suspend fun execute(
//                    scope: CoroutineScope,
//                    initialState: PersistentTaskState?
//            ): Unit {
//                 println("here here")
//                super.execute(scope, initialState)
//            }
//
//            fun test() {
//                println("here here")
//            }
//        }
//    }

    @Test
    fun test_something() {
        val car = mockk<Car>()
        //every { car.drive() } throws RuntimeException("error happened")
        try {
            car.drive()
        } catch (e: Exception) {
            println("catight $e")
        }

//        val replicationMetadataManager = mockk<ReplicationMetadataManager>()
//        runBlockingTest {
//            replicationMetadataManager.updateSettings("", Settings.EMPTY)
//        }
    }

    @Test
    fun calculateAddsValues() {
        val doc1 = Dependency1(5)
        val doc2 = Dependency2("6")

        val sut = SystemUnderTest(doc1, doc2)

        //assertEquals(11, sut.calculate())

        //val doc3 = mockk<Dependency1>()
    }

    @Test
    fun calculateAddsValues2() {
        val doc1 = mockk<Dependency1>()
        val doc2 = mockk<Dependency2>()

        every { doc1.value1 } returns 5
        every { doc2.value2 } returns "6"

        val sut = SystemUnderTest(doc1, doc2)

        //assertEquals(11, sut.calculate())
    }

    open class Vehicle {
        open fun drive(): Unit {
             println("driving")
        }
    }

    class Car:Vehicle() {
        override fun drive(): Unit {
            println("driving a car")
        }
    }

}
