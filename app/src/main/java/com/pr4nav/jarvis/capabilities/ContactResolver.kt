package com.pr4nav.jarvis.capabilities

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils

data class ContactMatch(
    val id: String,
    val name: String,
    val number: String,
    val typeLabel: String = "Mobile"
)

sealed class ContactResolutionResult {
    data class Single(val contact: ContactMatch) : ContactResolutionResult()
    data class Ambiguous(val matches: List<ContactMatch>) : ContactResolutionResult()
    object NotFound : ContactResolutionResult()
    object PermissionRequired : ContactResolutionResult()
}

/**
 * Resolves contact queries (name, relationship, partial name, phone number) against
 * the device Contacts Provider via ContactsContract.
 */
object ContactResolver {

    fun resolve(context: Context, query: String): ContactResolutionResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ContactResolutionResult.NotFound

        // 1. Direct phone number check
        if (isPhoneNumber(trimmed)) {
            val normalizedNumber = trimmed.replace(Regex("[^0-9+]"), "")
            return ContactResolutionResult.Single(
                ContactMatch(
                    id = "direct_number",
                    name = trimmed,
                    number = normalizedNumber,
                    typeLabel = "Direct"
                )
            )
        }

        // 2. Check READ_CONTACTS permission
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return ContactResolutionResult.PermissionRequired
        }

        // 3. Query Contacts Provider
        val matches = ArrayList<ContactMatch>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$trimmed%")

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null) {
                val idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getString(idCol) ?: "" else ""
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                    val num = if (numCol >= 0) cursor.getString(numCol) ?: "" else ""
                    val typeInt = if (typeCol >= 0) cursor.getInt(typeCol) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    val label = when (typeInt) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        else -> "Mobile"
                    }
                    if (num.isNotBlank()) {
                        matches.add(ContactMatch(id, name, num, label))
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }

        if (matches.isEmpty()) {
            return ContactResolutionResult.NotFound
        }

        // Exact name matches take priority
        val exactMatches = matches.filter { it.name.equals(trimmed, ignoreCase = true) }
        if (exactMatches.size == 1) {
            return ContactResolutionResult.Single(exactMatches[0])
        } else if (exactMatches.size > 1) {
            return ContactResolutionResult.Ambiguous(exactMatches)
        }

        return if (matches.size == 1) {
            ContactResolutionResult.Single(matches[0])
        } else {
            ContactResolutionResult.Ambiguous(matches)
        }
    }

    private fun isPhoneNumber(s: String): Boolean {
        val digits = s.count { it.isDigit() }
        return digits >= 7 && s.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }
    }
}
