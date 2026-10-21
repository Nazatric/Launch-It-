package com.launchit.app.data

import com.launchit.app.model.DependencyState
import com.launchit.app.model.Project
import com.launchit.app.model.RuntimeState
import kotlinx.coroutines.flow.Flow

/**
 * Repository for project data access.
 */
class ProjectRepository(private val projectDao: ProjectDao) {

    fun getAllProjects(): Flow<List<Project>> = projectDao.getAllProjects()

    suspend fun getAllProjectsList(): List<Project> = projectDao.getAllProjectsList()

    suspend fun getProjectById(id: String): Project? = projectDao.getProjectById(id)

    fun getProjectByIdFlow(id: String): Flow<Project?> = projectDao.getProjectByIdFlow(id)

    fun searchProjects(query: String): Flow<List<Project>> = projectDao.searchProjects(query)

    suspend fun insertProject(project: Project): String {
        projectDao.insertProject(project)
        return project.id
    }

    suspend fun updateProject(project: Project) = projectDao.updateProject(project)

    suspend fun deleteProject(project: Project) = projectDao.deleteProject(project)

    suspend fun deleteProjectById(id: String) = projectDao.deleteProjectById(id)

    suspend fun updateLastOpened(id: String) = projectDao.updateLastOpened(id)

    suspend fun updateRuntimeState(id: String, state: RuntimeState, error: String? = null) =
        projectDao.updateRuntimeState(id, state, error)

    suspend fun updateDependencyState(id: String, state: DependencyState) =
        projectDao.updateDependencyState(id, state)

    suspend fun getTotalStorageUsed(): Long = projectDao.getTotalStorageUsed() ?: 0L

    suspend fun updatePort(id: String, port: Int) = projectDao.updatePort(id, port)

    suspend fun getRunningProjects(): List<Project> = projectDao.getRunningProjects()
}
