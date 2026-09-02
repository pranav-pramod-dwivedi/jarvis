package com.pr4nav.jarvis.workspace

import com.pr4nav.jarvis.SessionState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JarvisWorkspaceTest {

    @Before
    fun setup() {
        JarvisWorkspace.initWorkspace()
    }

    @Test
    fun testWorkspaceConstantsAndInitialization() {
        assertEquals("/storage/emulated/0/JARVIS", JarvisWorkspace.ROOT_DIR)
        assertEquals("/storage/emulated/0/JARVIS/workspace", JarvisWorkspace.WORKSPACE_DIR)
        assertEquals("/storage/emulated/0/JARVIS/projects", JarvisWorkspace.PROJECTS_DIR)
        assertEquals("/storage/emulated/0/JARVIS/generated", JarvisWorkspace.GENERATED_DIR)
        assertEquals("/storage/emulated/0/JARVIS/downloads", JarvisWorkspace.DOWNLOADS_DIR)
        assertEquals("/storage/emulated/0/JARVIS/workspace", SessionState.dir)
    }

    @Test
    fun testPathNormalization() {
        // Relative names resolve to WORKSPACE_DIR
        assertEquals(
            "/storage/emulated/0/JARVIS/workspace/calculator",
            JarvisWorkspace.normalizePath("calculator")
        )
        assertEquals(
            "/storage/emulated/0/JARVIS/workspace/calculator",
            JarvisWorkspace.normalizePath("./calculator")
        )

        // Tilde resolves to workspace
        assertEquals(
            "/storage/emulated/0/JARVIS/workspace",
            JarvisWorkspace.normalizePath("~")
        )
        assertEquals(
            "/storage/emulated/0/JARVIS/workspace/my_app",
            JarvisWorkspace.normalizePath("~/my_app")
        )

        // Subdirectories within project
        assertEquals(
            "/storage/emulated/0/JARVIS/workspace/calculator/src/main.py",
            JarvisWorkspace.normalizePath("calculator/src/main.py")
        )

        // Forbidden /root/ attempts remapped safely to workspace
        assertEquals(
            "/storage/emulated/0/JARVIS/workspace/calculator",
            JarvisWorkspace.normalizePath("/root/calculator")
        )
    }

    @Test
    fun testWorkspaceBoundaryValidation() {
        // Allowed workspace path
        val validAccess = JarvisWorkspace.validateAccess("/storage/emulated/0/JARVIS/workspace/calculator", isWrite = true)
        assertTrue(validAccess is WorkspaceValidationResult.Allowed)

        // Forbidden root path rejected with structured violation
        val forbiddenAccess = JarvisWorkspace.validateAccess("/root/calculator", isWrite = true)
        assertTrue(forbiddenAccess is WorkspaceValidationResult.Violation)
        val violation = forbiddenAccess as WorkspaceValidationResult.Violation
        assertEquals("/root/calculator", violation.requested)
        assertEquals("/storage/emulated/0/JARVIS/workspace", violation.allowedRoot)
        assertEquals("/storage/emulated/0/JARVIS/workspace/calculator", violation.suggested)

        val errJson = violation.toErrorJson()
        assertEquals("WORKSPACE_BOUNDARY", errJson.optString("error"))
    }

    @Test
    fun testStorageTelemetry() {
        val telemetry = JarvisWorkspace.getStorageTelemetry()
        assertEquals("/storage/emulated/0/JARVIS", telemetry.optString("workspace_root"))
        assertEquals("/storage/emulated/0/JARVIS/workspace", telemetry.optString("workspace_dir"))
    }
}
