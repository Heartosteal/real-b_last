package com.blockblast.helper.controller

import com.blockblast.helper.model.HelperMode
import com.blockblast.helper.model.SolveResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutomationState(
    val mode: HelperMode = HelperMode.IDLE,
    val targetScore: Int? = null,
    val currentScore: Int = 0,
    val remainingTimeMs: Long? = null,
    val lastResult: SolveResult? = null,
    val statusMessage: String = "대기 중"
)

/**
 * 추천 모드, 자동 실행 모드, 목표 점수 및 실행 시간 제한을 통합 제어.
 */
class AutomationController {

    private val _state = MutableStateFlow(AutomationState())
    val state: StateFlow<AutomationState> = _state.asStateFlow()

    private var timerDeadlineMs: Long? = null

    fun setMode(mode: HelperMode) {
        if (mode != HelperMode.AUTO_PLAY) {
            timerDeadlineMs = null
        }
        val msg = when (mode) {
            HelperMode.IDLE -> "대기 중"
            HelperMode.RECOMMEND -> "추천 모드 실행 중"
            HelperMode.AUTO_PLAY -> "자동 배치 실행 중"
        }
        _state.value = _state.value.copy(mode = mode, statusMessage = msg)
    }

    fun setTargetScore(score: Int?) {
        _state.value = _state.value.copy(
            targetScore = score,
            statusMessage = if (score != null) "목표 점수: ${score}점 설정됨" else "목표 점수 해제됨"
        )
    }

    fun setTargetDurationSeconds(seconds: Int?) {
        if (seconds == null || seconds <= 0) {
            timerDeadlineMs = null
            _state.value = _state.value.copy(remainingTimeMs = null)
            return
        }
        val durationMs = seconds * 1000L
        timerDeadlineMs = System.currentTimeMillis() + durationMs
        _state.value = _state.value.copy(
            remainingTimeMs = durationMs,
            statusMessage = "실행 시간: ${seconds}초 설정됨"
        )
    }

    fun updateCurrentScore(score: Int?) {
        if (score == null) return
        val current = _state.value
        val target = current.targetScore
        if (current.mode == HelperMode.AUTO_PLAY && target != null && score >= target) {
            _state.value = current.copy(
                mode = HelperMode.IDLE,
                currentScore = score,
                statusMessage = "목표 점수(${target}점) 달성으로 자동 정지"
            )
        } else {
            _state.value = current.copy(currentScore = score)
        }
    }

    fun tickTimerAndCheckStop(): Boolean {
        val deadline = timerDeadlineMs ?: return false
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) {
            timerDeadlineMs = null
            _state.value = _state.value.copy(
                mode = HelperMode.IDLE,
                remainingTimeMs = 0L,
                statusMessage = "설정한 실행 시간 종료로 자동 정지"
            )
            return true
        }
        _state.value = _state.value.copy(remainingTimeMs = remaining)
        return false
    }

    fun updateSolveResult(result: SolveResult?) {
        val current = _state.value
        val modeTag = if (result?.isCrisisMode == true) " [생존 우선]" else ""
        val msg = when {
            current.mode == HelperMode.IDLE -> current.statusMessage
            result == null -> "인식 대기 중..."
            else -> "${current.mode.name}$modeTag (${result.steps.size}수 계산 완료)"
        }
        _state.value = current.copy(lastResult = result, statusMessage = msg)
    }
}
