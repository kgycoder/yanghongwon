package com.agent.arena

import android.content.Context
import org.json.JSONObject

/**
 * 아주 단순하고 모호하지 않은 명령은 LMArena(LLM) 왕복 없이 로컬에서 즉시 실행한다.
 * 보수적으로 매칭하고, 조금이라도 애매하면 null 을 돌려 정상 에이전트 루프로 넘긴다.
 */
object FastPath {

    data class Result(val message: String, val steps: List<String>, val error: Boolean)

    private val QUESTION = Regex("방법|어떻게|왜|알려|설명|뭐|무엇|how|why|what|explain|\\?")
    private val TIME_WORDS = Regex("\\d\\s*(분|초|시간|시|min|sec|hour|pm|am)")
    // 복합 명령/예약성 표현은 패스트패스가 일부만 가로채지 않도록 LLM 으로 넘긴다
    private val COMPOUND = Regex("[가-힣]고\\s|그리고|그다음|그 다음|하고|랑 |\\band\\b|\\bthen\\b|,|타이머|timer|뒤에|후에|예약|내일|tomorrow")
    private val PERCENT = Regex("(\\d{1,3})\\s*(%|퍼센트|프로|percent)?")

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }

    fun tryHandle(ctx: Context, raw: String): Result? {
        val t = raw.trim().lowercase()
        if (t.isEmpty() || t.length > 40 || QUESTION.containsMatchIn(t)) return null
        if (COMPOUND.containsMatchIn(t) || TIME_WORDS.containsMatchIn(t)) return null
        val ko = raw.any { it in '\uAC00'..'\uD7A3' }

        // 정확히 하나의 의도로만 해석될 때만 실행 (둘 이상이면 모호 → LLM)
        val hits = listOfNotNull(media(t), ringer(t), torch(t), dnd(t), brightness(t), volume(t))
        if (hits.size != 1) return null
        val cmd: JSONObject = hits[0]
        val r = DeviceApi.execute(ctx, cmd)
        val ok = r.startsWith("ok")
        val clean = r.removePrefix("ok: ").removePrefix("error: ")
        val msg = if (ok) (if (ko) "완료했어요. ($clean)" else "Done. ($clean)") else (if (ko) "실패: $clean" else "Failed: $clean")
        return Result(msg, listOf(if (ok) "✓ $clean" else "✗ $clean"), !ok)
    }

    private fun obj(vararg kv: Pair<String, Any>) = JSONObject().also { o -> kv.forEach { o.put(it.first, it.second) } }

    private fun media(t: String): JSONObject? {
        val m = Regex("^(음악\\s*)?(재생|일시\\s*정지|다음\\s*곡|이전\\s*곡|멈춰|다음\\s*노래|이전\\s*노래|pause|play|next|previous|skip)\\s*(해\\s*줘|해줘|해|해주세요|좀)?$").find(t) ?: return null
        val w = m.groupValues[2].replace(" ", "")
        val c = when (w) {
            "재생", "play" -> "play"
            "일시정지", "멈춰", "pause" -> "pause"
            "다음곡", "다음노래", "next", "skip" -> "next"
            else -> "previous"
        }
        return obj("action" to "media", "command" to c)
    }

    private fun ringer(t: String): JSONObject? = when {
        has(t, "무음 모드", "무음모드", "무음으로", "silent mode") -> obj("action" to "ringer_mode", "mode" to "silent")
        has(t, "진동 모드", "진동모드", "진동으로", "vibrate mode") -> obj("action" to "ringer_mode", "mode" to "vibrate")
        has(t, "소리 모드", "소리모드", "벨소리 모드", "normal mode", "sound mode") -> obj("action" to "ringer_mode", "mode" to "normal")
        else -> null
    }

    private fun torch(t: String): JSONObject? {
        if (!has(t, "손전등", "플래시", "랜턴", "flashlight", "torch")) return null
        return when {
            has(t, "꺼", "끄", "off") -> obj("action" to "torch", "on" to false)
            has(t, "켜", "키", "on") -> obj("action" to "torch", "on" to true)
            else -> null
        }
    }

    private fun dnd(t: String): JSONObject? {
        if (!has(t, "방해 금지", "방해금지", "do not disturb", "dnd")) return null
        return when {
            has(t, "꺼", "끄", "해제", "off") -> obj("action" to "dnd", "mode" to "off")
            has(t, "켜", "키", "on") -> obj("action" to "dnd", "mode" to "priority")
            else -> null
        }
    }

    private fun brightness(t: String): JSONObject? {
        if (!has(t, "밝기", "brightness", "화면 밝게", "화면 어둡게", "밝게", "어둡게")) return null
        if (TIME_WORDS.containsMatchIn(t)) return null
        PERCENT.find(t)?.let { m ->
            if (m.groupValues[2].isNotEmpty() || has(t, "밝기 ", "brightness ")) {
                return obj("action" to "set_brightness", "level" to m.groupValues[1].toInt().coerceIn(1, 100))
            }
        }
        return when {
            has(t, "최대", "max", "가장 밝게") -> obj("action" to "set_brightness", "level" to 100)
            has(t, "최소", "min", "가장 어둡게") -> obj("action" to "set_brightness", "level" to 1)
            has(t, "자동", "auto") -> obj("action" to "set_brightness", "auto" to true)
            has(t, "올려", "높여", "밝게", "키워", "up", "increase", "brighter") -> obj("action" to "set_brightness", "delta" to 20)
            has(t, "내려", "낮춰", "줄여", "어둡게", "down", "decrease", "dim") -> obj("action" to "set_brightness", "delta" to -20)
            else -> null
        }
    }

    private fun volume(t: String): JSONObject? {
        if (!has(t, "볼륨", "음량", "소리", "volume", "음소거", "mute")) return null
        if (has(t, "해제", "unmute", "풀어")) return null
        val stream = when {
            has(t, "벨소리", "ring") -> "ring"
            has(t, "알람", "alarm") -> "alarm"
            has(t, "알림", "notification") -> "notification"
            has(t, "통화", "call") -> "call"
            else -> "music"
        }
        fun cmd(vararg kv: Pair<String, Any>) = obj("action" to "set_volume", "stream" to stream, *kv)
        PERCENT.find(t)?.let { m ->
            if (m.groupValues[2].isNotEmpty() || has(t, "볼륨 ", "음량 ", "volume ")) {
                return cmd("level" to m.groupValues[1].toInt().coerceIn(0, 100))
            }
        }
        return when {
            has(t, "음소거", "mute", "소리 꺼", "볼륨 꺼") -> cmd("mute" to true)
            has(t, "최대", "max", "풀로", "가득") -> cmd("max" to true)
            has(t, "올려", "높여", "키워", "크게", "up", "increase", "louder", "올리") -> cmd("delta_pct" to 15)
            has(t, "내려", "낮춰", "줄여", "작게", "down", "decrease", "lower", "quieter", "내리") -> cmd("delta_pct" to -15)
            else -> null
        }
    }
}
