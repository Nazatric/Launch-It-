package com.launchit.app.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.launchit.app.data.ProjectRepository
import com.launchit.app.model.Project
import com.launchit.app.model.ProjectType
import com.launchit.app.model.RuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Handles importing projects from various sources (ZIP archives, directories).
 */
class ProjectImporter(
    private val context: Context,
    private val repository: ProjectRepository,
    private val analyzer: ProjectAnalyzer
) {
    companion object {
        private const val TAG = "LaunchItImporter"
        private const val MAX_ZIP_SIZE = 500L * 1024 * 1024 // 500MB max
        private const val MAX_SINGLE_FILE_SIZE = 100L * 1024 * 1024 // 100MB max per file
    }

    data class ImportResult(
        val project: Project,
        val analysis: ProjectAnalyzer.AnalysisResult,
        val success: Boolean,
        val errorMessage: String? = null
    )

    private val emptyAnalysis = ProjectAnalyzer.AnalysisResult(
        projectType = ProjectType.UNKNOWN,
        entryPoint = "",
        serveDirectory = "",
        description = "",
        requiresNode = false,
        packageManager = null,
        buildScript = null,
        startScript = null,
        iconPath = null,
        hasBuildOutput = false,
        needsBuild = false,
        framework = null,
        isStaticServe = false,
        warnings = emptyList(),
        errorMessage = null
    )

    /**
     * Import a project from a folder URI (Directory picker result via SAF).
     * Copies all files from the selected directory tree into internal storage.
     */
    suspend fun importFromFolderUri(uri: Uri, displayName: String? = null): ImportResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Import started for folder URI: $uri, displayName: $displayName")

        try {
            // Take persistable URI permission for folder access
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                Log.d(TAG, "Persistable folder URI permission granted")
            } catch (e: SecurityException) {
                // Try read-only permission
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.d(TAG, "Read-only persistable folder URI permission granted")
                } catch (e2: SecurityException) {
                    Log.d(TAG, "Could not take persistable folder permission: ${e2.message}")
                }
            }

            val folderName = getFolderName(uri) ?: displayName ?: "imported-folder"
            Log.i(TAG, "Folder name: $folderName")

            return@withContext importFolderContents(uri, folderName)
        } catch (e: Exception) {
            Log.e(TAG, "Folder import failed unexpectedly", e)
            ImportResult(
                project = Project(name = displayName ?: "Unknown"),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Folder import failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private suspend fun importFolderContents(folderUri: Uri, name: String): ImportResult {
        Log.i(TAG, "Importing folder contents: $name")

        val projectDir = getProjectDir(name)

        // Clean up any existing directory with same name
        if (projectDir.exists()) {
            Log.d(TAG, "Cleaning up existing project dir: ${projectDir.absolutePath}")
            projectDir.deleteRecursively()
        }
        if (!projectDir.mkdirs()) {
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Failed to create project directory"
            )
        }

        // Copy all files from the SAF tree to internal storage
        try {
            copyFolderTree(folderUri, projectDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy folder contents", e)
            projectDir.deleteRecursively()
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Failed to copy folder contents: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        // Verify the extracted directory has content
        val children = projectDir.listFiles()
        if (children == null || children.isEmpty()) {
            projectDir.deleteRecursively()
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Selected folder was empty or contained no accessible files."
            )
        }

        // The folder might contain a single top-level directory; unwrap if needed
        val actualProjectDir = unwrapSingleDirectory(projectDir)
        Log.i(TAG, "Actual project dir: ${actualProjectDir.absolutePath}")

        // Analyze the project
        val analysis = analyzer.analyze(actualProjectDir, name)
        Log.i(TAG, "Folder analysis result: type=${analysis.projectType}, entry=${analysis.entryPoint}, requiresNode=${analysis.requiresNode}")

        val project = Project(
            name = name,
            displayName = name,
            projectType = analysis.projectType,
            entryPoint = analysis.entryPoint,
            sourcePath = actualProjectDir.absolutePath,
            buildOutputPath = if (analysis.serveDirectory != actualProjectDir.absolutePath) analysis.serveDirectory else null,
            iconPath = analysis.iconPath,
            runtimeState = RuntimeState.IMPORTED,
            description = analysis.description,
            requiresNode = analysis.requiresNode,
            packageManager = analysis.packageManager,
            buildScript = analysis.buildScript,
            startScript = analysis.startScript,
            storageUsedBytes = calculateDirSize(actualProjectDir),
            errorMessage = if (analysis.needsBuild) analysis.warnings.firstOrNull() else null
        )

        // Save to database
        try {
            repository.insertProject(project)
            Log.i(TAG, "Folder project saved to database: id=${project.id}, name=${project.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save folder project to database", e)
            projectDir.deleteRecursively()
            return ImportResult(
                project = project,
                analysis = analysis,
                success = false,
                errorMessage = "Failed to save project: ${e.message}"
            )
        }

        return ImportResult(project = project, analysis = analysis, success = true)
    }

    /**
     * Recursively copy files from a SAF DocumentFile tree to a target directory.
     */
    private fun copyFolderTree(folderUri: Uri, targetDir: File) {
        val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
            ?: androidx.documentfile.provider.DocumentFile.fromSingleUri(context, folderUri)
            ?: throw IOException("Cannot access selected folder")

        if (!documentFile.isDirectory) {
            throw IOException("Selected URI is not a folder")
        }

        copyDocumentFile(documentFile, targetDir)
    }

    private fun copyDocumentFile(source: androidx.documentfile.provider.DocumentFile, targetDir: File) {
        if (source.isDirectory) {
            targetDir.mkdirs()
            source.listFiles().forEach { child ->
                val childTarget = File(targetDir, child.name ?: return@forEach)
                if (child.isDirectory) {
                    copyDocumentFile(child, childTarget)
                } else if (child.isFile) {
                    copyDocumentFile(child, childTarget)
                }
            }
        } else if (source.isFile) {
            val targetFile = File(targetDir, source.name ?: "unnamed")
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Cannot read file: ${source.name}")
        }
    }

    private fun getFolderName(uri: Uri): String? {
        // Try to extract folder name from the URI path
        val lastSegment = uri.lastPathSegment
        return lastSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: lastSegment?.substringAfterLast('/')
    }

    /**
     * Import a project from a URI (file picker result).
     */
    suspend fun importFromUri(uri: Uri, displayName: String? = null): ImportResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Import started for URI: $uri, displayName: $displayName")

        try {
            // Take persistable URI permission so we can access the file across coroutine dispatches
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                Log.d(TAG, "Persistable URI permission granted")
            } catch (e: SecurityException) {
                // Not all URIs support persistable permissions (e.g., from some file managers)
                // The temporary grant should still be valid for this session
                Log.d(TAG, "Could not take persistable permission (may still work): ${e.message}")
            }

            val mimeType = context.contentResolver.getType(uri)
            val fileName = getFileName(uri) ?: "imported-project"
            Log.i(TAG, "File: name=$fileName, mime=$mimeType")

            return@withContext when {
                mimeType == "application/zip" ||
                mimeType == "application/x-zip-compressed" ||
                fileName.endsWith(".zip", ignoreCase = true) -> {
                    importZipArchive(uri, displayName ?: fileName.removeSuffix(".zip"))
                }
                mimeType == "application/x-gzip" || fileName.endsWith(".tar.gz", ignoreCase = true) || fileName.endsWith(".tgz", ignoreCase = true) -> {
                    ImportResult(
                        project = Project(name = displayName ?: fileName),
                        analysis = emptyAnalysis,
                        success = false,
                        errorMessage = "Tar/gzip archives are not yet supported. Please use ZIP format."
                    )
                }
                else -> {
                    if (fileName.endsWith(".html", ignoreCase = true) || fileName.endsWith(".htm", ignoreCase = true)) {
                        importSingleHtml(uri, displayName ?: fileName)
                    } else {
                        ImportResult(
                            project = Project(name = displayName ?: fileName),
                            analysis = emptyAnalysis,
                            success = false,
                            errorMessage = "Unsupported file type. Supported: .zip, .html, .htm"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed unexpectedly", e)
            ImportResult(
                project = Project(name = displayName ?: "Unknown"),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Import failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private suspend fun importZipArchive(uri: Uri, name: String): ImportResult {
        Log.i(TAG, "Importing ZIP archive: $name")

        val projectDir = getProjectDir(name)

        // Clean up any existing directory with same name to avoid conflicts
        if (projectDir.exists()) {
            Log.d(TAG, "Cleaning up existing project dir: ${projectDir.absolutePath}")
            projectDir.deleteRecursively()
        }
        if (!projectDir.mkdirs()) {
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Failed to create project directory"
            )
        }

        // Extract ZIP with security checks
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                projectDir.deleteRecursively()
                return ImportResult(
                    project = Project(name = name),
                    analysis = emptyAnalysis,
                    success = false,
                    errorMessage = "Could not open the selected file. It may have been moved or deleted."
                )
            }

            inputStream.use { stream ->
                extractZipSafely(stream, projectDir)
            }

            Log.d(TAG, "ZIP extracted successfully to ${projectDir.absolutePath}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading file", e)
            projectDir.deleteRecursively()
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Permission denied. Please select the file again."
            )
        } catch (e: IOException) {
            Log.e(TAG, "ZIP extraction failed", e)
            projectDir.deleteRecursively()
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Failed to extract archive: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during extraction", e)
            projectDir.deleteRecursively()
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Extraction failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        // Verify the extracted directory has content
        val children = projectDir.listFiles()
        if (children == null || children.isEmpty()) {
            projectDir.deleteRecursively()
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Archive was empty or contained no extractable files."
            )
        }

        // The ZIP might contain a single top-level directory; unwrap if needed
        val actualProjectDir = unwrapSingleDirectory(projectDir)
        Log.i(TAG, "Actual project dir: ${actualProjectDir.absolutePath}")

        // Analyze the project
        val analysis = analyzer.analyze(actualProjectDir, name)
        Log.i(TAG, "Analysis result: type=${analysis.projectType}, entry=${analysis.entryPoint}, requiresNode=${analysis.requiresNode}")

        val project = Project(
            name = name,
            displayName = name,
            projectType = analysis.projectType,
            entryPoint = analysis.entryPoint,
            sourcePath = actualProjectDir.absolutePath,
            buildOutputPath = if (analysis.serveDirectory != actualProjectDir.absolutePath) analysis.serveDirectory else null,
            iconPath = analysis.iconPath,
            runtimeState = RuntimeState.IMPORTED,
            description = analysis.description,
            requiresNode = analysis.requiresNode,
            packageManager = analysis.packageManager,
            buildScript = analysis.buildScript,
            startScript = analysis.startScript,
            storageUsedBytes = calculateDirSize(actualProjectDir),
            errorMessage = if (analysis.needsBuild) analysis.warnings.firstOrNull() else null
        )

        // Save to database
        try {
            repository.insertProject(project)
            Log.i(TAG, "Project saved to database: id=${project.id}, name=${project.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save project to database", e)
            // Clean up files if DB insert fails
            projectDir.deleteRecursively()
            return ImportResult(
                project = project,
                analysis = analysis,
                success = false,
                errorMessage = "Failed to save project: ${e.message}"
            )
        }

        return ImportResult(project = project, analysis = analysis, success = true)
    }

    private suspend fun importSingleHtml(uri: Uri, name: String): ImportResult {
        Log.i(TAG, "Importing single HTML file: $name")

        val projectDir = getProjectDir(name)
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
        if (!projectDir.mkdirs()) {
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Failed to create project directory"
            )
        }

        val destFile = File(projectDir, "index.html")
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                projectDir.deleteRecursively()
                return ImportResult(
                    project = Project(name = name),
                    analysis = emptyAnalysis,
                    success = false,
                    errorMessage = "Could not open the selected HTML file."
                )
            }

            inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "HTML file copied to ${destFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import HTML file", e)
            projectDir.deleteRecursively()
            return ImportResult(
                project = Project(name = name),
                analysis = emptyAnalysis,
                success = false,
                errorMessage = "Failed to copy file: ${e.message}"
            )
        }

        val analysis = analyzer.analyze(projectDir, name)

        val project = Project(
            name = name,
            displayName = name,
            projectType = ProjectType.STATIC_WEB,
            entryPoint = "index.html",
            sourcePath = projectDir.absolutePath,
            runtimeState = RuntimeState.IMPORTED,
            storageUsedBytes = destFile.length()
        )

        try {
            repository.insertProject(project)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save project to database", e)
            projectDir.deleteRecursively()
            return ImportResult(
                project = project,
                analysis = analysis,
                success = false,
                errorMessage = "Failed to save project: ${e.message}"
            )
        }

        Log.i(TAG, "HTML project imported: id=${project.id}")
        return ImportResult(project = project, analysis = analysis, success = true)
    }

    /**
     * Extract a ZIP archive safely, preventing path traversal attacks.
     */
    private fun extractZipSafely(inputStream: InputStream, targetDir: File) {
        val targetCanonical = targetDir.canonicalPath
        var fileCount = 0
        var totalSize = 0L

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry: ZipEntry?

            while (zis.nextEntry.also { entry = it } != null) {
                val currentEntry = entry!!
                val entryName = currentEntry.name

                // Security: Check for path traversal
                if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\")) {
                    Log.w(TAG, "Skipping dangerous entry: $entryName")
                    zis.closeEntry()
                    continue
                }

                val outFile = File(targetDir, entryName)

                // Security: Verify resolved path is within target directory
                val outCanonical = outFile.canonicalPath
                if (!outCanonical.startsWith(targetCanonical + File.separator) &&
                    outCanonical != targetCanonical) {
                    Log.w(TAG, "Path traversal blocked: $entryName")
                    zis.closeEntry()
                    continue
                }

                if (currentEntry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    // Ensure parent directory exists
                    outFile.parentFile?.mkdirs()

                    // Check declared size (might be -1 if unknown)
                    val declaredSize = currentEntry.size
                    if (declaredSize > MAX_SINGLE_FILE_SIZE) {
                        Log.w(TAG, "Skipping oversized file: $entryName ($declaredSize bytes)")
                        zis.closeEntry()
                        continue
                    }

                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var fileBytesWritten = 0L
                        while (zis.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            fileBytesWritten += bytesRead
                            totalSize += bytesRead

                            // Check actual written size (in case declared size was wrong)
                            if (fileBytesWritten > MAX_SINGLE_FILE_SIZE) {
                                throw IOException("File $entryName exceeds maximum size limit")
                            }
                            if (totalSize > MAX_ZIP_SIZE) {
                                throw IOException("Archive exceeds maximum total size limit (${MAX_ZIP_SIZE / (1024 * 1024)}MB)")
                            }
                        }
                    }
                    fileCount++
                }
                zis.closeEntry()
            }
        }
        Log.i(TAG, "Extracted $fileCount files, total size: $totalSize bytes")
    }

    private fun getProjectDir(name: String): File {
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
        // Add a timestamp suffix to handle duplicate names
        val dirName = "${safeName}_${System.currentTimeMillis()}"
        return File(context.filesDir, "projects/$dirName")
    }

    /**
     * If the extracted ZIP contains a single top-level directory, return that directory.
     */
    private fun unwrapSingleDirectory(dir: File): File {
        val children = dir.listFiles() ?: return dir
        val nonHidden = children.filter { !it.name.startsWith(".") }
        if (nonHidden.size == 1 && nonHidden[0].isDirectory) {
            return nonHidden[0]
        }
        return dir
    }

    private fun calculateDirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun getFileName(uri: Uri): String? {
        // Try content resolver first
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not query file name: ${e.message}")
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
