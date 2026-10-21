package com.launchit.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents an imported web project in the library.
 */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val displayName: String = name,
    val projectType: ProjectType = ProjectType.STATIC_WEB,
    val entryPoint: String = "index.html",
    val sourcePath: String = "",
    val buildOutputPath: String? = null,
    val iconPath: String? = null,
    val runtimeState: RuntimeState = RuntimeState.IMPORTED,
    val dependencyState: DependencyState = DependencyState.UNKNOWN,
    val lastOpened: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val description: String = "",
    val port: Int = 0,
    val errorMessage: String? = null,
    val buildScript: String? = null,
    val startScript: String? = null,
    val requiresNode: Boolean = false,
    val packageManager: String? = null,
    val storageUsedBytes: Long = 0L
)
