package com.launchit.app.runtime

import com.launchit.app.model.ProjectType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Comprehensive tests for ProjectAnalyzer covering all project types and structures.
 * 
 * Test fixtures:
 * 1. Root index.html (static)
 * 2. Nested HTML entry point
 * 3. React app with package.json and src/
 * 4. Vite project with public/index.html
 * 5. Prebuilt project with dist/
 * 6. Node.js server
 * 7. Unknown project (no index.html, no package.json)
 * 8. ES modules project
 * 9. Multiple HTML files (ranking test)
 * 10. Wrapper directory in ZIP
 */
class ProjectAnalyzerTest {

    private lateinit var testDir: File
    private lateinit var analyzer: ProjectAnalyzer

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "launchit-analyzer-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
        analyzer = ProjectAnalyzer()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    // ==================== Static Projects ====================

    @Test
    fun `static project with root index_html detected correctly`() {
        File(testDir, "index.html").writeText("<html><body>Hello</body></html>")
        File(testDir, "style.css").writeText("body { color: red; }")

        val result = analyzer.analyze(testDir, "static-test")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertEquals("index.html", result.entryPoint)
        assertFalse(result.requiresNode)
        assertFalse(result.needsBuild)
        assertTrue(result.isStaticServe)
        assertNull(result.errorMessage)
    }

    @Test
    fun `static project with nested HTML entry point`() {
        File(testDir, "pages").mkdirs()
        File(testDir, "pages/index.html").writeText("<html><body>Nested</body></html>")

        val result = analyzer.analyze(testDir, "nested-test")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertEquals("pages/index.html", result.entryPoint)
        assertTrue(result.isStaticServe)
    }

    @Test
    fun `ES modules project detected as static`() {
        File(testDir, "index.html").writeText("""
            <html>
            <head><script type="module" src="./src/main.js"></script></head>
            <body><div id="app"></div></body>
            </html>
        """.trimIndent())
        File(testDir, "src").mkdirs()
        File(testDir, "src/main.js").writeText("import { app } from './app.js'; app();")

        val result = analyzer.analyze(testDir, "es-modules")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertEquals("index.html", result.entryPoint)
        assertFalse(result.requiresNode)
        assertFalse(result.needsBuild)
        assertTrue(result.isStaticServe)
        assertTrue(result.description.contains("ES modules"))
    }

    @Test
    fun `multiple HTML files ranks index_html first`() {
        File(testDir, "about.html").writeText("<html>About</html>")
        File(testDir, "index.html").writeText("<html>Home</html>")
        File(testDir, "contact.html").writeText("<html>Contact</html>")

        val result = analyzer.analyze(testDir, "multi-html")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertEquals("index.html", result.entryPoint)
    }

    // ==================== Framework Projects ====================

    @Test
    fun `React app with package_json detected as FRONTEND_APP`() {
        File(testDir, "package.json").writeText("""
            {
                "name": "my-react-app",
                "dependencies": { "react": "^18.0.0", "react-dom": "^18.0.0" },
                "scripts": { "build": "react-scripts build", "start": "react-scripts start" }
            }
        """.trimIndent())
        File(testDir, "src").mkdirs()
        File(testDir, "src/App.jsx").writeText("export default function App() { return <div>App</div>; }")

        val result = analyzer.analyze(testDir, "react-app")

        assertEquals(ProjectType.FRONTEND_APP, result.projectType)
        assertTrue(result.requiresNode)
        assertEquals("npm", result.packageManager)
        assertEquals("build", result.buildScript)
        assertEquals("start", result.startScript)
        assertEquals("react", result.framework)
        assertTrue(result.needsBuild)
        assertFalse(result.hasBuildOutput)
    }

    @Test
    fun `React app with dist output detected as ready to serve`() {
        File(testDir, "package.json").writeText("""
            {
                "name": "my-react-app",
                "dependencies": { "react": "^18.0.0" },
                "scripts": { "build": "react-scripts build" }
            }
        """.trimIndent())
        File(testDir, "dist").mkdirs()
        File(testDir, "dist/index.html").writeText("<html><body>Built App</body></html>")
        File(testDir, "dist/static/js/main.js").let { 
            it.parentFile.mkdirs()
            it.writeText("console.log('built')")
        }

        val result = analyzer.analyze(testDir, "react-built")

        assertEquals(ProjectType.FRONTEND_APP, result.projectType)
        assertTrue(result.hasBuildOutput)
        assertFalse(result.needsBuild)
        assertTrue(result.isStaticServe)
    }

    @Test
    fun `Vite project with public index_html`() {
        File(testDir, "package.json").writeText("""
            {
                "name": "vite-app",
                "devDependencies": { "vite": "^5.0.0" },
                "scripts": { "dev": "vite", "build": "vite build" }
            }
        """.trimIndent())
        File(testDir, "public").mkdirs()
        File(testDir, "public/index.html").writeText("""
            <html>
            <head><script type="module" src="/src/main.js"></script></head>
            <body><div id="app"></div></body>
            </html>
        """.trimIndent())
        File(testDir, "src").mkdirs()
        File(testDir, "src/main.js").writeText("import App from './App.vue'")

        val result = analyzer.analyze(testDir, "vite-app")

        assertEquals(ProjectType.FRONTEND_APP, result.projectType)
        assertEquals("vite", result.framework)
        assertTrue(result.hasBuildOutput) // public/index.html found
    }

    @Test
    fun `Next_js project detected as FULL_STACK`() {
        File(testDir, "package.json").writeText("""
            {
                "name": "next-app",
                "dependencies": { "next": "^14.0.0", "react": "^18.0.0" },
                "scripts": { "dev": "next dev", "build": "next build", "start": "next start" }
            }
        """.trimIndent())
        File(testDir, "pages").mkdirs()
        File(testDir, "pages/index.js").writeText("export default function Home() { return <div>Home</div>; }")

        val result = analyzer.analyze(testDir, "next-app")

        assertEquals(ProjectType.FULL_STACK, result.projectType)
        assertEquals("nextjs", result.framework)
        assertTrue(result.requiresNode)
    }

    @Test
    fun `Node Express server detected as NODE_SERVER`() {
        File(testDir, "package.json").writeText("""
            {
                "name": "express-api",
                "dependencies": { "express": "^4.18.0" },
                "scripts": { "start": "node server.js" }
            }
        """.trimIndent())
        File(testDir, "server.js").writeText("const express = require('express');")

        val result = analyzer.analyze(testDir, "express-api")

        assertEquals(ProjectType.NODE_SERVER, result.projectType)
        assertTrue(result.requiresNode)
        assertEquals("start", result.startScript)
    }

    @Test
    fun `package manager detected from lockfile`() {
        File(testDir, "package.json").writeText("""{ "name": "test" }""")
        File(testDir, "index.html").writeText("<html>Test</html>")
        File(testDir, "yarn.lock").writeText("# yarn lockfile v1")

        val result = analyzer.analyze(testDir, "yarn-project")

        assertEquals("yarn", result.packageManager)
    }

    @Test
    fun `pnpm detected from lockfile`() {
        File(testDir, "package.json").writeText("""{ "name": "test" }""")
        File(testDir, "index.html").writeText("<html>Test</html>")
        File(testDir, "pnpm-lock.yaml").writeText("lockfileVersion: '6.0'")

        val result = analyzer.analyze(testDir, "pnpm-project")

        assertEquals("pnpm", result.packageManager)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `unknown project with no HTML or package_json`() {
        File(testDir, "readme.md").writeText("# Some project")
        File(testDir, "data.json").writeText("{}")

        val result = analyzer.analyze(testDir, "unknown")

        assertEquals(ProjectType.UNKNOWN, result.projectType)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `empty directory handled gracefully`() {
        val result = analyzer.analyze(testDir, "empty")

        assertEquals(ProjectType.UNKNOWN, result.projectType)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `nonexistent directory handled gracefully`() {
        val fakeDir = File("/tmp/nonexistent-${System.currentTimeMillis()}")
        val result = analyzer.analyze(fakeDir, "fake")

        assertEquals(ProjectType.UNKNOWN, result.projectType)
        assertTrue(result.errorMessage?.contains("not found") == true || 
                   result.errorMessage?.contains("not a directory") == true)
    }

    @Test
    fun `wrapper directory is unwrapped correctly`() {
        // Simulates a ZIP with a single top-level directory
        val wrapper = File(testDir, "my-project")
        wrapper.mkdirs()
        File(wrapper, "index.html").writeText("<html>Unwrapped</html>")
        File(wrapper, "style.css").writeText("body {}")

        val result = analyzer.analyze(testDir, "wrapped-project")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        // Should find the HTML in the unwrapped directory
        assertNotNull(result.entryPoint)
    }

    @Test
    fun `Three_js project with CDN scripts detected as static`() {
        File(testDir, "index.html").writeText("""
            <html>
            <head>
                <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
            </head>
            <body>
                <canvas id="game"></canvas>
                <script src="game.js"></script>
            </body>
            </html>
        """.trimIndent())
        File(testDir, "game.js").writeText("const scene = new THREE.Scene();")

        val result = analyzer.analyze(testDir, "threejs-game")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertFalse(result.requiresNode)
        assertFalse(result.needsBuild)
        assertTrue(result.isStaticServe)
    }

    @Test
    fun `Canvas game detected as static`() {
        File(testDir, "index.html").writeText("""
            <html>
            <body>
                <canvas id="game" width="800" height="600"></canvas>
                <script src="game.js"></script>
            </body>
            </html>
        """.trimIndent())
        File(testDir, "game.js").writeText("""
            const canvas = document.getElementById('game');
            const ctx = canvas.getContext('2d');
            ctx.fillRect(0, 0, 100, 100);
        """.trimIndent())

        val result = analyzer.analyze(testDir, "canvas-game")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertEquals("index.html", result.entryPoint)
        assertTrue(result.isStaticServe)
    }

    @Test
    fun `project with src directory but no package_json is static`() {
        // This is the critical case: src/ doesn't mean "needs build" if there's no package.json
        File(testDir, "index.html").writeText("""
            <html>
            <head><script src="src/main.js"></script></head>
            <body></body>
            </html>
        """.trimIndent())
        File(testDir, "src").mkdirs()
        File(testDir, "src/main.js").writeText("console.log('hello')")
        File(testDir, "src/utils.js").writeText("export function helper() {}")

        val result = analyzer.analyze(testDir, "src-static")

        assertEquals(ProjectType.STATIC_WEB, result.projectType)
        assertEquals("index.html", result.entryPoint)
        assertFalse(result.requiresNode)
        assertFalse(result.needsBuild)
        assertTrue(result.isStaticServe)
    }

    @Test
    fun `Vue project detected correctly`() {
        File(testDir, "package.json").writeText("""
            {
                "name": "vue-app",
                "dependencies": { "vue": "^3.3.0" },
                "scripts": { "build": "vue-cli-service build" }
            }
        """.trimIndent())
        File(testDir, "src").mkdirs()
        File(testDir, "src/App.vue").writeText("<template><div>Hello</div></template>")

        val result = analyzer.analyze(testDir, "vue-app")

        assertEquals(ProjectType.FRONTEND_APP, result.projectType)
        assertEquals("vue", result.framework)
        assertTrue(result.needsBuild)
    }

    @Test
    fun `prebuilt project with both source and dist uses dist`() {
        File(testDir, "package.json").writeText("""
            {
                "name": "mixed-project",
                "dependencies": { "react": "^18.0.0" },
                "scripts": { "build": "vite build" }
            }
        """.trimIndent())
        File(testDir, "index.html").writeText("<html><script type=\"module\" src=\"/src/main.jsx\"></script></html>")
        File(testDir, "src").mkdirs()
        File(testDir, "src/main.jsx").writeText("import React from 'react'")
        File(testDir, "dist").mkdirs()
        File(testDir, "dist/index.html").writeText("<html><body>Built</body></html><script src='assets/index.js'></script>")

        val result = analyzer.analyze(testDir, "mixed")

        assertEquals(ProjectType.FRONTEND_APP, result.projectType)
        assertTrue(result.hasBuildOutput)
        assertFalse(result.needsBuild)
        assertTrue(result.isStaticServe)
    }
}
