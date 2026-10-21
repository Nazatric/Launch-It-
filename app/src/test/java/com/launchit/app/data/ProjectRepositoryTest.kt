package com.launchit.app.data

import com.launchit.app.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProjectRepositoryTest {

    private lateinit var fakeDao: FakeProjectDao
    private lateinit var repository: ProjectRepository

    @Before
    fun setup() {
        fakeDao = FakeProjectDao()
        repository = ProjectRepository(fakeDao)
    }

    @Test
    fun `insertProject returns project id`() = runTest {
        val project = Project(name = "test", sourcePath = "/tmp/test")
        val id = repository.insertProject(project)
        assertEquals(project.id, id)
    }

    @Test
    fun `updateRuntimeState delegates to dao`() = runTest {
        val project = Project(name = "test", sourcePath = "/tmp/test")
        repository.insertProject(project)
        repository.updateRuntimeState(project.id, RuntimeState.RUNNING)
        assertEquals(RuntimeState.RUNNING, fakeDao.lastState)
    }

    @Test
    fun `updateDependencyState delegates to dao`() = runTest {
        val project = Project(name = "test", sourcePath = "/tmp/test")
        repository.insertProject(project)
        repository.updateDependencyState(project.id, DependencyState.INSTALLED)
        assertEquals(DependencyState.INSTALLED, fakeDao.lastDepState)
    }

    @Test
    fun `getTotalStorageUsed returns zero when no projects`() = runTest {
        val total = repository.getTotalStorageUsed()
        assertEquals(0L, total)
    }

    // Fake DAO for testing
    class FakeProjectDao : ProjectDao {
        private val projects = mutableListOf<Project>()
        var lastState: RuntimeState? = null
        var lastDepState: DependencyState? = null

        override fun getAllProjects(): Flow<List<Project>> = flowOf(projects.toList())
        override suspend fun getAllProjectsList(): List<Project> = projects.toList()
        override suspend fun getProjectById(id: String): Project? = projects.find { it.id == id }
        override fun getProjectByIdFlow(id: String): Flow<Project?> = flowOf(projects.find { it.id == id })
        override fun searchProjects(query: String): Flow<List<Project>> = flowOf(projects.filter { it.name.contains(query) })
        override suspend fun insertProject(project: Project): Long { projects.add(project); return 1L }
        override suspend fun updateProject(project: Project) {
            val index = projects.indexOfFirst { it.id == project.id }
            if (index >= 0) projects[index] = project
        }
        override suspend fun deleteProject(project: Project) { projects.removeAll { it.id == project.id } }
        override suspend fun deleteProjectById(id: String) { projects.removeAll { it.id == id } }
        override suspend fun updateLastOpened(id: String, timestamp: Long) {}
        override suspend fun updateRuntimeState(id: String, state: RuntimeState, error: String?) { lastState = state }
        override suspend fun updateDependencyState(id: String, state: DependencyState) { lastDepState = state }
        override suspend fun getTotalStorageUsed(): Long? = projects.sumOf { it.storageUsedBytes }
        override suspend fun updatePort(id: String, port: Int) {}
        override suspend fun getRunningProjects(): List<Project> = projects.filter { it.runtimeState == RuntimeState.RUNNING }
    }
}
