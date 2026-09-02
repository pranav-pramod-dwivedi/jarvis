package com.pr4nav.jarvis.tools.catalog

import android.content.Intent
import android.provider.Settings
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema
import org.json.JSONObject

object ConnectivityNetworkTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "network_wifi_toggle",
            description = "Opens Wi-Fi settings to connect or toggle Wi-Fi.",
            argumentSchema = schema(prop("state", "boolean", "Optional true/false state")),
            execute = { ctx, _ ->
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("📶 Opened Wi-Fi settings.")
            }
        ))

        reg(CanonicalToolDef(
            name = "network_bluetooth_toggle",
            description = "Toggles or configures Bluetooth state.",
            argumentSchema = schema(prop("state", "boolean", "true to enable, false to disable")),
            execute = { ctx, args ->
                val state = args.optBoolean("state", true)
                CanonicalToolRegistry.execute(ctx, "system.bluetooth", JSONObject().put("state", state))
            }
        ))

        reg(CanonicalToolDef(
            name = "network_hotspot_open",
            description = "Opens Tethering & Portable Hotspot settings.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("📶 Opened Hotspot & Wireless settings.")
            }
        ))

        reg(CanonicalToolDef(
            name = "network_airplane_mode_open",
            description = "Opens Airplane Mode settings.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("✈️ Opened Airplane Mode settings.")
            }
        ))
    }
}
