package com.agent.arena

import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime
import java.util.Locale

/** LMArena 로 함께 보내는 기기 상태/환경 정보. */
object DeviceState {

    fun info(ctx: Context): String {
        val dm = ctx.resources.displayMetrics
        return buildString {
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("screen_px: ${dm.widthPixels}x${dm.heightPixels} (density ${dm.density})")
            appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("local_time: ${ZonedDateTime.now()}")
            // 현재 시스템 상태(음량/밝기/무음/방해금지/Wi-Fi/블루투스/배터리/손전등/권한) - 설정 앱을 열어 읽을 필요 없음
            appendLine(DeviceApi.snapshot(ctx))
            append("accessibility_service: ${if (AgentAccessibilityService.instance != null) "enabled" else "DISABLED (UI tap/swipe/screen-reading unavailable, but API commands and open_settings/intent/dial/web_search/maps still work)"}")
        }
    }

    fun apps(ctx: Context): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .take(150)
            .joinToString("\n") { "${it.first} | ${it.second}" }
    }

    fun screen(): String {
        val svc = AgentAccessibilityService.instance
            ?: return "(접근성 서비스가 꺼져 있어 화면을 볼 수 없음. API 명령(set_volume 등)·open_settings·intent 는 그대로 사용 가능. 탭/스와이프 같은 UI 조작이 꼭 필요할 때만 need_user 로 접근성 권한 활성화를 요청하세요.)"
        return svc.dumpScreen()
    }
}
