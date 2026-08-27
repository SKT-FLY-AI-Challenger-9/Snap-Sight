package com.example.snap_sight.metrics

import android.os.SystemClock
import android.util.Log
import com.example.snap_sight.ux.GuidanceFeedback
import org.json.JSONObject

/**
 * 세션별 구간 지연 측정 — 발표용 p50/p95 산출 재료 (2026-08-27).
 *
 * 모든 시각은 폰의 [SystemClock.elapsedRealtime] **하나로만** 잰다. 서버 시계와 섞지 않으므로
 * 폰-서버 시계 오차 문제가 없고, 서버 구간(베스트컷+설명)은 "폰이 결과를 받아본 시각" 기준이라
 * 폴링 대기(2→4→8초 백오프)가 포함된다 = 사용자 체감 기준.
 *
 * 마크 이름 (모두 listening_start 로부터의 ms 오프셋으로 기록):
 *  - listening_start       두 번 탭 → 세션 시작 (LISTENING 진입)
 *  - stt_done              발화 종료 두 번 탭 (PARSING 진입)
 *  - utterance_understood  타겟 스펙 응답 수신 (발화 이해)
 *  - aiming_start          조준 시작
 *  - shutter               셔터 (CAPTURING 진입)
 *  - upload_done           업로드 응답 수신
 *  - understanding_done    통합 사진 이해(베스트컷+설명+라벨) 수신 — 서버가 한 번에 돌려주므로
 *                          베스트컷/설명은 폰에서는 분리되지 않는다
 *  - description_done      (수동 촬영 경로) 상세 설명 수신
 *  - announce_requested    결과 안내 발화 요청
 *  - announce_start        결과 안내 재생 실제 시작 (내장 TTS·서버 합성 공통)
 *
 * 안내 재생이 시작되면 JSON 한 줄을 logcat(SnapLatency)에 남긴다. 안내까지 못 간 세션
 * (취소·실패·안내 없는 흐름)은 다음 세션 시작 때 partial=true 로 남긴다.
 *
 * 수집·집계:
 *   adb logcat -d -s SnapLatency:I > latency.log
 *   python -m ai.tools.latency_report latency.log
 */
class SessionLatencyTracker {

    private val lock = Any()
    private var sessionId: String? = null
    private var marks = LinkedHashMap<String, Long>()
    private var awaitingAnnounce = false
    private var emitted = false

    /** 새 세션 시작(두 번 탭 → LISTENING). 직전 세션이 안내까지 못 갔으면 partial 로 흘려보낸다. */
    fun begin(sessionId: String?) {
        synchronized(lock) {
            if (marks.isNotEmpty() && !emitted) emitLocked(partial = true)
            this.sessionId = sessionId
            marks = LinkedHashMap()
            awaitingAnnounce = false
            emitted = false
            marks[MARK_LISTENING_START] = now()
        }
    }

    /**
     * 구간 경계 기록. 같은 이름은 첫 기록만 유지한다(업로드 재시도·중복 콜백이 시각을 덮어쓰지 않게).
     * 진행 중 세션과 다른 sessionId 의 늦은 콜백은 무시한다.
     */
    fun mark(sessionId: String?, name: String) {
        synchronized(lock) {
            if (marks.isEmpty()) return // begin 전 이벤트 (세션 밖)
            if (sessionId != null && this.sessionId != null && sessionId != this.sessionId) return
            if (sessionId != null && this.sessionId == null) this.sessionId = sessionId
            if (!marks.containsKey(name)) marks[name] = now()
        }
    }

    /**
     * 결과 안내 발화를 곧 요청함 — 이 뒤 첫 재생 시작([onSpeechStart])을 announce_start 로 잡는다.
     * (합성 경로가 내장 TTS/서버 합성 두 갈래라 재생 시작은 [GuidanceFeedback] 훅으로만 알 수 있다)
     */
    fun expectAnnounce(sessionId: String?) {
        synchronized(lock) {
            if (marks.isEmpty()) return
            if (sessionId != null && this.sessionId != null && sessionId != this.sessionId) return
            if (!marks.containsKey(MARK_ANNOUNCE_REQUESTED)) marks[MARK_ANNOUNCE_REQUESTED] = now()
            awaitingAnnounce = true
        }
    }

    /** [GuidanceFeedback.speechStartListener] 로 연결 — expectAnnounce 이후 첫 재생 시작을 소비한다. */
    fun onSpeechStart(@Suppress("UNUSED_PARAMETER") priority: GuidanceFeedback.SpeechPriority) {
        synchronized(lock) {
            if (!awaitingAnnounce || emitted) return
            awaitingAnnounce = false
            marks[MARK_ANNOUNCE_START] = now()
            emitLocked(partial = false)
        }
    }

    private fun emitLocked(partial: Boolean) {
        emitted = true
        val base = marks[MARK_LISTENING_START] ?: return
        val offsets = JSONObject()
        for ((name, at) in marks) offsets.put(name, at - base)
        val line = JSONObject()
            .put("session", sessionId ?: "unknown")
            .put("partial", partial)
            .put("marks_ms", offsets)
        Log.i(TAG, line.toString())
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "SnapLatency"
        const val MARK_LISTENING_START = "listening_start"
        const val MARK_STT_DONE = "stt_done"
        const val MARK_UTTERANCE_UNDERSTOOD = "utterance_understood"
        const val MARK_AIMING_START = "aiming_start"
        const val MARK_SHUTTER = "shutter"
        const val MARK_UPLOAD_DONE = "upload_done"
        const val MARK_UNDERSTANDING_DONE = "understanding_done"
        const val MARK_DESCRIPTION_DONE = "description_done"
        const val MARK_ANNOUNCE_REQUESTED = "announce_requested"
        const val MARK_ANNOUNCE_START = "announce_start"
    }
}
