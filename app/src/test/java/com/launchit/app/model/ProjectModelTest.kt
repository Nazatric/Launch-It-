package com.launchit.app.model

import org.junit.Assert.*
import org.junit.Test

class ProjectModelTest {

    @Test
    fun `project default values are correct`() {
        val project = Project(
            name = "test-project",
            sourcePath = "/tmp/test"
        )
        assertEquals("test-project", project.name)
        assertEquals("test-project", project.displayName)
        assertEquals(ProjectType.STATIC_WEB, project.projectType)
        assertEquals("index.html", project.entryPoint)
        assertEquals(RuntimeState.IMPORTED, project.runtimeState)
        assertEquals(DependencyState.UNKNOWN, project.dependencyState)
        assertEquals(0, project.port)
        assertNull(project.errorMessage)
        assertFalse(project.requiresNode)
    }

    @Test
    fun `project with empty source path is valid`() {
        val project = Project(name = "error-project")
        assertEquals("", project.sourcePath)
        assertEquals("error-project", project.name)
    }

    @Test
    fun `project with custom values preserves them`() {
        val project = Project(
            name = "my-app",
            displayName = "My App",
            projectType = ProjectType.FRONTEND_APP,
            entryPoint = "dist/index.html",
            sourcePath = "/tmp/my-app",
            requiresNode = true,
            packageManager = "npm",
            buildScript = "build",
            startScript = "start"
        )
        assertEquals("My App", project.displayName)
        assertEquals(ProjectType.FRONTEND_APP, project.projectType)
        assertTrue(project.requiresNode)
        assertEquals("npm", project.packageManager)
        assertEquals("build", project.buildScript)
    }

    @Test
    fun `project id is generated as UUID`() {
        val p1 = Project(name = "a", sourcePath = "/a")
        val p2 = Project(name = "b", sourcePath = "/b")
        assertNotEquals(p1.id, p2.id)
        assertTrue(p1.id.isNotBlank())
    }

    @Test
    fun `project copy preserves unchanged fields`() {
        val original = Project(
            name = "original",
            displayName = "Original",
            sourcePath = "/tmp/original"
        )
        val modified = original.copy(displayName = "Modified")
        assertEquals(original.id, modified.id)
        assertEquals(original.name, modified.name)
        assertEquals("Modified", modified.displayName)
        assertEquals(original.sourcePath, modified.sourcePath)
    }

    @Test
    fun `runtime state transitions are valid enum values`() {
        val states = RuntimeState.entries
        assertTrue(states.contains(RuntimeState.IMPORTED))
        assertTrue(states.contains(RuntimeState.ANALYZING))
        assertTrue(states.contains(RuntimeState.PREPARING))
        assertTrue(states.contains(RuntimeState.BUILDING))
        assertTrue(states.contains(RuntimeState.STARTING))
        assertTrue(states.contains(RuntimeState.READY))
        assertTrue(states.contains(RuntimeState.RUNNING))
        assertTrue(states.contains(RuntimeState.STOPPING))
        assertTrue(states.contains(RuntimeState.STOPPED))
        assertTrue(states.contains(RuntimeState.FAILED))
        assertTrue(states.contains(RuntimeState.CRASHED))
        assertEquals(11, states.size)
    }

    @Test
    fun `project types cover all expected categories`() {
        val types = ProjectType.entries
        assertTrue(types.contains(ProjectType.STATIC_WEB))
        assertTrue(types.contains(ProjectType.FRONTEND_APP))
        assertTrue(types.contains(ProjectType.NODE_SERVER))
        assertTrue(types.contains(ProjectType.FULL_STACK))
        assertTrue(types.contains(ProjectType.UNKNOWN))
        assertEquals(5, types.size)
    }

    @Test
    fun `dependency state enum values are complete`() {
        val states = DependencyState.entries
        assertTrue(states.contains(DependencyState.UNKNOWN))
        assertTrue(states.contains(DependencyState.NONE))
        assertTrue(states.contains(DependencyState.INSTALLING))
        assertTrue(states.contains(DependencyState.INSTALLED))
        assertTrue(states.contains(DependencyState.FAILED))
        assertTrue(states.contains(DependencyState.STALE))
        assertEquals(6, states.size)
    }
}
