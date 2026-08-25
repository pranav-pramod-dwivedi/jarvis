package com.pr4nav.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

data class TermuxResult(
    val label: String,
    val cmd: String,
    val stdout: String?,
    val stderr: String?,
    val exitCode: Int?,
    val err: Int?,
    val errmsg: String?,
    val internalError: String? = null
)

fun TermuxResult.toJson(): String = JSONObject().apply {
    put("label", label)
    put("cmd", cmd)
    put("stdout", stdout ?: "")
    put("stderr", stderr ?: "")
    put("rc", exitCode ?: -999)
    put("err", err ?: -999)
    put("errmsg", errmsg ?: "")
    put("internal_error", internalError ?: "")
}.toString()

object ResultBus {
    const val EXTRA_LABEL = "jarvis_label"
    const val EXTRA_CMD = "jarvis_cmd"

    // Exact keys defined by Termux TermuxService (termux-app repo):
    // result bundle extra key + keys inside the bundle.
    const val KEY_RESULT_BUNDLE = "result"
    const val KEY_STDOUT = "stdout"
    const val KEY_STDERR = "stderr"
    const val KEY_EXIT_CODE = "exitCode"
    const val KEY_ERR = "err"          // Activity.RESULT_OK (-1) means no internal error
    const val KEY_ERRMSG = "errmsg"

    @Volatile
    var listener: ((TermuxResult) -> Unit)? = null
}

/**
 * Manifest-declared receiver targeted by the PendingIntent handed to Termux.
 * Delivery happens under Jarvis' own identity, exported=false is sufficient.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(ResultBus.EXTRA_LABEL) ?: "?"
        val cmd = intent.getStringExtra(ResultBus.EXTRA_CMD) ?: ""
        val b: Bundle? = intent.getBundleExtra(ResultBus.KEY_RESULT_BUNDLE)
        val r = if (b == null) {
            TermuxResult(label, cmd, null, null, null, null, null,
                internalError = "result bundle missing from callback")
        } else {
            TermuxResult(
                label = label,
                cmd = cmd,
                stdout = b.getString(ResultBus.KEY_STDOUT),
                stderr = b.getString(ResultBus.KEY_STDERR),
                exitCode = if (b.containsKey(ResultBus.KEY_EXIT_CODE)) b.getInt(ResultBus.KEY_EXIT_CODE) else null,
                err = if (b.containsKey(ResultBus.KEY_ERR)) b.getInt(ResultBus.KEY_ERR) else null,
                errmsg = b.getString(ResultBus.KEY_ERRMSG) ?: ""
            )
        }
        Log.i("JARVIS", r.toJson())
        ResultBus.listener?.invoke(r)
    }
}
