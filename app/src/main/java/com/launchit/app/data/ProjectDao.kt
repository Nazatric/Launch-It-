package com.launchit.app.data

import androidx.room.*
import com.launchit.app.model.DependencyState
import com.launchit.app.model.Project
import com.launchit.app.model.RuntimeState
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpened DESC, createdAt DESC")
    fun getAllProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects ORDER BY lastOpened DESC, createdAt DESC")
    suspend fun getAllProjectsList(): List<Project>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: String): Project?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getProjectByIdFlow(id: String): Flow<Project?>

    @Query("SELECT * FROM projects WHERE name LIKE '%' || :query || '%' OR displayName LIKE '%' || :query || '%'")
    fun searchProjects(query: String): Flow<List<Project>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: Project): Long

    @Update
    suspend fun updateProject(project: Project)

    @Delete
    suspend fun deleteProject(project: Project)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    @Query("UPDATE projects SET lastOpened = :timestamp WHERE id = :id")
    suspend fun updateLastOpened(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET runtimeState = :state, errorMessage = :error WHERE id = :id")
    suspend fun updateRuntimeState(id: String, state: RuntimeState, error: String? = null)

    @Query("UPDATE projects SET dependencyState = :state WHERE id = :id")
    suspend fun updateDependencyState(id: String, state: DependencyState)

    @Query("SELECT SUM(storageUsedBytes) FROM projects")
    suspend fun getTotalStorageUsed(): Long?

    @Query("UPDATE projects SET port = :port WHERE id = :id")
    suspend fun updatePort(id: String, port: Int)

    @Query("SELECT * FROM projects WHERE runtimeState = 'RUNNING' OR runtimeState = 'STARTING'")
    suspend fun getRunningProjects(): List<Project>
}
