package com.pr4nav.jarvis.registry

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pr4nav.jarvis.router.JarvisIntentRouter

object NavCommDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        // Navigation
        CapabilityDef(
            id = "navigation.maps.open",
            category = "navigation",
            name = "Open Google Maps",
            description = "Launch the Google Maps application",
            aliases = listOf("open maps", "open google maps", "launch maps", "start maps"),
            execute = { ctx, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    ctx.startActivity(intent)
                    CapabilityExecutionResult.ok("🗺️ Opened Google Maps.")
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    CapabilityExecutionResult.ok("🗺️ Opened Google Maps on web.")
                }
            }
        ),

        CapabilityDef(
            id = "navigation.route",
            category = "navigation",
            name = "Navigate to Destination",
            description = "Start turn-by-turn navigation in Google Maps",
            aliases = listOf("navigate to", "take me to", "take me home", "directions to", "drive to"),
            optionalParams = listOf("destination"),
            execute = { ctx, params ->
                val dest = (params["destination"] as? String) ?: "home"
                val uri = Uri.parse("google.navigation:q=${Uri.encode(dest)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    ctx.startActivity(intent)
                    CapabilityExecutionResult.ok("🗺️ Navigating to $dest.")
                } catch (_: Exception) {
                    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(dest)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(web)
                    CapabilityExecutionResult.ok("🗺️ Opening navigation to $dest.")
                }
            }
        ),

        CapabilityDef(
            id = "navigation.search_nearby",
            category = "navigation",
            name = "Search Nearby Places",
            description = "Search nearby amenities (e.g. petrol pump, hospital, restaurants)",
            aliases = listOf("find nearest", "search nearby", "nearest petrol station", "nearest hospital"),
            optionalParams = listOf("query"),
            execute = { ctx, params ->
                val q = (params["query"] as? String) ?: "petrol station"
                val uri = Uri.parse("geo:0,0?q=${Uri.encode(q)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("📍 Searching for nearest $q.")
            }
        ),

        // Communication
        CapabilityDef(
            id = "comm.whatsapp.open",
            category = "communication",
            name = "Open WhatsApp",
            description = "Launch WhatsApp messenger",
            aliases = listOf("open whatsapp", "launch whatsapp", "start whatsapp"),
            execute = { ctx, _ ->
                val pm = ctx.packageManager
                val intent = pm.getLaunchIntentForPackage("com.whatsapp")
                if (intent != null) {
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    CapabilityExecutionResult.ok("💬 WhatsApp opened.")
                } else {
                    CapabilityExecutionResult.fail("WhatsApp is not installed on this device.")
                }
            }
        ),

        CapabilityDef(
            id = "comm.whatsapp.message",
            category = "communication",
            name = "Compose WhatsApp Message",
            description = "Open WhatsApp conversation or compose message",
            aliases = listOf("message on whatsapp", "text on whatsapp", "send whatsapp to"),
            optionalParams = listOf("contact", "text"),
            execute = { ctx, params ->
                val contact = (params["contact"] as? String) ?: ""
                val text = (params["text"] as? String) ?: ""
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(text)}")
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    ctx.startActivity(intent)
                    CapabilityExecutionResult.ok("💬 Composing WhatsApp message to $contact: \"$text\".")
                } catch (e: Exception) {
                    CapabilityExecutionResult.fail("WhatsApp unavailable: ${e.message}")
                }
            }
        ),

        CapabilityDef(
            id = "comm.telegram.open",
            category = "communication",
            name = "Open Telegram",
            description = "Launch Telegram messenger",
            aliases = listOf("open telegram", "launch telegram"),
            execute = { ctx, _ ->
                val pm = ctx.packageManager
                val intent = pm.getLaunchIntentForPackage("org.telegram.messenger")
                if (intent != null) {
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    CapabilityExecutionResult.ok("✈️ Telegram opened.")
                } else {
                    CapabilityExecutionResult.fail("Telegram is not installed.")
                }
            }
        ),

        CapabilityDef(
            id = "comm.gmail.open",
            category = "communication",
            name = "Open Gmail",
            description = "Launch the Gmail email client",
            aliases = listOf("open gmail", "launch gmail", "check email", "read my email", "open email"),
            execute = { ctx, _ ->
                val pm = ctx.packageManager
                val intent = pm.getLaunchIntentForPackage("com.google.android.gm")
                if (intent != null) {
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    CapabilityExecutionResult.ok("📧 Gmail opened.")
                } else {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    CapabilityExecutionResult.ok("📧 Opening Gmail on web.")
                }
            }
        ),

        CapabilityDef(
            id = "comm.gmail.compose",
            category = "communication",
            name = "Compose Email",
            description = "Open email compose screen with recipient and body",
            aliases = listOf("compose email", "send an email to", "write email"),
            optionalParams = listOf("to", "subject", "body"),
            execute = { ctx, params ->
                val to = (params["to"] as? String) ?: ""
                val sub = (params["subject"] as? String) ?: ""
                val body = (params["body"] as? String) ?: ""
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:${Uri.encode(to)}")
                    putExtra(Intent.EXTRA_SUBJECT, sub)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("✉️ Composing email to $to.")
            }
        ),

        CapabilityDef(
            id = "comm.messages.open",
            category = "communication",
            name = "Open SMS Messages",
            description = "Launch default text messaging application",
            aliases = listOf("open messages", "open sms", "read my messages"),
            execute = { ctx, _ ->
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MESSAGING)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("💬 Messages app opened.")
            }
        ),

        CapabilityDef(
            id = "comm.sms.compose",
            category = "communication",
            name = "Compose SMS Message",
            description = "Open SMS message composer",
            aliases = listOf("text", "send a text to", "sms to"),
            optionalParams = listOf("contact", "phone", "message"),
            execute = { ctx, params ->
                val phone = (params["phone"] as? String) ?: (params["contact"] as? String) ?: ""
                val msg = (params["message"] as? String) ?: ""
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("sms:$phone")
                    putExtra("sms_body", msg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                com.pr4nav.jarvis.capabilities.Android16SafeLauncher.startActivitySafe(ctx, intent)
                CapabilityExecutionResult.ok("💬 Composing SMS to $phone.")
            }
        ),

        CapabilityDef(
            id = "comm.phone.dial",
            category = "communication",
            name = "Phone Call / Dialer",
            description = "Initiate phone call or open dialer with contact/number resolved",
            aliases = listOf("dial", "call", "phone", "open dialer"),
            optionalParams = listOf("number", "target"),
            execute = { ctx, params ->
                val num = (params["number"] as? String) ?: (params["target"] as? String) ?: ""
                val res = com.pr4nav.jarvis.capabilities.PhoneCallManager.placeCall(ctx, num)
                if (res.success) {
                    CapabilityExecutionResult.ok(res.message)
                } else {
                    CapabilityExecutionResult.fail(res.message)
                }
            }
        ),

        CapabilityDef(
            id = "comm.contacts.open",
            category = "communication",
            name = "Open Contacts",
            description = "Launch the Android Contacts app",
            aliases = listOf("open contacts", "show contacts", "contacts"),
            execute = { ctx, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                com.pr4nav.jarvis.capabilities.Android16SafeLauncher.startActivitySafe(ctx, intent)
                CapabilityExecutionResult.ok("👥 Contacts opened.")
            }
        )
    )
}
