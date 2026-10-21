package com.launchit.app.runtime

import android.util.Log
import com.launchit.app.model.ProjectType
import org.json.JSONObject
import java.io.File

/**
 * Analyzes an imported project to determine its type, entry point, dependencies, and execution requirements.
 * 
 * Detection strategy:
 * 1. Scan the directory tree (bounded, skip node_modules/.git/build caches)
 * 2. Check for package.json → analyze dependencies and scripts
 * 3. Find HTML entry points by ranking candidates
 * 4. Determine if project is static, source-only, or prebuilt
 * 5. Identify framework, package manager, and runtime requirements
 */
class ProjectAnalyzer {

    companion object {
        private const val TAG = "LaunchItAnalyzer"
        private const val MAX_SCAN_DEPTH = 5
        private val SKIP_DIRS = setOf(
            "node_modules", ".git", ".svn", ".hg",
            "__pycache__", ".cache", ".next",
            "coverage", ".nyc_output", "bower_components"
        )
    }

    data class AnalysisResult(
        val projectType: ProjectType,
        val entryPoint: String,
        val serveDirectory: String,
        val description: String,
        val requiresNode: Boolean,
        val packageManager: String?,
        val buildScript: String?,
        val startScript: String?,
        val iconPath: String?,
        val hasBuildOutput: Boolean = false,
        val needsBuild: Boolean = false,
        val framework: String? = null,
        val isStaticServe: Boolean = true,
        val warnings: List<String> = emptyList(),
        val errorMessage: String? = null
    )

    /**
     * Analyze a project directory and return analysis results.
     */
    fun analyze(projectDir: File, projectName: String): AnalysisResult {
        Log.i(TAG, "Analyzing project: $projectName at ${projectDir.absolutePath}")

        if (!projectDir.exists()) {
            return errorResult("Directory not found: ${projectDir.absolutePath}")
        }

        if (!projectDir.isDirectory) {
            return errorResult("Path is not a directory: ${projectDir.absolutePath}")
        }

        // Note: ProjectImporter handles unwrapping wrapper directories before calling this.
        // We scan the directory as-is.
        val actualRoot = projectDir

        // Scan directory for key files
        val scan = scanProject(actualRoot)

        Log.d(TAG, "Scan results: packageJson=${scan.hasPackageJson}, " +
                "htmlFiles=${scan.htmlFiles.size}, " +
                "hasDist=${scan.hasBuildOutput}, " +
                "hasSrc=${scan.hasSrcDir}")

        // Determine project type and entry point
        return when {
            scan.hasPackageJson -> analyzeFrameworkProject(actualRoot, scan, projectName)
            scan.htmlFiles.isNotEmpty() -> analyzeStaticProject(actualRoot, scan)
            else -> analyzeUnknownProject(actualRoot, scan)
        }
    }

    /**
     * Analyze a project with package.json (React, Vue, Vite, Node, etc.)
     */
    private fun analyzeFrameworkProject(root: File, scan: ProjectScan, name: String): AnalysisResult {
        val packageJsonFile = File(root, "package.json")
        val json: JSONObject = try {
            val content = packageJsonFile.readText()
            if (content.isBlank()) return errorResult("package.json is empty")
            JSONObject(content)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing package.json", e)
            return errorResult("Failed to parse package.json: ${e.message}")
        }

        val scripts = json.optJSONObject("scripts") ?: JSONObject()
        val dependencies = json.optJSONObject("dependencies") ?: JSONObject()
        val devDependencies = json.optJSONObject("devDependencies") ?: JSONObject()
        val description = json.optString("description", "")
        val mainField = json.optString("main", null)

        val hasBuildScript = scripts.has("build")
        val hasStartScript = scripts.has("start") || scripts.has("dev") || scripts.has("serve")
        val hasServerDep = hasServerDependencies(dependencies, devDependencies)
        val packageManager = detectPackageManager(root)
        val framework = detectFramework(dependencies, devDependencies)

        Log.d(TAG, "Framework=$framework, hasBuild=$hasBuildScript, hasStart=$hasStartScript, " +
                "hasServer=$hasServerDep, hasBuildOutput=${scan.hasBuildOutput}")

        // Determine if the project has usable build output
        val buildOutputDir = findBuildOutput(root, scan)
        val hasBuildOutput = buildOutputDir != null

        // Determine project type
        val projectType = when {
            hasServerDep && hasBuildScript -> ProjectType.FULL_STACK
            hasServerDep -> ProjectType.NODE_SERVER
            else -> ProjectType.FRONTEND_APP
        }

        // Determine entry point
        val entryPoint = findEntryPoint(root, scan, buildOutputDir)
        val serveDir = if (buildOutputDir != null && hasBuildOutput) {
            buildOutputDir.absolutePath
        } else {
            root.absolutePath
        }

        // Determine if this project needs building
        val needsBuild = when {
            // If we found build output with HTML, it's ready
            hasBuildOutput -> false
            // If it's a frontend app with source but no build output, it needs building
            projectType == ProjectType.FRONTEND_APP && scan.hasSrcDir && hasBuildScript -> true
            // If it's a Vite project with no dist, needs building
            framework == "vite" && !hasBuildOutput -> true
            // React/Vue/Angular projects without build output
            (framework == "react" || framework == "vue" || framework == "angular") && !hasBuildOutput && scan.hasSrcDir -> true
            // If there's an index.html at root, it can be served directly
            File(root, "index.html").exists() -> false
            // If there's a public/index.html, it can be served from public/
            File(root, "public/index.html").exists() -> false
            else -> !hasBuildOutput && scan.hasSrcDir
        }

        // Check if we can serve this project even if it "needs building"
        val isStaticServe = when {
            // Has build output → definitely serveable
            hasBuildOutput -> true
            // Has root index.html → serveable as static
            File(root, "index.html").exists() -> true
            // Has public/index.html (Vite/CRA) → serveable
            File(root, "public/index.html").exists() -> true
            // Source-only framework project → NOT serveable as static
            needsBuild -> false
            // Unknown → try to serve
            else -> true
        }

        val warnings = mutableListOf<String>()
        if (needsBuild && !hasBuildOutput) {
            warnings.add("Project has source but no build output. Run 'npm run build' first.")
        }
        if (projectType == ProjectType.NODE_SERVER) {
            warnings.add("Node.js server projects require a compatible runtime (limited on Android).")
        }

        val buildScript = if (hasBuildScript) "build" else null
        val startScript = when {
            scripts.has("start") -> "start"
            scripts.has("dev") -> "dev"
            scripts.has("serve") -> "serve"
            else -> null
        }

        Log.i(TAG, "Detected ${projectType.name}: entry=$entryPoint, serve=$serveDir, " +
                "needsBuild=$needsBuild, framework=$framework")

        return AnalysisResult(
            projectType = projectType,
            entryPoint = entryPoint,
            serveDirectory = serveDir,
            description = description.ifBlank { framework?.let { "$it project" } ?: "Web project" },
            requiresNode = true,
            packageManager = packageManager,
            buildScript = buildScript,
            startScript = startScript,
            iconPath = findIcon(root),
            hasBuildOutput = hasBuildOutput,
            needsBuild = needsBuild,
            framework = framework,
            isStaticServe = isStaticServe,
            warnings = warnings
        )
    }

    /**
     * Analyze a project with HTML files but no package.json (static web project).
     */
    private fun analyzeStaticProject(root: File, scan: ProjectScan): AnalysisResult {
        val entryPoint = findEntryPoint(root, scan, null)

        // Check for linked JS files with module imports (ES modules)
        val htmlFile = File(root, entryPoint)
        val usesEsModules = if (htmlFile.exists()) {
            val content = htmlFile.readText()
            content.contains("type=\"module\"") || content.contains("type='module'")
        } else false

        Log.i(TAG, "Static project: entry=$entryPoint, esModules=$usesEsModules")

        return AnalysisResult(
            projectType = ProjectType.STATIC_WEB,
            entryPoint = entryPoint,
            serveDirectory = root.absolutePath,
            description = if (usesEsModules) "Static project with ES modules" else "Static web project",
            requiresNode = false,
            packageManager = null,
            buildScript = null,
            startScript = null,
            iconPath = findIcon(root),
            hasBuildOutput = true,
            needsBuild = false,
            framework = null,
            isStaticServe = true
        )
    }

    /**
     * Analyze a project with no recognizable entry point.
     */
    private fun analyzeUnknownProject(root: File, scan: ProjectScan): AnalysisResult {
        val files = root.listFiles()?.map { it.name } ?: emptyList()
        Log.w(TAG, "Unknown project type. Files: $files")

        return AnalysisResult(
            projectType = ProjectType.UNKNOWN,
            entryPoint = "index.html",
            serveDirectory = root.absolutePath,
            description = "Unknown project type",
            requiresNode = false,
            packageManager = null,
            buildScript = null,
            startScript = null,
            iconPath = findIcon(root),
            hasBuildOutput = false,
            needsBuild = false,
            framework = null,
            isStaticServe = true,
            errorMessage = "No index.html or package.json found. Project may not be compatible."
        )
    }

    // ==================== Project Scanning ====================

    data class ProjectScan(
        val hasPackageJson: Boolean,
        val htmlFiles: List<String>,        // Relative paths to HTML files
        val hasBuildOutput: Boolean,         // Has dist/build/out with HTML
        val buildOutputDir: String?,         // Which build output dir was found
        val hasSrcDir: Boolean,
        val hasNodeModules: Boolean,
        val hasPublicDir: Boolean,
        val lockfiles: List<String>,
        val configFiles: List<String>,
        val allFiles: Map<String, List<String>> // dir -> file names (1 level)
    )

    private fun scanProject(root: File): ProjectScan {
        val hasPackageJson = File(root, "package.json").exists()
        val htmlFiles = mutableListOf<String>()
        var hasBuildOutput = false
        var buildOutputDir: String? = null
        var hasSrcDir = false
        var hasNodeModules = false
        var hasPublicDir = false
        val lockfiles = mutableListOf<String>()
        val configFiles = mutableListOf<String>()
        val allFiles = mutableMapOf<String, List<String>>()

        // Check immediate children
        root.listFiles()?.forEach { file ->
            if (file.isFile) {
                allFiles.getOrPut("") { mutableListOf<String>() as List<String> }.let {
                    // Track file names
                }
                if (file.name.endsWith(".html") || file.name.endsWith(".htm")) {
                    htmlFiles.add(file.name)
                }
                if (file.name.endsWith("-lock.yaml") || file.name.endsWith(".lock") ||
                    file.name.endsWith("-lock.json") || file.name == "bun.lockb") {
                    lockfiles.add(file.name)
                }
                if (file.name.startsWith(".") || file.name in listOf(
                    "vite.config.ts", "vite.config.js", "next.config.js", "next.config.mjs",
                    "webpack.config.js", "tsconfig.json", ".eslintrc.json", ".babelrc",
                    "tailwind.config.js", "postcss.config.js", "nuxt.config.ts"
                )) {
                    configFiles.add(file.name)
                }
            } else if (file.isDirectory && !file.name.startsWith(".")) {
                allFiles[file.name] = file.list()?.toList() ?: emptyList()

                when (file.name) {
                    "src" -> hasSrcDir = true
                    "node_modules" -> hasNodeModules = true
                    "public" -> hasPublicDir = true
                }

                // Check for build output directories
                if (file.name in listOf("dist", "build", "out")) {
                    if (file.listFiles()?.any { it.name.endsWith(".html") } == true) {
                        hasBuildOutput = true
                        buildOutputDir = file.name
                    }
                }

                // Check public directory for HTML
                if (file.name == "public") {
                    if (file.listFiles()?.any { it.name.endsWith(".html") } == true) {
                        if (!hasBuildOutput) {
                            hasBuildOutput = true
                            buildOutputDir = "public"
                        }
                    }
                }
            }
        }

        // Search deeper for HTML files (up to MAX_SCAN_DEPTH levels, skipping node_modules etc.)
        if (htmlFiles.isEmpty()) {
            findHtmlFilesRecursive(root, htmlFiles, 0)
        }

        return ProjectScan(
            hasPackageJson = hasPackageJson,
            htmlFiles = htmlFiles,
            hasBuildOutput = hasBuildOutput,
            buildOutputDir = buildOutputDir,
            hasSrcDir = hasSrcDir,
            hasNodeModules = hasNodeModules,
            hasPublicDir = hasPublicDir,
            lockfiles = lockfiles,
            configFiles = configFiles,
            allFiles = allFiles
        )
    }

    private fun findHtmlFilesRecursive(dir: File, results: MutableList<String>, depth: Int) {
        if (depth >= MAX_SCAN_DEPTH) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name !in SKIP_DIRS && !file.name.startsWith(".")) {
                findHtmlFilesRecursive(file, results, depth + 1)
            } else if (file.isFile && (file.name.endsWith(".html") || file.name.endsWith(".htm"))) {
                val relativePath = file.relativeTo(dir).path
                if (relativePath !in results) {
                    results.add(relativePath)
                }
            }
        }
    }

    // ==================== Entry Point Resolution ====================

    /**
     * Find the best entry point HTML file, ranked by priority.
     */
    private fun findEntryPoint(root: File, scan: ProjectScan, buildOutputDir: File?): String {
        // If we have a build output directory, look there first
        if (buildOutputDir != null && buildOutputDir.exists()) {
            val candidates = listOf("index.html", "index.htm")
            for (candidate in candidates) {
                if (File(buildOutputDir, candidate).exists()) {
                    return candidate
                }
            }
        }

        // Check root directory
        val rootCandidates = listOf("index.html", "index.htm", "main.html", "app.html", "home.html")
        for (candidate in rootCandidates) {
            if (File(root, candidate).exists()) return candidate
        }

        // Check public/ directory
        if (scan.hasPublicDir) {
            for (candidate in rootCandidates) {
                if (File(root, "public/$candidate").exists()) return "public/$candidate"
            }
        }

        // Search subdirectories (one level deep)
        root.listFiles()?.filter { it.isDirectory && it.name !in SKIP_DIRS && !it.name.startsWith(".") }
            ?.forEach { subDir ->
                for (candidate in rootCandidates) {
                    if (File(subDir, candidate).exists()) return "${subDir.name}/$candidate"
                }
            }

        // Last resort: return any HTML file found
        if (scan.htmlFiles.isNotEmpty()) {
            return scan.htmlFiles.first()
        }

        return "index.html"
    }

    /**
     * Find the build output directory if it exists and has content.
     */
    private fun findBuildOutput(root: File, scan: ProjectScan): File? {
        val candidates = listOf("dist", "build", "out")
        for (candidate in candidates) {
            val dir = File(root, candidate)
            if (dir.exists() && dir.isDirectory) {
                // Check if it has any HTML files
                val hasHtml = dir.walkTopDown()
                    .maxDepth(2)
                    .any { it.isFile && (it.name.endsWith(".html") || it.name.endsWith(".htm")) }
                if (hasHtml) return dir
            }
        }
        // Check public/ for Vite/CRA dev mode
        val publicDir = File(root, "public")
        if (publicDir.exists() && publicDir.isDirectory) {
            val hasHtml = publicDir.listFiles()?.any { it.name.endsWith(".html") } == true
            if (hasHtml) return publicDir
        }
        return null
    }

    // ==================== Framework Detection ====================

    private fun detectFramework(deps: JSONObject, devDeps: JSONObject): String? {
        // Check for specific frameworks in order of specificity
        val frameworkChecks = listOf(
            "next" to "nextjs",
            "nuxt" to "nuxt",
            "nuxt3" to "nuxt",
            "@angular/core" to "angular",
            "svelte" to "svelte",
            "@sveltejs/kit" to "sveltekit",
            "vue" to "vue",
            "react-dom" to "react",
            "react" to "react",
            "preact" to "preact",
            "solid-js" to "solid",
            "three" to "threejs"
        )

        for ((pkg, framework) in frameworkChecks) {
            if (deps.has(pkg) || devDeps.has(pkg)) return framework
        }

        // Check for bundlers
        val bundlerChecks = listOf(
            "vite" to "vite",
            "webpack" to "webpack",
            "parcel" to "parcel",
            "esbuild" to "esbuild",
            "rollup" to "rollup"
        )
        for ((pkg, framework) in bundlerChecks) {
            if (deps.has(pkg) || devDeps.has(pkg)) return framework
        }

        return null
    }

    private fun hasServerDependencies(deps: JSONObject, devDeps: JSONObject): Boolean {
        val serverPackages = listOf(
            "express", "fastify", "koa", "hapi", "@hapi/hapi",
            "next", "nuxt", "svelte-kit", "@sveltejs/kit",
            "drizzle-orm", "pg", "postgres", "mysql2",
            "mongoose", "sequelize", "prisma"
        )
        for (pkg in serverPackages) {
            if (deps.has(pkg) || devDeps.has(pkg)) {
                Log.d(TAG, "Found server dependency: $pkg")
                return true
            }
        }
        return false
    }

    // ==================== Utility Functions ====================

    private fun detectPackageManager(dir: File): String {
        return when {
            File(dir, "pnpm-lock.yaml").exists() -> "pnpm"
            File(dir, "yarn.lock").exists() -> "yarn"
            File(dir, "bun.lockb").exists() -> "bun"
            File(dir, "package-lock.json").exists() -> "npm"
            else -> "npm"
        }
    }

    private fun findIcon(dir: File): String? {
        val iconCandidates = listOf(
            "icon.png", "icon.jpg", "icon.svg",
            "favicon.ico", "favicon.png", "favicon.svg",
            "logo.png", "logo.svg", "logo.jpg",
            "apple-touch-icon.png", "og-image.png"
        )
        for (candidate in iconCandidates) {
            val file = File(dir, candidate)
            if (file.exists()) return candidate
        }
        // Check public/ directory
        val publicDir = File(dir, "public")
        if (publicDir.exists()) {
            for (candidate in iconCandidates) {
                val file = File(publicDir, candidate)
                if (file.exists()) return "public/$candidate"
            }
        }
        return null
    }

    private fun unwrapWrapperDir(dir: File): File {
        val children = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return dir
        val nonHidden = children.filter { !it.name.startsWith("._") }
        // If there's exactly one subdirectory and no files, unwrap it
        if (nonHidden.size == 1 && nonHidden[0].isDirectory) {
            val inner = nonHidden[0]
            // Only unwrap if the inner directory has more content (package.json, index.html, etc.)
            if (File(inner, "package.json").exists() || File(inner, "index.html").exists() ||
                inner.listFiles()?.any { it.name.endsWith(".html") } == true) {
                return inner
            }
        }
        return dir
    }

    private fun errorResult(message: String): AnalysisResult {
        Log.w(TAG, "Analysis error: $message")
        return AnalysisResult(
            projectType = ProjectType.UNKNOWN,
            entryPoint = "index.html",
            serveDirectory = "",
            description = message,
            requiresNode = false,
            packageManager = null,
            buildScript = null,
            startScript = null,
            iconPath = null,
            errorMessage = message
        )
    }
}
