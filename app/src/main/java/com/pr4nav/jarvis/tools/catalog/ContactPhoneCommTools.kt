package com.pr4nav.jarvis.tools.catalog

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.fail
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema
import org.json.JSONObject

object ContactPhoneCommTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "contact_find",
            description = "Searches for a contact by name and retrieves their details.",
            argumentSchema = schema(
                prop("name", "string", "Contact name to search for"),
                required = listOf("name")
            ),
            execute = { ctx, args ->
                val name = args.optString("name", "")
                CanonicalToolRegistry.execute(ctx, "contacts.find", JSONObject().put("query", name))
            }
        ))

        reg(CanonicalToolDef(
            name = "contact_add",
            description = "Creates a new contact card with name, phone, and optional email.",
            argumentSchema = schema(
                prop("name", "string", "Full name of contact"),
                prop("phone", "string", "Phone number"),
                prop("email", "string", "Optional email address"),
                required = listOf("name", "phone")
            ),
            execute = { ctx, args ->
                val name = args.optString("name", "")
                val phone = args.optString("phone", "")
                val email = args.optString("email", "")
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    type = ContactsContract.Contacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.NAME, name)
                    putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                    if (email.isNotBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                ok("👤 Opened contact card to save $name ($phone).")
            }
        ))

        reg(CanonicalToolDef(
            name = "contact_open_app",
            description = "Opens the Contacts application.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Contacts") {}
                ok("▶️ Opening Contacts.")
            }
        ))

        reg(CanonicalToolDef(
            name = "phone_call_contact",
            description = "Initiates a phone call to a contact by name or phone number.",
            argumentSchema = schema(
                prop("target", "string", "Contact name or phone number"),
                required = listOf("target")
            ),
            execute = { ctx, args ->
                val target = args.optString("target", "")
                val res = com.pr4nav.jarvis.capabilities.PhoneCallManager.placeCall(ctx, target)
                if (res.success) {
                    ok(res.message, mapOf("target" to res.contactName, "number" to res.phoneNumber, "method" to res.method))
                } else {
                    fail(res.status, res.message)
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "phone_dial_number",
            description = "Opens the phone dialer prefilled with a phone number.",
            argumentSchema = schema(
                prop("number", "string", "Phone number to dial"),
                required = listOf("number")
            ),
            execute = { ctx, args ->
                val num = args.optString("number", "")
                val res = com.pr4nav.jarvis.capabilities.PhoneCallManager.dialNumber(ctx, num)
                if (res.success) {
                    ok(res.message, mapOf("number" to res.phoneNumber))
                } else {
                    fail(res.status, res.message)
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "phone_open_app",
            description = "Opens the Phone / Dialer app.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Phone") {}
                ok("▶️ Opening Phone.")
            }
        ))

        reg(CanonicalToolDef(
            name = "message_send_sms",
            description = "Sends an SMS message to a contact or phone number.",
            argumentSchema = schema(
                prop("recipient", "string", "Contact name or phone number"),
                prop("message", "string", "Text body of the message"),
                required = listOf("recipient", "message")
            ),
            execute = { ctx, args ->
                val r = args.optString("recipient", "")
                val m = args.optString("message", "")
                JarvisIntentRouter.routeAndExecute(ctx, "Send message to $r saying $m") {}
                ok("💬 Sending message to $r: \"$m\".", mapOf("recipient" to r, "message" to m))
            }
        ))

        reg(CanonicalToolDef(
            name = "message_send_whatsapp",
            description = "Sends a message via WhatsApp.",
            argumentSchema = schema(
                prop("recipient", "string", "Contact name or phone number"),
                prop("message", "string", "Message text"),
                required = listOf("message")
            ),
            execute = { ctx, args ->
                val m = args.optString("message", "")
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage("com.whatsapp")
                        putExtra(Intent.EXTRA_TEXT, m)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    ok("💬 Opening WhatsApp to send: \"$m\".")
                } catch (_: Exception) {
                    CatalogSchemaHelper.fail("WHATSAPP_ERROR", "WhatsApp is not installed on this device")
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "message_open_app",
            description = "Opens the default Messages application.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Messages") {}
                ok("▶️ Opening Messages.")
            }
        ))

        reg(CanonicalToolDef(
            name = "message_share_text",
            description = "Opens the Android share sheet to share text with any messaging app.",
            argumentSchema = schema(
                prop("text", "string", "Text to share"),
                required = listOf("text")
            ),
            execute = { ctx, args ->
                val text = args.optString("text", "")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                val chooser = Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
                ok("📤 Opening share sheet for text.")
            }
        ))
    }
}
