package com.pr4nav.jarvis.capabilities

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject

object DeviceCapability : Capability {

    override val name = "device"

    private var torchCallback: CameraManager.TorchCallback? = null
    @Volatile private var torchOn: Boolean? = null

    fun info(): CapabilityResult {
        val ctx = Capabilities.require()
        val b = battery()
        return CapabilityResult.ok(
            JSONObject()
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("brand", Build.BRAND)
                .put("device", Build.DEVICE)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
                .put("bootCount", try { Settings.getBootCount() } catch (_: Exception) { -1 })
                .put("batteryLevel", b.first)
                .put("charging", b.second)
                .toString()
        )
    }

    private object Settings {
        fun getBootCount(): Int =
            android.provider.Settings.Global.getInt(
                Capabilities.require().contentResolver,
                android.provider.Settings.Global.BOOT_COUNT, -1
            )
    }

    fun battery(): Pair<Int, Boolean> {
        val ctx = Capabilities.require()
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1 to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0) (level * 100) / scale else -1
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return pct to plugged
    }

    fun vibrate(ms: Long): CapabilityResult = try {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (Capabilities.require().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            Capabilities.require().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) CapabilityResult.fail("This device has no vibrator")
        else {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
            CapabilityResult.ok(JSONObject().put("vibrated", ms).toString())
        }
    } catch (e: Exception) { CapabilityResult.fail(e.message ?: "vibrate failed") }

    fun torch(on: Boolean): CapabilityResult = try {
        val cm = Capabilities.require().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        } ?: return CapabilityResult.fail("No flash-capable rear camera found")
        cm.setTorchMode(camId, on)
        torchOn = on
        CapabilityResult.ok(JSONObject().put("torch", if (on) "on" else "off").toString())
    } catch (e: Exception) { CapabilityResult.fail("Torch failed: ${e.message}") }

    fun torchStatus(): CapabilityResult = CapabilityResult.ok(
        JSONObject().put("torch", torchOn?.let { if (it) "on" else "off" } ?: "unknown").toString()
    )

    fun setAlarm(hour: Int?, minute: Int?, label: String?): CapabilityResult = try {
        val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            hour?.let { putExtra(android.provider.AlarmClock.EXTRA_HOUR, it) }
            minute?.let { putExtra(android.provider.AlarmClock.EXTRA_MINUTES, it) }
            label?.takeIf { it.isNotBlank() }?.let { putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
        }
        Capabilities.require().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        CapabilityResult.ok(JSONObject().put("alarm", "$hour:${minute ?: 0} '$label'").toString())
    } catch (e: Exception) { CapabilityResult.fail("No alarm app available: ${e.message}") }

    fun setTimer(seconds: Int, label: String?): CapabilityResult = try {
        val i = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            label?.takeIf { it.isNotBlank() }?.let { putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
        }
        Capabilities.require().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        CapabilityResult.ok(JSONObject().put("timer", seconds).put("label", label ?: "").toString())
    } catch (e: Exception) { CapabilityResult.fail("No timer app available: ${e.message}") }

    override fun available(): Boolean = true
    override fun permitted(): Boolean = true

    override fun status(): String {
        val b = try { battery() } catch (_: Exception) { -1 to false }
        return "✓ Device — ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · battery ${b.first}%"
    }

    override fun tools() = listOf(
        ToolDef("device.info", "Device model/OS/battery snapshot", "{}", null,
            { _ -> info().envelope() }),
        ToolDef("device.battery", "Battery level and charging state", "{}", null,
            {
                val b = battery()
                CapabilityResult.ok(JSONObject().put("level", b.first).put("charging", b.second).toString()).envelope()
            }),
        ToolDef("device.vibrate", "Vibrate for N milliseconds", """{"ms":300}""", null,
            { a -> vibrate(a.optLong("ms", 300)).envelope() }),
        ToolDef("device.torch", "Turn the flashlight on/off", """{"on":true}""", null,
            { a -> torch(a.optBoolean("on", true)).envelope() }),
        ToolDef("device.alarm", "Set an alarm via the system clock app", """{"hour":7,"minute":30,"label":"wake"}""",
            null,
            {
                setAlarm(
                    if (it.has("hour")) it.getInt("hour") else null,
                    if (it.has("minute")) it.getInt("minute") else null,
                    it.optString("label", "")
                ).envelope()
            }),
        ToolDef("device.timer", "Start a countdown timer", """{"seconds":60,"label":"tea"}""", null,
            { a -> setTimer(a.optInt("seconds", 60), a.optString("label", "")).envelope() })
    )
}
