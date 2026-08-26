package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcModelRef
import com.pr4nav.jarvis.opencode.json.OcProvider

class OpenCodeModelManager(
    private val client: OpenCodeClient,
    private val kv: OcKvStore
) {

    data class Catalog(
        val providers: List<OcProvider>,
        val fetchedAtMs: Long
    )

    @Volatile
    private var cache: Catalog? = null
    private val refreshLock = Object()
    private val ttlMs: Long = 10 * 60_000

    @Volatile
    var current: OcModelRef? = readStored()

    fun catalog(maxAgeMs: Long = ttlMs): OcResult<Catalog> {
        cache?.let { c ->
            if (System.currentTimeMillis() - c.fetchedAtMs <= maxAgeMs) {
                return OcResult.ok(c)
            }
        }
        synchronized(refreshLock) {
            cache?.let { c ->
                if (System.currentTimeMillis() - c.fetchedAtMs <= maxAgeMs) {
                    return OcResult.ok(c)
                }
            }
            return when (val r = client.providers()) {
                is OcResult.Err -> {
                    cache?.let { return OcResult.ok(it) }
                    r
                }
                is OcResult.Ok -> {
                    val c = Catalog(r.value, System.currentTimeMillis())
                    cache = c
                    OcResult.ok(c)
                }
            }
        }
    }

    fun invalidateCache() {
        cache = null
    }

    fun allModels(): List<Pair<OcProvider, List<String>>> =
        catalog().getOrNull()?.providers?.map { p ->
            p to p.models.map { it.id }
        } ?: emptyList()

    fun variantsOf(providerId: String, modelId: String): List<String> =
        catalog().getOrNull()?.providers
            ?.firstOrNull { it.id == providerId }
            ?.models?.firstOrNull { it.id == modelId }
            ?.variants ?: emptyList()

    fun isValid(ref: OcModelRef): Boolean =
        catalog().getOrNull()?.providers
            ?.firstOrNull { it.id == ref.providerID }
            ?.models?.any { it.id == ref.modelID } == true

    fun selectModel(ref: OcModelRef) {
        current = ref
        kv.putString(
            OpenCodeSessionStore.KEY_CURRENT_MODEL,
            "${ref.providerID}/${ref.modelID}" + (ref.variant?.let { ":$it" } ?: "")
        )
    }

    private fun readStored(): OcModelRef? {
        val raw = kv.getString(OpenCodeSessionStore.KEY_CURRENT_MODEL) ?: return null
        val parts = raw.split(":", "/", limit = 3)
        return when {
            parts.size >= 3 && raw.contains(":") && raw.contains("/") -> {
                val slashIdx = raw.indexOf('/')
                val colonIdx = raw.indexOf(':')
                if (colonIdx > slashIdx) {
                    OcModelRef(raw.substring(0, slashIdx), raw.substring(slashIdx + 1, colonIdx), raw.substring(colonIdx + 1))
                } else null
            }
            raw.contains('/') -> {
                val idx = raw.indexOf('/')
                OcModelRef(raw.substring(0, idx), raw.substring(idx + 1))
            }
            else -> null
        }
    }

    companion object {
        const val TAG = "Models"
    }
}
