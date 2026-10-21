package com.launchit.app.runtime

import org.junit.Assert.*
import org.junit.Test

class LocalHttpServerTest {

    @Test
    fun `getMimeType returns correct types for common extensions`() {
        assertEquals("text/html; charset=utf-8", LocalHttpServer.getMimeType("index.html"))
        assertEquals("text/css; charset=utf-8", LocalHttpServer.getMimeType("style.css"))
        assertEquals("application/javascript; charset=utf-8", LocalHttpServer.getMimeType("app.js"))
        assertEquals("application/javascript; charset=utf-8", LocalHttpServer.getMimeType("module.mjs"))
        assertEquals("application/json; charset=utf-8", LocalHttpServer.getMimeType("data.json"))
        assertEquals("image/png", LocalHttpServer.getMimeType("logo.png"))
        assertEquals("image/jpeg", LocalHttpServer.getMimeType("photo.jpg"))
        assertEquals("image/jpeg", LocalHttpServer.getMimeType("photo.jpeg"))
        assertEquals("image/svg+xml", LocalHttpServer.getMimeType("icon.svg"))
        assertEquals("image/webp", LocalHttpServer.getMimeType("image.webp"))
        assertEquals("font/woff2", LocalHttpServer.getMimeType("font.woff2"))
        assertEquals("font/woff", LocalHttpServer.getMimeType("font.woff"))
        assertEquals("font/ttf", LocalHttpServer.getMimeType("font.ttf"))
        assertEquals("application/wasm", LocalHttpServer.getMimeType("module.wasm"))
    }

    @Test
    fun `getMimeType returns octet-stream for unknown extensions`() {
        assertEquals("application/octet-stream", LocalHttpServer.getMimeType("file.xyz"))
        assertEquals("application/octet-stream", LocalHttpServer.getMimeType("noext"))
    }

    @Test
    fun `getMimeType handles case insensitive extensions`() {
        assertEquals("text/html; charset=utf-8", LocalHttpServer.getMimeType("page.HTML"))
        assertEquals("text/css; charset=utf-8", LocalHttpServer.getMimeType("styles.CSS"))
        assertEquals("application/javascript; charset=utf-8", LocalHttpServer.getMimeType("script.JS"))
    }
}
