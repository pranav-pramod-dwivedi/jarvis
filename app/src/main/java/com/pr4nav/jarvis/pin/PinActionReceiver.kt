package com.pr4nav.jarvis.pin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pr4nav.jarvis.MainActivity

class PinActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PinActionReceiver"

        const val ACTION_PIN_DONE = "com.pr4nav.jarvis.pin.ACTION_PIN_DONE"
        const val ACTION_PIN_SNOOZE = "com.pr4nav.jarvis.pin.ACTION_PIN_SNOOZE"
        const val ACTION_PIN_TALK = "com.pr4nav.jarvis.pin.ACTION_PIN_TALK"
        const val ACTION_ESCALATE_TICK = "com.pr4nav.jarvis.pin.ACTION_ESCALATE_TICK"

        const val ACTION_CHECKIN_KICKOFF = "com.pr4nav.jarvis.pin.ACTION_CHECKIN_KICKOFF"
        const val ACTION_CHECKIN_MID = "com.pr4nav.jarvis.pin.ACTION_CHECKIN_MID"
        const val ACTION_CHECKIN_PRE_SLEEP = "com.pr4nav.jarvis.pin.ACTION_CHECKIN_PRE_SLEEP"

        const val EXTRA_PIN_ID = "extra_pin_id"
        const val EXTRA_MINUTES = "extra_minutes"
        const val EXTRA_PROMPT = "extra_prompt"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val pinId = intent.getStringExtra(EXTRA_PIN_ID) ?: ""

        Log.i(TAG, "Pin action received: $action for pin [$pinId]")

        when (action) {
            ACTION_PIN_DONE -> {
                if (pinId.isNotBlank()) {
                    StickyPinManager.acknowledgePin(context, pinId)
                }
            }
            ACTION_PIN_SNOOZE -> {
                if (pinId.isNotBlank()) {
                    val mins = intent.getIntExtra(EXTRA_MINUTES, 15)
                    StickyPinManager.snoozePin(context, pinId, mins)
                }
            }
            ACTION_PIN_TALK -> {
                val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: "check in"
                try {
                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("from_pin_talk", true)
                        putExtra("prompt", prompt)
                    }
                    context.startActivity(mainIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch main activity for talk: ${e.message}")
                }
            }
            ACTION_ESCALATE_TICK -> {
                if (pinId.isNotBlank()) {
                    StickyPinManager.escalatePin(context, pinId)
                }
            }
            ACTION_CHECKIN_KICKOFF -> {
                StickyPinManager.handleKickoffCheckin(context)
            }
            ACTION_CHECKIN_MID -> {
                StickyPinManager.handleMidSessionCheckin(context)
            }
            ACTION_CHECKIN_PRE_SLEEP -> {
                StickyPinManager.handlePreSleepCheckin(context)
            }
        }
    }
}
