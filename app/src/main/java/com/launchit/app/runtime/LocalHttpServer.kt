package com.launchit.app.runtime

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Local HTTP server for serving project files.
 * Uses a unique origin per project to support module loading, fetch, and proper browser APIs.
 */
class LocalHttpServer(
    private val port: Int,
    private val rootDir: File
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "LocalHttpServer"

        // MIME type mapping
        private val MIME_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "application/javascript; charset=utf-8",
            "mjs" to "application/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "webp" to "image/webp",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "wasm" to "application/wasm",
            "map" to "application/json",
            "txt" to "text/plain; charset=utf-8",
            "xml" to "application/xml",
            "pdf" to "application/pdf"
        )

        @JvmStatic
        fun getMimeType(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return MIME_TYPES[ext] ?: "application/octet-stream"
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        // Decode the URI path
        val decodedPath = java.net.URLDecoder.decode(uri, "UTF-8")

        // Prevent path traversal attacks
        val normalizedPath = decodedPath.replace("..", "").replace("//", "/")
        if (normalizedPath.contains("..")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }

        // Map URI to file
        val requestPath = if (normalizedPath == "/" || normalizedPath.isEmpty()) {
            "index.html"
        } else {
            normalizedPath.trimStart('/')
        }

        val requestedFile = File(rootDir, requestPath)

        // Security: ensure resolved path is within rootDir
        if (!requestedFile.canonicalPath.startsWith(rootDir.canonicalPath)) {
            Log.w(TAG, "Path traversal attempt blocked: $decodedPath")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }

        // If directory, try index.html
        val targetFile = when {
            requestedFile.isDirectory -> File(requestedFile, "index.html")
            !requestedFile.exists() -> {
                // SPA fallback: serve index.html for client-side routing
                val indexFile = File(rootDir, "index.html")
                if (indexFile.exists()) indexFile else requestedFile
            }
            else -> requestedFile
        }

        if (!targetFile.exists() || !targetFile.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not Found: $decodedPath"
            )
        }

        return try {
            val mimeType = getMimeType(targetFile.name)
            val fis = FileInputStream(targetFile)
            newFixedLengthResponse(Response.Status.OK, mimeType, fis, targetFile.length())
        } catch (e: IOException) {
            Log.e(TAG, "Error serving file: ${targetFile.absolutePath}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Internal Server Error"
            )
        }
    }
}
