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
 * 
 * Execution state machine:
 * IMPORTED → STARTING → VERIFYING → RUNNING
 *                       → FAILED (on error)
 * RUNNING → STOPPING → STOPPED
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
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                val msg = "Project source directory not found: ${project.sourcePath}"
                Log.e(TAG, msg)
                repository.updateRuntimeState(projectId, RuntimeState.FAILED, msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            repository.updateRuntimeState(projectId, RuntimeState.STARTING)

            // Determine what to serve
            val serveDir = resolveServeDirectory(project)
            if (serveDir == null || !serveDir.exists()) {
                val msg = buildServeDirectoryErrorMessage(project)
                Log.e(TAG, msg)
                repository.updateRuntimeState(projectId, RuntimeState.FAILED, msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            Log.i(TAG, "Serving from: ${serveDir.absolutePath}")

            // Verify there's an actual file to serve
            val entryPoint = project.entryPoint.ifBlank { "index.html" }
            val cleanEntryPoint = entryPoint.trimStart('/')
            val targetFile = File(serveDir, cleanEntryPoint)
            if (!targetFile.exists()) {
                // Try to find any HTML file in the serve directory
                val anyHtml = serveDir.walkTopDown().maxDepth(1)
                    .firstOrNull { it.isFile && it.name.endsWith(".html") }
                if (anyHtml == null) {
                    val msg = "No HTML files found in project. Entry point '$cleanEntryPoint' not found."
                    Log.e(TAG, msg)
                    repository.updateRuntimeState(projectId, RuntimeState.FAILED, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                Log.w(TAG, "Entry point '$cleanEntryPoint' not found, but found ${anyHtml.name}")
            }

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

            // Verify the server actually serves the entry point
            val url = "http://127.0.0.1:$port/$cleanEntryPoint"
            val verified = verifyServerResponse(port, cleanEntryPoint)
            if (!verified) {
                Log.w(TAG, "Server started but entry point verification failed. Proceeding anyway.")
            }

            activeServers[projectId] = server
            allocatedPorts[projectId] = port
            repository.updatePort(projectId, port)

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
     * Build a helpful error message when the serve directory can't be found.
     */
    private fun buildServeDirectoryErrorMessage(project: Project): String {
        return when (project.projectType) {
            ProjectType.FRONTEND_APP -> {
                "This project appears to be a frontend app (React, Vue, Angular, etc.) that hasn't been built yet.\n\n" +
                "Launch It can run pre-built web apps but cannot compile React/Vue/TypeScript source.\n\n" +
                "To fix: Run 'npm run build' on a computer, then import the 'dist' or 'build' folder."
            }
            ProjectType.NODE_SERVER -> {
                "Node.js server projects require a Node.js runtime which is not available on Android.\n\n" +
                "Launch It can only serve static web content."
            }
            ProjectType.FULL_STACK -> {
                "Full-stack projects require a server runtime which is not available on Android.\n\n" +
                "Try importing just the static frontend output (dist/build folder)."
            }
            ProjectType.STATIC_WEB -> {
                "The project directory is empty or corrupted. Try re-importing the project."
            }
            ProjectType.UNKNOWN -> {
                "Could not determine how to run this project.\n\n" +
                "Launch It supports: static HTML/CSS/JS, pre-built React/Vue/Vite apps, " +
                "and simple web projects."
            }
        }
    }

    /**
     * Resolve the directory to serve for a project.
     * Smart detection based on project type and actual files on disk.
     */
    private fun resolveServeDirectory(project: Project): File? {
        val sourceDir = File(project.sourcePath)
        
        // For ALL project types, check for build output first
        // This handles the case where a React/Vite project was built
        val buildCandidates = listOf("dist", "build", "out")
        for (candidate in buildCandidates) {
            val dir = File(sourceDir, candidate)
            if (dir.exists() && dir.isDirectory) {
                // Check if it has actual content (HTML or JS)
                val hasContent = dir.listFiles()?.any { 
                    it.name.endsWith(".html") || it.name.endsWith(".htm") || it.name.endsWith(".js") 
                } == true
                if (hasContent) {
                    Log.i(TAG, "Found build output: ${dir.absolutePath}")
                    return dir
                }
            }
        }

        // Check public/ directory (Vite, CRA)
        val publicDir = File(sourceDir, "public")
        if (publicDir.exists() && publicDir.isDirectory) {
            val hasHtml = publicDir.listFiles()?.any { it.name.endsWith(".html") } == true
            if (hasHtml) {
                Log.i(TAG, "Found public directory with HTML: ${publicDir.absolutePath}")
                return publicDir
            }
        }

        // For STATIC_WEB and UNKNOWN, serve from source
        if (project.projectType == ProjectType.STATIC_WEB || project.projectType == ProjectType.UNKNOWN) {
            // Verify there's at least one file to serve
            if (sourceDir.listFiles()?.any { it.isFile } == true) {
                return sourceDir
            }
            return null
        }

        // For FRONTEND_APP without build output, check if source has index.html
        if (project.projectType == ProjectType.FRONTEND_APP) {
            val sourceIndex = File(sourceDir, "index.html")
            if (sourceIndex.exists() && sourceIndex.isFile) {
                // Check if this looks like a real HTML file (not just a Vite template)
                val content = sourceIndex.readText()
                val looksLikeTemplate = content.contains("/src/main.") || 
                    content.contains("/src/index.") ||
                    content.contains("type=\"module\"") && content.contains("/@vite")
                
                if (!looksLikeTemplate) {
                    Log.i(TAG, "Frontend app has index.html in source, serving source")
                    return sourceDir
                } else {
                    Log.w(TAG, "index.html appears to be a dev template referencing source files. Cannot serve without build.")
                    return null
                }
            }
            return null
        }

        // For NODE_SERVER and FULL_STACK without build output, check for index.html
        if (project.projectType == ProjectType.NODE_SERVER || project.projectType == ProjectType.FULL_STACK) {
            if (File(sourceDir, "index.html").exists()) {
                return sourceDir
            }
            return null
        }

        return sourceDir
    }

    /**
     * Verify that the server responds with a valid HTTP response for the entry point.
     */
    private suspend fun verifyServerResponse(port: Int, path: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                socket.soTimeout = 2000
                
                val request = "GET /$path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()
                
                val response = socket.getInputStream().bufferedReader().readLine()
                socket.close()
                
                val isValid = response?.startsWith("HTTP/1.1 200") == true || 
                              response?.startsWith("HTTP/1.0 200") == true
                if (!isValid) {
                    Log.w(TAG, "Server verification: got '$response' for /$path")
                }
                isValid
            } catch (e: Exception) {
                Log.w(TAG, "Server verification failed: ${e.message}")
                false
            }
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
