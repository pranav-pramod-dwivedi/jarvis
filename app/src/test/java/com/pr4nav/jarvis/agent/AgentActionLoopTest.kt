package com.pr4nav.jarvis.agent

import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentActionLoopTest {

    @Before
    fun setup() {
        JarvisWorkspace.initWorkspace()
    }

    @Test
    fun testBuildVerifiedProjectLifecycle() {
        val files = mapOf(
            "calculator.py" to "def add(a, b):\n    return a + b\n\nif __name__ == '__main__':\n    print('Calc Ready')\n",
            "README.md" to "# Calculator App\nBuilt by JARVIS Agent.\n"
        )

        val result = AgentActionLoop.buildVerifiedProject(
            context = null,
            projectName = "calculator",
            files = files
        )

        assertEquals("/storage/emulated/0/JARVIS/workspace/calculator", result.projectPath)
        assertEquals(2, result.filesCreated.size)
        assertTrue(result.stepLogs.any { it.phase == "OBSERVE" })
        assertTrue(result.stepLogs.any { it.phase == "PLAN" })
        assertTrue(result.stepLogs.any { it.phase == "ACT" })
        assertTrue(result.stepLogs.any { it.phase == "VERIFY" })
    }
}
