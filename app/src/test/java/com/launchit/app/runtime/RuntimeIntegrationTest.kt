package com.launchit.app.runtime

import com.launchit.app.model.ProjectType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Integration tests for the LocalHttpServer and ProjectAnalyzer.
 * Tests actual HTTP serving, path traversal prevention, MIME types, and project classification.
 */
class RuntimeIntegrationTest {

    private lateinit var testDir: File
    private var server: LocalHttpServer? = null

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "launchit-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        try { server?.stop() } catch (_: Exception) {}
        testDir.deleteRecursively()
    }

    // ==================== HTTP Server Tests ====================

    @Test
    fun `server serves index html from root`() {
        File(testDir, "index.html").writeText("<html><body>Hello LaunchIt</body></html>")

        val port = findFreePort()
        server = LocalHttpServer(port, testDir)
        server!!.start()
        waitForPort(port)

        val response = httpGet("http://127.0.0.1:$port/")
        assertEquals(200, response.first)
        assertTrue("Response should contain Hello LaunchIt but was: ${response.second}", response.second.contains("Hello LaunchIt"))
    }

    @Test
    fun `server serves relative CSS and JS assets`() {
        File(testDir, "index.html").writeText("""<html><head><link rel="stylesheet" href="style.css"></head><body><script src="app.js"></script></body></html>""")
        File(testDir, "style.css").writeText("body { background: blue; }")
        File(testDir, "app.js").writeText("console.log('hello');")

        val port = findFreePort()
        server = LocalHttpServer(port, testDir)
        server!!.start()
        waitForPort(port)

        val cssResponse = httpGet("http://127.0.0.1:$port/style.css")
        assertEquals(200, cssResponse.first)
        assertTrue(cssResponse.second.contains("background: blue"))

        val jsResponse = httpGet("http://127.0.0.1:$port/app.js")
        assertEquals(200, jsResponse.first)
        assertTrue(jsResponse.second.contains("console.log"))
    }

    @Test
    fun `server handles nested directories`() {
        File(testDir, "index.html").writeText("<html>Root</html>")
        val subDir = File(testDir, "assets")
        subDir.mkdirs()
        File(subDir, "data.json").writeText("""{"width": 100}""")

        val port = findFreePort()
        server = LocalHttpServer(port, testDir)
        server!!.start()
        waitForPort(port)

        val response = httpGet("http://127.0.0.1:$port/assets/data.json")
        assertEquals(200, response.first)
        assertTrue(response.second.contains("width"))
    }

    @Test
    fun `server blocks path traversal attempts`() {
        File(testDir, "index.html").writeText("<html>OK</html>")
        // Create a file outside the project dir
        val outsideFile = File(testDir.parentFile, "secret.txt")
        outsideFile.writeText("SECRET DATA")

        val port = findFreePort()
        server = LocalHttpServer(port, testDir)
        server!!.start()
        waitForPort(port)

        // Try path traversal
        val response = httpGet("http://127.0.0.1:$port/..%2F..%2Fsecret.txt")
        assertNotEquals("Path traversal should not return 200", 200, response.first)

        outsideFile.delete()
    }

    @Test
    fun `server SPA fallback returns index for unknown routes`() {
        File(testDir, "index.html").writeText("<html>SPA App</html>")

        val port = findFreePort()
        server = LocalHttpServer(port, testDir)
        server!!.start()
        waitForPort(port)

        val response = httpGet("http://127.0.0.1:$port/some/random/path")
        assertEquals(200, response.first)
        assertTrue(response.second.contains("SPA App"))
    }

    @Test
    fun `server handles explicit entry point request`() {
        File(testDir, "index.html").writeText("<html>Entry Point</html>")

        val port = findFreePort()
        server = LocalHttpServer(port, testDir)
        server!!.start()
        waitForPort(port)

        val response = httpGet("http://127.0.0.1:$port/index.html")
        assertEquals(200, response.first)
        assertTrue(response.second.contains("Entry Point"))
    }

    @Test
    fun `server stops cleanly and releases port`() {
        File(testDir, "index.html").writeText("<html>Test</html>")

        val port = findFreePort()
        server = LocalHttpServer(port, testDir)
        server!!.start()
        waitForPort(port)

        val response = httpGet("http://127.0.0.1:$port/")
        assertEquals(200, response.first)

        server!!.stop()
        server = null

        // Wait briefly for port release
        Thread.sleep(200)
        assertTrue("Port should be free after server stop", isPortFree(port))
    }

    @Test
    fun `server handles concurrent requests`() {
        File(testDir, "index.html").writeText("<html>Concurrent</html>")

        val port = findFreePort()
        server = LocalHttpServer(port, testDir)
        server!!.start()
        waitForPort(port)

        val results = mutableListOf<Boolean>()
        val threads = (1..5).map { i ->
            Thread {
                val response = httpGet("http://127.0.0.1:$port/index.html")
                synchronized(results) {
                    results.add(response.first == 200)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        assertEquals("All 5 concurrent requests should succeed", 5, results.count { it })
    }

    // ==================== Project Analyzer Tests ====================

    @Test
    fun `analyzer detects static HTML project`() {
        val projectDir = File(testDir, "static-project")
        projectDir.mkdirs()
        File(projectDir, "index.html").writeText("<html><body>Test</body></html>")
        File(projectDir, "style.css").writeText("body {}")
        File(projectDir, "app.js").writeText("var x = 1;")

        val analyzer = ProjectAnalyzer()
        val result = analyzer.analyze(projectDir, "test-static")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertEquals("index.html", result.entryPoint)
        assertFalse(result.requiresNode)
        assertNull(result.errorMessage)
    }

    @Test
    fun `analyzer detects frontend app with build script`() {
        val projectDir = File(testDir, "react-app")
        projectDir.mkdirs()
        File(projectDir, "package.json").writeText("""
            {
                "name": "my-react-app",
                "scripts": {"build": "vite build", "dev": "vite"},
                "dependencies": {"react": "^19.0.0", "react-dom": "^19.0.0"},
                "devDependencies": {"vite": "^5.0.0"}
            }
        """.trimIndent())

        val analyzer = ProjectAnalyzer()
        val result = analyzer.analyze(projectDir, "react-app")

        assertEquals(ProjectType.FRONTEND_APP, result.projectType)
        assertTrue(result.requiresNode)
        assertEquals("build", result.buildScript)
        assertEquals("npm", result.packageManager)
    }

    @Test
    fun `analyzer detects Node.js server project`() {
        val projectDir = File(testDir, "node-server")
        projectDir.mkdirs()
        File(projectDir, "package.json").writeText("""
            {
                "name": "my-server",
                "main": "server.js",
                "scripts": {"start": "node server.js"},
                "dependencies": {"express": "^4.18.0"}
            }
        """.trimIndent())

        val analyzer = ProjectAnalyzer()
        val result = analyzer.analyze(projectDir, "node-server")

        assertEquals(ProjectType.NODE_SERVER, result.projectType)
        assertTrue(result.requiresNode)
        assertEquals("server.js", result.entryPoint)
        assertEquals("start", result.startScript)
    }

    @Test
    fun `analyzer detects Next.js full stack project`() {
        val projectDir = File(testDir, "next-app")
        projectDir.mkdirs()
        File(projectDir, "package.json").writeText("""
            {
                "name": "my-next-app",
                "scripts": {"build": "next build", "start": "next start", "dev": "next dev"},
                "dependencies": {"next": "16.2.6", "react": "19.2.6", "drizzle-orm": "^0.30.0", "pg": "^8.12.0"}
            }
        """.trimIndent())

        val analyzer = ProjectAnalyzer()
        val result = analyzer.analyze(projectDir, "next-app")

        assertEquals(ProjectType.FULL_STACK, result.projectType)
        assertTrue(result.requiresNode)
        assertEquals("build", result.buildScript)
        assertEquals("start", result.startScript)
    }

    @Test
    fun `analyzer detects three.js project as static web`() {
        val projectDir = File(testDir, "threejs-game")
        projectDir.mkdirs()
        File(projectDir, "index.html").writeText("""
            <html><body><canvas id="game"></canvas>
            <script type="importmap">{"imports": {"three": "./node_modules/three/build/three.module.js"}}</script>
            <script type="module" src="game.js"></script></body></html>
        """.trimIndent())
        File(projectDir, "game.js").writeText("import * as THREE from 'three';")

        val analyzer = ProjectAnalyzer()
        val result = analyzer.analyze(projectDir, "threejs-game")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertEquals("index.html", result.entryPoint)
        assertFalse(result.requiresNode)
    }

    @Test
    fun `analyzer handles missing directory gracefully`() {
        val missingDir = File(testDir, "does-not-exist")

        val analyzer = ProjectAnalyzer()
        val result = analyzer.analyze(missingDir, "missing")

        assertEquals(ProjectType.UNKNOWN, result.projectType)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `analyzer handles empty package.json`() {
        val projectDir = File(testDir, "empty-json")
        projectDir.mkdirs()
        File(projectDir, "package.json").writeText("")

        val analyzer = ProjectAnalyzer()
        val result = analyzer.analyze(projectDir, "empty-json")

        assertEquals(ProjectType.UNKNOWN, result.projectType)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `analyzer detects package manager from lockfiles`() {
        // yarn.lock -> yarn
        val yarnDir = File(testDir, "yarn-project")
        yarnDir.mkdirs()
        File(yarnDir, "package.json").writeText("""{"name":"test","scripts":{"build":"tsc"},"dependencies":{}}""")
        File(yarnDir, "yarn.lock").writeText("")

        val analyzer = ProjectAnalyzer()
        assertEquals("yarn", analyzer.analyze(yarnDir, "yarn").packageManager)

        // pnpm-lock.yaml -> pnpm
        val pnpmDir = File(testDir, "pnpm-project")
        pnpmDir.mkdirs()
        File(pnpmDir, "package.json").writeText("""{"name":"test","scripts":{"build":"tsc"},"dependencies":{}}""")
        File(pnpmDir, "pnpm-lock.yaml").writeText("")

        assertEquals("pnpm", analyzer.analyze(pnpmDir, "pnpm").packageManager)

        // package-lock.json -> npm
        val npmDir = File(testDir, "npm-project")
        npmDir.mkdirs()
        File(npmDir, "package.json").writeText("""{"name":"test","scripts":{"build":"tsc"},"dependencies":{}}""")
        File(npmDir, "package-lock.json").writeText("{}")

        assertEquals("npm", analyzer.analyze(npmDir, "npm").packageManager)
    }

    @Test
    fun `analyzer detects project with nested index html`() {
        val projectDir = File(testDir, "nested-project")
        projectDir.mkdirs()
        val subDir = File(projectDir, "web")
        subDir.mkdirs()
        File(subDir, "index.html").writeText("<html>Nested</html>")

        val analyzer = ProjectAnalyzer()
        val result = analyzer.analyze(projectDir, "nested")

        // Should find the nested index.html
        assertNotNull("Should find entry point", result.entryPoint)
        assertTrue("Entry should contain web/", result.entryPoint.contains("web/"))
    }

    // ==================== Helpers ====================

    private fun findFreePort(): Int {
        return java.net.ServerSocket(0).use { it.localPort }
    }

    private fun isPortFree(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                socket.close()
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
    }

    private fun httpGet(urlString: String): Pair<Int, String> {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.instanceFollowRedirects = false
            val status = conn.responseCode
            val body = try {
                conn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            }
            conn.disconnect()
            Pair(status, body)
        } catch (e: Exception) {
            Pair(-1, "Connection error: ${e.message}")
        }
    }
}
