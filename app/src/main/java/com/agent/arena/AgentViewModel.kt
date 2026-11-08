package com.agent.arena

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class Role { USER, AGENT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String = "",
    val steps: List<String> = emptyList(),
    val streaming: Boolean = false,
    val planning: Boolean = false,
    val isError: Boolean = false,
)

class AgentViewModel : ViewModel() {

    companion object { private const val MAX_STEPS = 40 }

    private val app get() = AgentApp.instance
    private val ctl get() = AgentApp.instance.controller

    val messages = mutableStateListOf<ChatMessage>()
    var running by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var sessionStarted = false
    private var nextId = 1L
    /** 로컬 패스트패스로 처리한 작업 기록 - 다음 LLM 프롬프트에 알려 문맥을 유지한다. */
    private var localNote = ""

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || running) return
        messages.add(ChatMessage(nextId++, Role.USER, t))
        val idx = messages.size
        messages.add(ChatMessage(nextId++, Role.AGENT, streaming = true))
        running = true
        job = viewModelScope.launch {
            try {
                runAgent(t, idx)
            } finally {
                running = false
                AgentAccessibilityService.instance?.cursorHide(1300)
            }
        }
    }

    fun stop() {
        if (!running) return
        job?.cancel()
        viewModelScope.launch { ctl.stopGeneration() }
    }

    /** 새 채팅 = LMArena URL 을 다시 열어 새 세션 시작. */
    fun newChat() {
        job?.cancel()
        running = false
        messages.clear()
        sessionStarted = false
        localNote = ""
        viewModelScope.launch {
            ctl.stopGeneration()
            ctl.newChat()
        }
    }

    // ---------------------------------------------------------------- agent loop

    private fun upd(i: Int, f: (ChatMessage) -> ChatMessage) {
        if (i in 0 until messages.size) messages[i] = f(messages[i])
    }

    private fun finish(i: Int, text: String, error: Boolean) {
        upd(i) { it.copy(text = text, streaming = false, planning = false, isError = error) }
    }

    private class Exec(val json: String, val hadError: Boolean, val quietLines: List<String>)

    private suspend fun runAgent(userText: String, idx: Int) {
        // 0) 패스트패스: 볼륨/밝기/손전등 같은 단순 명령은 LLM 왕복 없이 즉시 실행
        FastPath.tryHandle(app, userText)?.let { r ->
            upd(idx) { it.copy(steps = it.steps + r.steps) }
            localNote += "user: $userText -> ${r.steps.joinToString("; ")}\n"
            finish(idx, r.message, r.error)
            return
        }

        val note = localNote
        localNote = ""
        var outgoing = if (!sessionStarted) AgentPrompt.first(userText, app, note) else AgentPrompt.followUp(userText, app, note)
        var step = 0
        var formatRetries = 0
        var errorRetries = 0
        try {
            while (true) {
                step++
                if (step > MAX_STEPS) {
                    finish(idx, "최대 단계($MAX_STEPS)에 도달해 작업을 중단했습니다.", true); return
                }
                val ticket = ctl.send(outgoing)
                if (ticket == null) {
                    finish(idx, "LMArena로 메시지를 보내지 못했습니다. 상단 'Agent' 버튼으로 LMArena 페이지 상태(로그인/캡차)를 확인하세요.", true)
                    return
                }
                sessionStarted = true

                val raw = ctl.awaitResponse(ticket) { partial ->
                    upd(idx) {
                        it.copy(
                            text = WorkParser.visibleStreaming(partial),
                            planning = WorkParser.isPlanning(partial),
                        )
                    }
                }
                if (raw.isBlank()) {
                    finish(idx, "LMArena 응답을 받지 못했습니다. (시간 초과 또는 사이트 오류)\n진단: ${ctl.debug(ticket)}", true); return
                }

                val parsed = WorkParser.parse(raw)
                val block = parsed.block
                if (block == null) {
                    if (parsed.looksBroken && formatRetries < 2) {
                        formatRetries++
                        outgoing = AgentPrompt.formatError()
                        continue
                    }
                    // work 블록이 없으면 일반 대화 답변으로 간주
                    finish(idx, parsed.visible.ifBlank { raw }, false)
                    return
                }
                formatRetries = 0
                upd(idx) {
                    it.copy(
                        steps = it.steps + block.summary.ifBlank { block.status },
                        text = "",
                        planning = false,
                    )
                }

                val svc = AgentAccessibilityService.instance
                val actions = block.commands.map { it.optString("action").lowercase().trim() }
                val apiOnly = actions.isNotEmpty() && actions.all { DeviceApi.isQuiet(it) }
                // API 전용 턴은 화면이 바뀌지 않으므로 커서 오버레이를 띄우지 않는다
                if (block.commands.isNotEmpty() && !apiOnly) svc?.cursorShow(block.summary.ifBlank { "작업 중" })
                val exec = if (block.commands.isNotEmpty()) executeAll(block.commands) else Exec("[]", false, emptyList())
                if (exec.quietLines.isNotEmpty()) upd(idx) { it.copy(steps = it.steps + exec.quietLines) }

                // 모델이 "done" 이라 했어도 실행 중 오류가 났다면 끝내지 않고 결과를 돌려줘 재시도시킨다
                val terminal = block.status == "done" || block.status == "failed" || block.status == "need_user"
                val retryAfterError = block.status == "done" && exec.hadError && errorRetries < 2
                if (retryAfterError) errorRetries++
                if (terminal && !retryAfterError) {
                    when (block.status) {
                        "done" -> { svc?.cursorLabel("작업 완료", CursorState.DONE); finish(idx, parsed.visible.ifBlank { block.summary }, false) }
                        "failed" -> { svc?.cursorLabel("작업 실패", CursorState.FAILED); finish(idx, parsed.visible.ifBlank { block.summary }, true) }
                        else -> { svc?.cursorLabel("확인이 필요해요", CursorState.ASK); finish(idx, parsed.visible.ifBlank { block.summary }, false) }
                    }
                    return
                }
                if (!apiOnly) svc?.cursorLabel("다음 작업 생각 중…", CursorState.THINKING)
                // 고정 delay 제거: 각 액션이 이미 화면 안정(settle)까지 기다린 뒤 반환한다.
                // API 전용 턴이면 화면 덤프를 생략(observe:true 로 요청 가능)
                val needScreen = !apiOnly || block.observe
                outgoing = AgentPrompt.result(step, exec.json, needScreen)
            }
        } catch (e: CancellationException) {
            AgentAccessibilityService.instance?.cursorLabel("중단됨", CursorState.STOPPED)
            upd(idx) { it.copy(streaming = false, planning = false, text = it.text.ifBlank { "(중단됨)" }) }
            throw e
        } catch (e: Exception) {
            finish(idx, "오류가 발생했습니다: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /** 한 턴의 명령 실행. API 명령은 접근성 서비스가 꺼져 있어도 앱 컨텍스트로 실행된다. */
    private suspend fun executeAll(cmds: List<JSONObject>): Exec {
        val svc = AgentAccessibilityService.instance
        val arr = JSONArray()
        val quiet = ArrayList<String>()
        var hadError = false
        for (c in cmds.take(12)) {
            val a = c.optString("action").lowercase().trim()
            val r = when {
                svc != null -> svc.execute(c)
                DeviceApi.handles(a) -> DeviceApi.execute(app, c)
                else -> "error: accessibility service is not enabled (needed for UI actions; API commands still work)"
            }
            arr.put(JSONObject().put("action", a).put("result", r))
            if (DeviceApi.isQuiet(a) && a != "status") quiet.add((if (r.startsWith("error")) "✗ " else "✓ ") + r.removePrefix("ok: ").removePrefix("error: ").take(120))
            if (r.startsWith("error")) { hadError = true; break }
        }
        return Exec(arr.toString(), hadError, quiet)
    }
}
