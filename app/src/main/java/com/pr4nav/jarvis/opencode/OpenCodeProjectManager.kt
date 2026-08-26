package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcProject
import java.util.concurrent.CopyOnWriteArrayList

class OpenCodeProjectManager(
    private val client: OpenCodeClient,
    private val kv: OcKvStore
) {

    @Volatile
    private var cachedCurrentDirectory: String? = null

    @Volatile
    private var currentFetched: Boolean = false

    val recents = CopyOnWriteArrayList<String>()

    init {
        loadRecents()
    }

    fun currentDirectory(): String? {
        if (!currentFetched) {
            cachedCurrentDirectory = client.currentProject().getOrNull()?.worktree
            currentFetched = true
        }
        return cachedCurrentDirectory ?: currentDirectoryFallback()
    }

    fun refreshCurrent(): OcResult<String?> {
        val r = client.currentProject()
        r.onOk {
            cachedCurrentDirectory = it?.worktree
            currentFetched = true
            it?.worktree?.let { d -> selectDirectory(d) }
        }
        return r.map { it?.worktree }
    }

    private fun currentDirectoryFallback(): String? = recents.firstOrNull()

    fun projects(): OcResult<List<OcProject>> {
        val r = client.projects()
        r.onOk { list ->
            list.forEach { p -> p.worktree?.let { remember(it) } }
            persistRecents()
        }
        return r
    }

    fun selectDirectory(directory: String) {
        val clean = directory.trimEnd('/')
        cachedCurrentDirectory = clean
        currentFetched = true
        remember(clean)
        persistRecents()
    }

    fun isGitWorktree(path: String): Boolean {
        val dotGit = java.io.File(path, ".git")
        if (!dotGit.exists()) return false
        if (dotGit.isDirectory) return true
        return try {
            dotGit.readText().contains("gitdir:")
        } catch (_: Exception) {
            false
        }
    }

    private fun remember(dir: String) {
        recents.remove(dir)
        recents.add(0, dir)
        while (recents.size > 12) recents.removeAt(recents.size - 1)
    }

    private fun loadRecents() {
        try {
            val raw = kv.getString(OpenCodeSessionStore.KEY_KNOWN_PROJECTS) ?: return
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) arr.optString(i)?.let { recents.add(it) }
        } catch (_: Exception) {
        }
    }

    private fun persistRecents() {
        runCatching {
            kv.putString(
                OpenCodeSessionStore.KEY_KNOWN_PROJECTS,
                org.json.JSONArray(recents.toList()).toString()
            )
        }
    }
}
