package com.agent.arena

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * UI 를 거치지 않는 시스템/앱 API 실행기.
 * - 접근성 서비스 없이도 동작한다(앱 컨텍스트로 실행).
 * - QUIET 액션은 화면이 바뀌지 않으므로 실행 후 화면 덤프/대기가 필요 없다.
 */
object DeviceApi {

    /** 화면을 바꾸지 않고 즉시 끝나는 액션 (실행 결과 read-back 으로 검증 가능). */
    private val QUIET = setOf(
        "set_volume", "set_brightness", "torch", "ringer_mode", "dnd",
        "media", "clipboard_set", "status", "set_alarm", "set_timer",
    )

    /** 다른 앱/설정 화면을 여는 액션 (실행 후 화면 확인이 필요). */
    private val OPENERS = setOf("open_settings", "intent", "dial", "sms_compose", "web_search", "maps")

    fun handles(action: String) = action in QUIET || action in OPENERS
    fun isQuiet(action: String) = action in QUIET

    fun execute(ctx: Context, c: JSONObject): String {
        val a = c.optString("action").lowercase().trim()
        return try {
            when (a) {
                "set_volume" -> setVolume(ctx, c)
                "set_brightness" -> setBrightness(ctx, c)
                "torch" -> torch(ctx, c)
                "ringer_mode" -> ringerMode(ctx, c)
                "dnd" -> dnd(ctx, c)
                "media" -> media(ctx, c)
                "clipboard_set" -> clipboardSet(ctx, c)
                "status" -> snapshot(ctx)
                "set_alarm" -> setAlarm(ctx, c)
                "set_timer" -> setTimer(ctx, c)
                "open_settings" -> openSettings(ctx, c)
                "intent" -> genericIntent(ctx, c)
                "dial" -> dial(ctx, c)
                "sms_compose" -> smsCompose(ctx, c)
                "web_search" -> webSearch(ctx, c)
                "maps" -> maps(ctx, c)
                else -> "error: unknown api action '$a'"
            }
        } catch (e: ActivityNotFoundException) {
            "error: no installed app can handle this request"
        } catch (e: SecurityException) {
            "error: permission denied (${e.message})"
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ volume

    private fun streamOf(name: String) = when (name.lowercase().trim()) {
        "ring", "ringtone" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        "call", "voice" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    private fun streamLabel(s: Int) = when (s) {
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_ALARM -> "alarm"
        AudioManager.STREAM_NOTIFICATION -> "notification"
        AudioManager.STREAM_SYSTEM -> "system"
        AudioManager.STREAM_VOICE_CALL -> "call"
        else -> "music"
    }

    private fun volumeText(am: AudioManager, s: Int): String {
        val max = am.getStreamMaxVolume(s)
        val cur = am.getStreamVolume(s)
        val pct = if (max > 0) cur * 100 / max else 0
        return "${streamLabel(s)}=$cur/$max($pct%)"
    }

    /** stream: music|ring|alarm|notification|system|call, 그리고 level(0-100%) | delta(칸) | delta_pct | max | mute 중 하나. */
    private fun setVolume(ctx: Context, c: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = streamOf(c.optString("stream"))
        val max = am.getStreamMaxVolume(s)
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(s) else 0
        val cur = am.getStreamVolume(s)
        val target: Int = when {
            c.optBoolean("mute") -> min
            c.optBoolean("max") -> max
            c.has("level") -> (c.optDouble("level") * max / 100.0).roundToInt()
            c.has("delta_pct") -> {
                val d = c.optDouble("delta_pct") * max / 100.0
                cur + (if (d > 0) ceil(d) else floor(d)).toInt()
            }
            c.has("delta") -> cur + c.optInt("delta")
            else -> return "error: set_volume needs one of level(0-100) / delta / delta_pct / max / mute"
        }.coerceIn(min, max)
        am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)
        val now = am.getStreamVolume(s)
        val note = if (now != target) " (device clamped from $target)" else ""
        return "ok: volume ${streamLabel(s)} $cur->$now of $max (${if (max > 0) now * 100 / max else 0}%)$note"
    }

    // -------------------------------------------------------------- brightness

    /** level(1-100%) | delta(%) | auto:true. WRITE_SETTINGS 권한 필요. */
    private fun setBrightness(ctx: Context, c: JSONObject): String {
        if (!Settings.System.canWrite(ctx)) {
            start(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return "error: permission needed - 'Modify system settings' for Arena Agent (settings screen opened). Use need_user, then retry."
        }
        val cr = ctx.contentResolver
        if (c.optBoolean("auto")) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "ok: adaptive brightness on"
        }
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val pct: Int = when {
            c.has("level") -> c.optInt("level")
            c.has("delta") -> cur * 100 / 255 + c.optInt("delta")
            else -> return "error: set_brightness needs level(1-100) / delta / auto"
        }.coerceIn(1, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(1, 255))
        return "ok: brightness ${cur * 100 / 255}% -> $pct%"
    }

    // ------------------------------------------------------------------- torch

    @Volatile private var torchOn = false
    private var torchCallbackSet = false

    private fun ensureTorchCallback(cm: CameraManager) {
        if (torchCallbackSet) return
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
        }, null)
        torchCallbackSet = true
    }

    private fun torch(ctx: Context, c: JSONObject): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        ensureTorchCallback(cm)
        val withFlash = cm.cameraIdList.filter {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        val id = withFlash.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull() ?: return "error: this device has no flashlight"
        val on = if (c.has("on")) c.optBoolean("on") else !torchOn
        cm.setTorchMode(id, on)
        torchOn = on
        return "ok: flashlight ${if (on) "on" else "off"}"
    }

    // -------------------------------------------------------- ringer / DnD

    private fun policyAccessOrOpen(ctx: Context): String? {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) return null
        start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        return "error: permission needed - 'Do Not Disturb access' for Arena Agent (settings screen opened). Use need_user, then retry."
    }

    /** mode: normal | vibrate | silent (silent 는 방해금지 접근 권한 필요) */
    private fun ringerMode(ctx: Context, c: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = c.optString("mode").lowercase().trim()
        val v = when (mode) {
            "normal", "sound" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> {
                policyAccessOrOpen(ctx)?.let { return it }
                AudioManager.RINGER_MODE_SILENT
            }
            else -> return "error: ringer_mode mode must be normal|vibrate|silent"
        }
        am.ringerMode = v
        return "ok: ringer mode ${if (am.ringerMode == v) mode else "unchanged (system refused)"}"
    }

    /** mode: off | priority | alarms | total */
    private fun dnd(ctx: Context, c: JSONObject): String {
        policyAccessOrOpen(ctx)?.let { return it }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val mode = c.optString("mode").lowercase().trim()
        val f = when (mode) {
            "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority", "on" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "total", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return "error: dnd mode must be off|priority|alarms|total"
        }
        nm.setInterruptionFilter(f)
        return "ok: do-not-disturb $mode"
    }

    // ------------------------------------------------------------------- media

    /** command: play_pause | play | pause | next | previous | stop */
    private fun media(ctx: Context, c: JSONObject): String {
        val cmd = c.optString("command").lowercase().trim()
        val key = when (cmd) {
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "error: media command must be play_pause|play|pause|next|previous|stop"
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return "ok: media key '$cmd' sent to the active player"
    }

    private fun clipboardSet(ctx: Context, c: JSONObject): String {
        val text = c.optString("text")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("agent", text))
        return "ok: copied ${text.length} chars to clipboard"
    }

    // ------------------------------------------------------------------ status

    private inline fun item(sb: StringBuilder, key: String, f: () -> String) {
        val v = try { f() } catch (e: Exception) { "n/a" }
        sb.appendLine("$key: $v")
    }

    /** 현재 기기 상태를 한 번에 읽는다. 처음 프롬프트에 포함되므로 대부분 따로 부를 필요가 없다. */
    fun snapshot(ctx: Context): String {
        val app = ctx.applicationContext
        val sb = StringBuilder()
        item(sb, "battery") {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val st = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%${if (charging) " (charging)" else ""}"
        }
        item(sb, "volume") {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION)
                .joinToString(" ") { volumeText(am, it) }
        }
        item(sb, "ringer_mode") {
            when ((app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
        }
        item(sb, "brightness") {
            val cr = app.contentResolver
            val v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            "${v * 100 / 255}%${if (auto) " (adaptive)" else ""}"
        }
        item(sb, "do_not_disturb") {
            when (app.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "total"
                else -> "unknown"
            }
        }
        item(sb, "wifi") {
            if ((app.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled) "on" else "off"
        }
        item(sb, "network") {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        }
        item(sb, "bluetooth") {
            val ad = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (ad == null) "unsupported" else if (ad.isEnabled) "on" else "off"
        }
        item(sb, "airplane_mode") {
            if (Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) "on" else "off"
        }
        item(sb, "flashlight") { if (torchOn) "on" else "off" }
        item(sb, "api_permissions") {
            val nm = app.getSystemService(NotificationManager::class.java)
            "write_settings(brightness)=${Settings.System.canWrite(app)} dnd_access=${nm.isNotificationPolicyAccessGranted}"
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------ alarm / timer

    /** hour+minute(24h) 또는 in_minutes. label 선택. UI 없이 바로 등록. */
    private fun setAlarm(ctx: Context, c: JSONObject): String {
        var h: Int
        var m: Int
        if (c.has("in_minutes")) {
            val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, c.optInt("in_minutes").coerceIn(1, 24 * 60)) }
            h = cal.get(Calendar.HOUR_OF_DAY); m = cal.get(Calendar.MINUTE)
        } else {
            if (!c.has("hour")) return "error: set_alarm needs hour(0-23)+minute or in_minutes"
            h = c.optInt("hour"); m = c.optInt("minute", 0)
        }
        if (h !in 0..23 || m !in 0..59) return "error: invalid time $h:$m"
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, c.optString("label"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(ctx, i)
        return "ok: alarm requested for %02d:%02d (clock app handles it; use intent SHOW_ALARMS only if verification is needed)".format(h, m)
    }

    private fun setTimer(ctx: Context, c: JSONObject): String {
        val sec = c.optInt("seconds", 0)
        if (sec !in 1..86_400) return "error: set_timer needs seconds between 1 and 86400"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, c.optString("label"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(ctx, i)
        return "ok: timer requested for ${sec}s"
    }

    // ------------------------------------------------------------ openers

    private fun start(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private const val PAGES = "main wifi bluetooth display sound battery apps location airplane nfc network " +
        "accessibility date locale storage privacy security device_info input_method default_apps app_info " +
        "panel_wifi panel_internet panel_volume panel_nfc"

    /** 설정 앱을 홈부터 눌러 들어가지 말고, 해당 페이지를 바로 연다. */
    private fun openSettings(ctx: Context, c: JSONObject): String {
        val page = c.optString("page", "main").lowercase().trim()
        val q = Build.VERSION.SDK_INT >= 29
        val i: Intent = when (page) {
            "main" -> Intent(Settings.ACTION_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
            "network" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "locale" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "privacy" -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "device_info" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            "input_method" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "default_apps" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            "app_info" -> {
                val pkg = c.optString("package")
                if (pkg.isBlank()) return "error: app_info needs package"
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            }
            // 하단 시트 패널: 전체 설정 화면보다 훨씬 가볍고, 토글이 바로 화면에 보인다 (Android 10+)
            "panel_wifi" -> Intent(if (q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
            "panel_internet" -> Intent(if (q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)
            "panel_volume" -> Intent(if (q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)
            "panel_nfc" -> Intent(if (q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS)
            else -> return "error: unknown settings page '$page'. available: $PAGES"
        }
        start(ctx, i)
        return "ok: opened settings page $page"
    }

    private val INTENT_ACTIONS = setOf(
        Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SENDTO, Intent.ACTION_DIAL,
        Intent.ACTION_WEB_SEARCH, Intent.ACTION_INSERT, Intent.ACTION_EDIT, Intent.ACTION_MAIN,
        AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SHOW_TIMERS,
        MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, MediaStore.ACTION_IMAGE_CAPTURE,
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.ACTION_VIDEO_CAPTURE,
    )
    private val BLOCKED_SCHEMES = setOf("file", "content", "intent", "javascript", "android-app")

    /**
     * 앱 딥링크/공유/검색 등 범용 인텐트. 허용 목록 액션만 가능하고(ACTION_CALL 같은 즉시 실행형은 제외),
     * file/content/intent/javascript 스킴은 차단한다.
     * 키: intent_action, data, type, package, component("pkg/cls"), category, extras{}
     */
    private fun genericIntent(ctx: Context, c: JSONObject): String {
        val act = c.optString("intent_action").trim()
        if (act.isBlank()) return "error: intent needs intent_action"
        if (act !in INTENT_ACTIONS && !act.startsWith("android.settings.")) return "error: intent_action '$act' is not allowed"
        val i = Intent(act)
        val data = c.optString("data")
        val type = c.optString("type")
        if (data.isNotBlank()) {
            val uri = Uri.parse(data)
            if (uri.scheme?.lowercase() in BLOCKED_SCHEMES) return "error: data scheme '${uri.scheme}' is blocked"
            if (type.isNotBlank()) i.setDataAndType(uri, type) else i.data = uri
        } else if (type.isNotBlank()) {
            i.type = type
        }
        c.optString("package").takeIf { it.isNotBlank() }?.let { i.setPackage(it) }
        c.optString("component").takeIf { it.contains("/") }?.let {
            ComponentName.unflattenFromString(it)?.let { cn -> i.component = cn }
        }
        c.optString("category").takeIf { it.isNotBlank() }?.let { i.addCategory(it) }
        c.optJSONObject("extras")?.let { ex ->
            for (k in ex.keys()) {
                when (val v = ex.get(k)) {
                    is Boolean -> i.putExtra(k, v)
                    is Int -> i.putExtra(k, v)
                    is Long -> i.putExtra(k, v)
                    is Double -> i.putExtra(k, v)
                    is String -> i.putExtra(k, v)
                }
            }
        }
        start(ctx, i)
        return "ok: intent $act ${data.take(80)}".trim()
    }

    private val PHONE = Regex("^[0-9+*#()\\- ]{1,32}$")

    /** 다이얼러만 연다(실제 발신 X). 발신은 사용자가 직접 누른다. */
    private fun dial(ctx: Context, c: JSONObject): String {
        val n = c.optString("number").trim()
        if (!PHONE.matches(n)) return "error: invalid phone number"
        start(ctx, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)))
        return "ok: dialer opened with $n (call NOT placed)"
    }

    /** 문자 작성 화면만 연다(전송 X). 전송은 need_user 확인 후 UI 로 진행. */
    private fun smsCompose(ctx: Context, c: JSONObject): String {
        val n = c.optString("number").trim()
        if (!PHONE.matches(n)) return "error: invalid phone number"
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n)))
            .putExtra("sms_body", c.optString("text"))
        start(ctx, i)
        return "ok: SMS compose opened for $n (NOT sent)"
    }

    private fun webSearch(ctx: Context, c: JSONObject): String {
        val q = c.optString("query").trim()
        if (q.isBlank()) return "error: web_search needs query"
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        return "ok: opened web search for '${q.take(60)}'"
    }

    private fun maps(ctx: Context, c: JSONObject): String {
        val q = c.optString("query").trim()
        if (q.isBlank()) return "error: maps needs query"
        return try {
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            "ok: opened map search for '${q.take(60)}'"
        } catch (e: ActivityNotFoundException) {
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(q))))
            "ok: opened map search in browser for '${q.take(60)}'"
        }
    }
}
