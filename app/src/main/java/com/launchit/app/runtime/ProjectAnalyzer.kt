package com.launchit.app.runtime

import android.util.Log
import com.launchit.app.model.ProjectType
import org.json.JSONObject
import java.io.File

/**
 * Analyzes an imported project to determine its type, entry point, dependencies, and execution requirements.
 */
class ProjectAnalyzer {

    companion object {
        private const val TAG = "LaunchItAnalyzer"
    }

    data class AnalysisResult(
        val projectType: ProjectType,
        val entryPoint: String,
        val description: String,
        val requiresNode: Boolean,
        val packageManager: String?,
        val buildScript: String?,
        val startScript: String?,
        val iconPath: String?,
        val errorMessage: String? = null
    )

    /**
     * Analyze a project directory and return analysis results.
     */
    fun analyze(projectDir: File, projectName: String): AnalysisResult {
        Log.i(TAG, "Analyzing project: $projectName at ${projectDir.absolutePath}")

        if (!projectDir.exists()) {
            Log.w(TAG, "Project directory does not exist: ${projectDir.absolutePath}")
            return AnalysisResult(
                projectType = ProjectType.UNKNOWN,
                entryPoint = "",
                description = "Directory not found",
                requiresNode = false,
                packageManager = null,
                buildScript = null,
                startScript = null,
                iconPath = null,
                errorMessage = "Project directory not found"
            )
        }

        if (!projectDir.isDirectory) {
            Log.w(TAG, "Path is not a directory: ${projectDir.absolutePath}")
            return AnalysisResult(
                projectType = ProjectType.UNKNOWN,
                entryPoint = "",
                description = "Not a directory",
                requiresNode = false,
                packageManager = null,
                buildScript = null,
                startScript = null,
                iconPath = null,
                errorMessage = "Path is not a directory"
            )
        }

        // List all files for debugging
        val files = projectDir.listFiles()
        Log.d(TAG, "Project contains ${files?.size ?: 0} entries")
        files?.take(10)?.forEach { Log.d(TAG, "  ${if (it.isDirectory) "[DIR] " else ""}${it.name}") }

        // Look for package.json to determine project type
        val packageJsonFile = File(projectDir, "package.json")
        val hasPackageJson = packageJsonFile.exists()

        // Look for index.html
        val indexHtml = findEntryPoint(projectDir)

        Log.d(TAG, "package.json: $hasPackageJson, index.html: $indexHtml")

        // Determine project type
        return if (hasPackageJson) {
            analyzeNodeProject(projectDir, packageJsonFile, indexHtml)
        } else if (indexHtml != null) {
            analyzeStaticProject(projectDir, indexHtml)
        } else {
            Log.w(TAG, "Could not determine project type - no package.json or index.html")
            AnalysisResult(
                projectType = ProjectType.UNKNOWN,
                entryPoint = "",
                description = "Could not determine project type",
                requiresNode = false,
                packageManager = null,
                buildScript = null,
                startScript = null,
                iconPath = findIcon(projectDir),
                errorMessage = "No index.html or package.json found in project root"
            )
        }
    }

    private fun analyzeStaticProject(projectDir: File, entryPoint: String): AnalysisResult {
        Log.i(TAG, "Detected static web project with entry: $entryPoint")
        return AnalysisResult(
            projectType = ProjectType.STATIC_WEB,
            entryPoint = entryPoint,
            description = "Static web project",
            requiresNode = false,
            packageManager = null,
            buildScript = null,
            startScript = null,
            iconPath = findIcon(projectDir)
        )
    }

    private fun analyzeNodeProject(projectDir: File, packageJsonFile: File, indexHtml: String?): AnalysisResult {
        return try {
            val jsonContent = packageJsonFile.readText()
            if (jsonContent.isBlank()) {
                return AnalysisResult(
                    projectType = ProjectType.UNKNOWN,
                    entryPoint = indexHtml ?: "index.html",
                    description = "Empty package.json",
                    requiresNode = false,
                    packageManager = null,
                    buildScript = null,
                    startScript = null,
                    iconPath = findIcon(projectDir),
                    errorMessage = "package.json is empty"
                )
            }

            val json = JSONObject(jsonContent)
            val scripts = if (json.has("scripts")) json.getJSONObject("scripts") else JSONObject()
            val dependencies = if (json.has("dependencies")) json.getJSONObject("dependencies") else JSONObject()
            val devDependencies = if (json.has("devDependencies")) json.getJSONObject("devDependencies") else JSONObject()
            val description = if (json.has("description")) json.getString("description") else ""

            Log.d(TAG, "Package: description=$description")
            Log.d(TAG, "Dependencies count: ${dependencies.length()}")
            Log.d(TAG, "DevDependencies count: ${devDependencies.length()}")

            val hasBuildScript = scripts.has("build")
            val hasStartScript = scripts.has("start") || scripts.has("dev") || scripts.has("serve")
            val hasServerDep = hasServerDependencies(dependencies, devDependencies)

            // Detect package manager from lockfiles
            val packageManager = detectPackageManager(projectDir)

            Log.d(TAG, "hasBuild=$hasBuildScript, hasStart=$hasStartScript, hasServer=$hasServerDep, pm=$packageManager")

            // Determine project type
            val projectType = when {
                hasServerDep && hasBuildScript -> ProjectType.FULL_STACK
                hasServerDep -> ProjectType.NODE_SERVER
                hasBuildScript -> ProjectType.FRONTEND_APP
                else -> ProjectType.FRONTEND_APP
            }

            // Determine entry point
            val entryPoint = when (projectType) {
                ProjectType.NODE_SERVER, ProjectType.FULL_STACK -> {
                    val mainField = if (json.has("main")) json.getString("main") else null
                    mainField ?: "index.js"
                }
                else -> indexHtml ?: "index.html"
            }

            val buildScript = if (hasBuildScript) "build" else null
            val startScript = when {
                scripts.has("start") -> "start"
                scripts.has("dev") -> "dev"
                scripts.has("serve") -> "serve"
                else -> null
            }

            Log.i(TAG, "Detected ${projectType.name} project: entry=$entryPoint, build=$buildScript, start=$startScript")

            AnalysisResult(
                projectType = projectType,
                entryPoint = entryPoint,
                description = description,
                requiresNode = true,
                packageManager = packageManager,
                buildScript = buildScript,
                startScript = startScript,
                iconPath = findIcon(projectDir)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing package.json", e)
            AnalysisResult(
                projectType = ProjectType.UNKNOWN,
                entryPoint = indexHtml ?: "index.html",
                description = "Error parsing package.json",
                requiresNode = false,
                packageManager = null,
                buildScript = null,
                startScript = null,
                iconPath = findIcon(projectDir),
                errorMessage = "Failed to parse package.json: ${e.message}"
            )
        }
    }

    private fun findEntryPoint(dir: File): String? {
        val candidates = listOf("index.html", "index.htm", "main.html", "app.html")
        for (candidate in candidates) {
            if (File(dir, candidate).exists()) return candidate
        }
        // Search one level deep for HTML files
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { subDir ->
            for (candidate in candidates) {
                if (File(subDir, candidate).exists()) return "${subDir.name}/$candidate"
            }
        }
        return null
    }

    private fun findIcon(dir: File): String? {
        val iconCandidates = listOf("icon.png", "icon.jpg", "favicon.ico", "favicon.png", "logo.png", "logo.svg")
        for (candidate in iconCandidates) {
            val file = File(dir, candidate)
            if (file.exists()) return candidate
        }
        return null
    }

    private fun detectPackageManager(dir: File): String {
        return when {
            File(dir, "pnpm-lock.yaml").exists() -> "pnpm"
            File(dir, "yarn.lock").exists() -> "yarn"
            File(dir, "bun.lockb").exists() -> "bun"
            File(dir, "package-lock.json").exists() -> "npm"
            else -> "npm"
        }
    }

    private fun hasServerDependencies(dependencies: JSONObject, devDependencies: JSONObject): Boolean {
        val serverPackages = listOf(
            "express", "fastify", "koa", "hapi", "@hapi/hapi",
            "next", "nuxt", "svelte-kit", "@sveltejs/kit",
            "http-server", "serve", "live-server",
            "drizzle-orm", "pg", "postgres", "mysql2"
        )
        for (pkg in serverPackages) {
            if (dependencies.has(pkg) || devDependencies.has(pkg)) {
                Log.d(TAG, "Found server dependency: $pkg")
                return true
            }
        }
        return false
    }
}
