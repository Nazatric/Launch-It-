package com.launchit.app.runtime

import android.content.Context
import android.util.Log
import com.launchit.app.data.ProjectRepository
import com.launchit.app.model.DependencyState
import com.launchit.app.model.Project
import com.launchit.app.model.ProjectType
import com.launchit.app.model.RuntimeState
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates project runtime lifecycle with proper synchronization.
 */
class RuntimeOrchestrator(
    private val context: Context,
    private val repository: ProjectRepository
) {
    private val TAG = "LaunchItRuntime"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track active servers and their state
    private val activeServers = ConcurrentHashMap<String, LocalHttpServer>()
    private val startingProjects = ConcurrentHashMap<String, Boolean>()
    private val projectUrls = ConcurrentHashMap<String, String>()
    private val allocatedPorts = ConcurrentHashMap<String, Int>()

    /**
     * Start a project and return the URL to load.
     * Thread-safe: prevents duplicate starts and waits for server readiness.
     */
    suspend fun startProject(projectId: String): Result<String> = withContext(Dispatchers.IO) {
        Log.i(TAG, "startProject called for $projectId")

        // If already running, return cached URL
        val existingUrl = projectUrls[projectId]
        if (existingUrl != null && isProjectRunning(projectId)) {
            Log.i(TAG, "Project $projectId already running at $existingUrl")
            return@withContext Result.success(existingUrl)
        }

        // Prevent concurrent starts of same project
        if (startingProjects.containsKey(projectId)) {
            Log.i(TAG, "Project $projectId is being started by another coroutine, waiting...")
            var waited = 0L
            while (startingProjects.containsKey(projectId) && waited < 5000) {
                delay(50)
                waited += 50
                projectUrls[projectId]?.let { url ->
                    if (isProjectRunning(projectId)) {
                        return@withContext Result.success(url)
                    }
                }
            }
            if (startingProjects.containsKey(projectId)) {
                return@withContext Result.failure(IllegalStateException("Project startup timeout"))
            }
            projectUrls[projectId]?.let { return@withContext Result.success(it) }
        }

        // Mark as starting
        startingProjects[projectId] = true

        try {
            val project = repository.getProjectById(projectId)
                ?: return@withContext Result.failure(IllegalStateException("Project not found: $projectId"))

            Log.i(TAG, "Starting project: ${project.name} (type=${project.projectType}, entry=${project.entryPoint})")

            // Validate source directory
            val sourceDir = File(project.sourcePath)
            if (!sourceDir.exists()) {
                val msg = "Project source directory not found: ${project.sourcePath}"
                Log.e(TAG, msg)
                repository.updateRuntimeState(projectId, RuntimeState.FAILED, msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            repository.updateRuntimeState(projectId, RuntimeState.STARTING)

            // Determine what to serve
            val serveDir = resolveServeDirectory(project)
            if (serveDir == null || !serveDir.exists()) {
                val msg = if (project.projectType == com.launchit.app.model.ProjectType.FRONTEND_APP) {
                    "This project needs to be built first. It appears to be a React/Vue/Angular app that requires running 'npm run build' before it can be served. Only pre-built static apps can be run in Launch It."
                } else {
                    "Serve directory not found. The project may be incomplete or corrupted."
                }
                Log.e(TAG, msg)
                repository.updateRuntimeState(projectId, RuntimeState.FAILED, msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            Log.i(TAG, "Serving from: ${serveDir.absolutePath}")

            // Stop existing server if any
            stopServerInternal(projectId)

            // Allocate a port
            val port = allocatePort()
            Log.i(TAG, "Allocated port: $port")

            // Start HTTP server
            val server = LocalHttpServer(port, serveDir)
            try {
                server.start()
            } catch (e: Exception) {
                val msg = "Failed to start HTTP server on port $port: ${e.message}"
                Log.e(TAG, msg, e)
                repository.updateRuntimeState(projectId, RuntimeState.FAILED, msg)
                return@withContext Result.failure(e)
            }

            // Wait for server to be ready
            val ready = waitForServerReady(port, 5000L)
            if (!ready) {
                val msg = "HTTP server started but port $port is not accepting connections"
                Log.e(TAG, msg)
                try { server.stop() } catch (_: Exception) {}
                repository.updateRuntimeState(projectId, RuntimeState.FAILED, msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            activeServers[projectId] = server
            allocatedPorts[projectId] = port
            repository.updatePort(projectId, port)

            val entryPoint = project.entryPoint.ifBlank { "index.html" }
            val cleanEntryPoint = entryPoint.trimStart('/')
            val url = "http://127.0.0.1:$port/$cleanEntryPoint"
            projectUrls[projectId] = url

            Log.i(TAG, "Project started successfully: $url")
            repository.updateRuntimeState(projectId, RuntimeState.RUNNING)
            Result.success(url)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start project $projectId", e)
            val msg = e.message ?: e.javaClass.simpleName
            repository.updateRuntimeState(projectId, RuntimeState.FAILED, msg)
            Result.failure(e)
        } finally {
            startingProjects.remove(projectId)
        }
    }

    /**
     * Resolve the directory to serve for a project.
     */
    private fun resolveServeDirectory(project: Project): File? {
        val sourceDir = File(project.sourcePath)
        return when (project.projectType) {
            ProjectType.STATIC_WEB -> sourceDir
            ProjectType.FRONTEND_APP -> {
                // Look for pre-built output directories
                val candidates = listOf("dist", "build", "out", "public")
                for (candidate in candidates) {
                    val dir = File(sourceDir, candidate)
                    if (dir.exists() && dir.isDirectory) {
                        if (dir.listFiles()?.any { it.name.endsWith(".html") } == true) {
                            return dir
                        }
                    }
                }
                // Check if source directory itself has a usable index.html
                // (e.g., the user built it themselves or it's a simple HTML app with package.json)
                val sourceIndex = File(sourceDir, "index.html")
                if (sourceIndex.exists() && sourceIndex.isFile) {
                    Log.w(TAG, "No build output for ${project.name}, but index.html exists in source — serving source")
                    return sourceDir
                }
                Log.e(TAG, "No build output found for FRONTEND_APP ${project.name}. Project needs to be built first (npm run build).")
                null
            }
            ProjectType.NODE_SERVER, ProjectType.FULL_STACK -> {
                val candidates = listOf("dist", "build", "out", "public", ".next")
                for (candidate in candidates) {
                    val dir = File(sourceDir, candidate)
                    if (dir.exists() && dir.isDirectory) {
                        return dir
                    }
                }
                // Check if source directory has an index.html for simple cases
                if (File(sourceDir, "index.html").exists()) {
                    return sourceDir
                }
                Log.e(TAG, "No build output found for ${project.projectType.name} project: ${project.name}")
                null
            }
            ProjectType.UNKNOWN -> sourceDir
        }
    }

    /**
     * Wait for server to accept connections.
     */
    private suspend fun waitForServerReady(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                socket.close()
                return true
            } catch (_: Exception) {
                delay(50)
            }
        }
        return false
    }

    /**
     * Stop a project's runtime.
     */
    suspend fun stopProject(projectId: String) {
        Log.i(TAG, "Stopping project: $projectId")
        try {
            repository.updateRuntimeState(projectId, RuntimeState.STOPPING)
        } catch (_: Exception) {}
        
        stopServerInternal(projectId)
        projectUrls.remove(projectId)
        allocatedPorts.remove(projectId)
        startingProjects.remove(projectId)
        
        try {
            repository.updateRuntimeState(projectId, RuntimeState.STOPPED)
        } catch (_: Exception) {}
    }

    /**
     * Stop all running projects.
     */
    suspend fun stopAll() {
        Log.i(TAG, "Stopping all projects")
        val runningIds = activeServers.keys.toList()
        runningIds.forEach { projectId ->
            stopServerInternal(projectId)
            projectUrls.remove(projectId)
            allocatedPorts.remove(projectId)
            startingProjects.remove(projectId)
            try {
                repository.updateRuntimeState(projectId, RuntimeState.STOPPED)
            } catch (_: Exception) {}
        }
    }

    private fun stopServerInternal(projectId: String) {
        activeServers.remove(projectId)?.let { server ->
            try {
                server.stop()
                Log.i(TAG, "Server stopped for project: $projectId")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping server for $projectId", e)
            }
        }
    }

    private fun allocatePort(): Int {
        val usedPorts = allocatedPorts.values.toSet()
        for (port in 8900..9900) {
            if (port !in usedPorts && isPortAvailable(port)) {
                return port
            }
        }
        throw IllegalStateException("No available ports in range 8900-9900")
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    fun getProjectUrl(projectId: String): String? = projectUrls[projectId]

    fun isProjectRunning(projectId: String): Boolean = activeServers.containsKey(projectId)

    fun cleanup() {
        Log.i(TAG, "Cleaning up runtime orchestrator")
        scope.cancel()
        activeServers.values.forEach { server ->
            try { server.stop() } catch (_: Exception) {}
        }
        activeServers.clear()
        projectUrls.clear()
        allocatedPorts.clear()
        startingProjects.clear()
    }
}
