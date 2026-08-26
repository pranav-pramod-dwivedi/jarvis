package com.pr4nav.jarvis.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject
import kotlin.concurrent.thread

object LocationCapability : Capability {

    override val name = "location"

    private fun lm(ctx: Context = Capabilities.require()): LocationManager =
        ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasCoarse(): Boolean =
        ContextCompat.checkSelfPermission(Capabilities.require(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun hasFine(): Boolean =
        ContextCompat.checkSelfPermission(Capabilities.require(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(): List<String> = try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { safeEnabled(it) }
    } catch (_: Exception) { emptyList() }

    private fun safeEnabled(p: String): Boolean = try { lm().isProviderEnabled(p) } catch (_: Exception) { false }

    /** One-shot fix. No continuous tracking. */
    fun current(timeoutMs: Long = 20_000): CapabilityResult {
        if (!hasFine() && !hasCoarse())
            return CapabilityResult.fail("Location permission not granted — grant it in JARVIS → PERMISSIONS")
        if (enabledProviders().isEmpty())
            return CapabilityResult.fail("No location provider is enabled — turn on location in system settings")

        val latch = java.util.concurrent.CountDownLatch(1)
        var best: Location? = null

        fun consider(l: Location?) {
            if (l == null) return
            synchronized(this) {
                val b = best
                if (b == null || l.accuracy < b.accuracy) best = l
            }
        }

        val listener = LocationListener { l -> consider(l); latch.countDown() }
        val ctx = Capabilities.require()
        val manager = lm()
        var requested = false
        try {
            for (p in enabledProviders()) {
                if (p == LocationManager.PASSIVE_PROVIDER) continue
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        manager.getCurrentLocation(p, null, ctx.mainExecutor) { l ->
                            consider(l); latch.countDown()
                        }
                        requested = true
                    } else {
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(p, listener, Looper.getMainLooper())
                        requested = true
                    }
                } catch (_: Exception) {}
            }
        } catch (e: SecurityException) {
            return CapabilityResult.fail("Location permission revoked: ${e.message}")
        }

        try {
            if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                lastKnown()?.let { lk ->
                    consider(lk)
                }
            }
        } finally {
            try { manager.removeUpdates(listener) } catch (_: Exception) {}
        }

        val loc = best ?: lastKnown()
            ?: return CapabilityResult.fail(
                "No location fix within ${timeoutMs}ms${if (!requested) " (no provider accepted the request)" else ""}"
            )

        return CapabilityResult.ok(
            JSONObject().put("latitude", loc.latitude)
                .put("longitude", loc.longitude)
                .put("accuracyMeters", loc.accuracy.toDouble())
                .put("timestamp", loc.time)
                .put("provider", loc.provider)
                .toString(),
            "backgroundPermitted" to hasBackground().toString()
        )
    }

    fun lastKnown(): Location? = try {
        enabledProviders().mapNotNull { p ->
            try {
                @Suppress("MissingPermission") lm().getLastKnownLocation(p)
            } catch (_: SecurityException) { null }
        }.minByOrNull { it.accuracy }
    } catch (_: Exception) { null }

    private fun hasBackground(): Boolean =
        Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(Capabilities.require(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    override fun available(): Boolean = true
    override fun permitted(): Boolean = hasCoarse() || hasFine()

    override fun status(): String = when {
        !permitted() -> "✗ Location — permission not granted"
        enabledProviders().isEmpty() -> "△ Location — granted but all providers off"
        else -> "✓ Location — one-shot fixes ready (background=${hasBackground()})"
    }

    override fun tools() = listOf(
        ToolDef("location.current", "Get one current location fix (lat/lon/accuracy/time)", """{"timeoutMs":20000}""",
            { if (Capabilities.app != null && !permitted()) "location permission not granted" else null },
            { a -> current(a.optLong("timeoutMs", 20_000)).envelope() })
    )
}
