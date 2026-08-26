package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcAgent

class OpenCodeAgentManager(
    private val client: OpenCodeClient,
    private val kv: OcKvStore
) {

    @Volatile
    var current: String? = readStored()

    var cachedAgents: List<OcAgent> = emptyList()
        private set

    fun agents(): OcResult<List<OcAgent>> {
        val r = client.agents()
        r.onOk { cachedAgents = it }
        return r
    }

    fun primaryAgents(): List<OcAgent> =
        cachedAgents.filter { !it.hidden && (it.mode == null || it.mode == "primary" || it.mode == "all") }

    fun find(name: String): OcAgent? =
        cachedAgents.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun hasPlanAndBuild(): Pair<Boolean, Boolean> =
        (find("plan") != null) to (find("build") != null)

    fun togglePlanBuild(): String? {
        val planToBuild = find("plan") != null && find("build") != null
        if (!planToBuild) return null
        val next = when (current) {
            "plan" -> "build"
            else -> "plan"
        }
        selectAgent(next)
        return next
    }

    fun selectAgent(agentName: String) {
        current = agentName
        kv.putString(OpenCodeSessionStore.KEY_CURRENT_AGENT, agentName)
    }

    private fun readStored(): String? =
        kv.getString(OpenCodeSessionStore.KEY_CURRENT_AGENT)

    companion object {
        const val TAG = "Agents"
        const val DEFAULT_AGENT = "build"
    }
}
