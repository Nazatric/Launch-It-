package com.launchit.app.ui.library

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.launchit.app.LaunchItApplication
import com.launchit.app.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class LibraryUiState(
    val projects: List<Project> = emptyList(),
    val searchQuery: String = "",
    val isImporting: Boolean = false,
    val importProgress: String = "",
    val importError: String? = null
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LaunchItApplication
    private val repository = app.projectRepository
    private val orchestrator = app.runtimeOrchestrator
    private val importer = app.projectImporter

    private val _searchQuery = MutableStateFlow("")
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)

    val uiState: StateFlow<LibraryUiState> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) repository.getAllProjects()
            else repository.searchProjects(query)
        }
        .map { projects ->
            val importState = _importState.value
            LibraryUiState(
                projects = projects,
                searchQuery = _searchQuery.value,
                isImporting = importState is ImportState.Importing,
                importProgress = if (importState is ImportState.Importing) importState.message else "",
                importError = if (importState is ImportState.Error) importState.message else null
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun importProject(uri: Uri, displayName: String? = null) {
        viewModelScope.launch {
            _importState.value = ImportState.Importing("Importing project...")
            try {
                val result = importer.importFromUri(uri, displayName)
                if (result.success) {
                    _importState.value = ImportState.Success(result.project.id)
                    kotlinx.coroutines.delay(2000)
                    _importState.value = ImportState.Idle
                } else {
                    _importState.value = ImportState.Error(result.errorMessage ?: "Unknown error")
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error("Import failed: ${e.message}")
            }
        }
    }

    fun importFolder(uri: Uri, displayName: String? = null) {
        viewModelScope.launch {
            _importState.value = ImportState.Importing("Importing folder...")
            try {
                val result = importer.importFromFolderUri(uri, displayName)
                if (result.success) {
                    _importState.value = ImportState.Success(result.project.id)
                    kotlinx.coroutines.delay(2000)
                    _importState.value = ImportState.Idle
                } else {
                    _importState.value = ImportState.Error(result.errorMessage ?: "Unknown error")
                }
            } catch (e: Exception) {
                _importState.value = ImportState.Error("Folder import failed: ${e.message}")
            }
        }
    }

    fun launchProject(project: Project) {
        viewModelScope.launch {
            try {
                val result = orchestrator.startProject(project.id)
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "Failed to start project"
                    Log.e("LaunchItRuntime", "Failed to launch project ${project.name}: $msg")
                }
            } catch (e: Exception) {
                Log.e("LaunchItRuntime", "Exception launching project ${project.name}", e)
            }
        }
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            orchestrator.stopProject(project.id)
            try {
                File(project.sourcePath).deleteRecursively()
                project.buildOutputPath?.let { File(it).deleteRecursively() }
            } catch (_: Exception) {}
            repository.deleteProject(project)
        }
    }

    fun updateProjectName(projectId: String, newName: String) {
        viewModelScope.launch {
            val project = repository.getProjectById(projectId) ?: return@launch
            repository.updateProject(project.copy(displayName = newName))
        }
    }

    fun clearImportError() {
        _importState.value = ImportState.Idle
    }

    fun isProjectRunning(projectId: String): Boolean {
        return orchestrator.isProjectRunning(projectId)
    }

    sealed class ImportState {
        object Idle : ImportState()
        data class Importing(val message: String) : ImportState()
        data class Success(val projectId: String) : ImportState()
        data class Error(val message: String) : ImportState()
    }
}
