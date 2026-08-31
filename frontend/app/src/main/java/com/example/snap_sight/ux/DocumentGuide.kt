// 이 파일: 서류(DOCUMENT) 모드 전용 안내·자동촬영 로직 (2026-08-30, 사용자 요청 "서류·종이·신분증
// 등을 말하면 서류 모드"). 서류는 YOLO bbox 로 잡히지 않으므로 텍스트 줄들의 합집합
// ([DocumentObservation])으로 프레이밍한다. 서류가 책상 위·벽·거치대 어디에 어떤 각도로
// 있든 성립해야 하므로 중력 센서로 자세를 강제하지 않고, 회전은 글자 줄 각도로 잰다
// (사용자 지적 2026-08-30). 정면(수직)은 사용자가 맞추는 것으로 본다 — 글자 높이 기울기로
// 재는 수직 판정은 제목·본문 글자 크기 차이와 구분이 안 돼 꺼 두었다([TILT_GUIDANCE_ENABLED]).
// android.* 의존이 없고 시각(nowMs)을 주입받아 JVM 단위 테스트한다.
package com.example.snap_sight.ux

import com.example.snap_sight.camera.PhoneRoll
import kotlin.math.abs

/**
 * 판정 순서 — 앞 단계가 통과해야 다음을 말한다(시각장애 사용자가 여러 축을 동시에 고치는 건
 * 무리라 한 번에 하나만):
 *  1. 서류 찾기 — 관측이 없거나 오래됐거나 줄이 [MIN_LINES] 미만이면 탐색 안내
 *  2. 잘림 — 글자 영역이 마주보는 두 변에 닿으면 줌아웃(줌이 1.0 이면 "멀리"), 한 변이면 그
 *     방향으로 폰을 옮기기
 *  3. 크기 — 영역이 [ZOOM_TARGET_FILL] 미만이면 **줌인**으로 채운다(사용자 요청 2026-08-30 —
 *     "정면으로 맞추고 확대만 해줘도"). 줌이 상한이고도 [MIN_FILL] 미만일 때만 "가까이".
 *  4. 위치 — 중심이 [POSITION_TOLERANCE] 넘게 벗어나면 옮기기
 *  5. 회전 — 글자 줄 각도의 스냅 편차가 [ROTATION_TOLERANCE_DEG] 를 넘으면 돌리기 (풍경 문구 공용)
 *  6. 반사 — 서류 영역 포화 픽셀이 [GLARE_MAX] 를 넘으면 자리 옮기기
 *  7. 전부 통과 → 마지막 안내로부터 [SETTLE_AFTER_INSTRUCTION_MS] 지난 뒤 "그대로 멈춰 주세요"
 *     1회 + 진동 → 그 발화 시점부터 정지 상태로 [HOLD_MS] 유지 시 자동촬영 1회.
 *     (안내 문구가 재생 중인데 셔터가 터지던 문제 대책 — 실기기 2026-08-30)
 *
 * 통과 상태에서는 임계값을 [RELAX_FACTOR] 배 완화한다(히스테리시스) — 경계에서 손떨림으로
 * "좋아요/옮겨 주세요"가 왔다갔다하지 않게. 같은 문구는 [REPEAT_MS] 간격, 바뀐 문구도
 * [MIN_GAP_MS] 간격으로만 말한다.
 */
internal class DocumentGuide {

    internal enum class Zoom { IN, OUT }

    internal data class Outcome(
        /** 지금 말할 문구 — 없으면 null. */
        val utterance: String? = null,
        /** 화면 안내 카드용 짧은 상태 문구. */
        val statusText: String,
        /** 짧은 확정 진동 1회. */
        val vibrate: Boolean = false,
        /** 자동촬영 요청 — 세션당 1회만 true. */
        val capture: Boolean = false,
        /** 배율 한 스텝 요청 — 실제 반영은 호출부(AutoZoomController 쿨다운)가 결정. */
        val zoom: Zoom? = null,
    )

    private var searchingSinceMs: Long? = null
    private var holdSinceMs: Long? = null
    private var readyAnnouncedAtMs: Long? = null
    private var fired = false
    private var lastUtterance: String? = null
    private var lastSpokenAtMs = Long.MIN_VALUE / 2
    /** 마지막으로 교정 안내를 말한 시각 — 이 뒤 [SETTLE_AFTER_INSTRUCTION_MS] 동안은 "좋아요"를 미룬다. */
    private var lastInstructionAtMs = Long.MIN_VALUE / 2

    /** 새 서류 세션 — 진행 상태·"이미 말했음"을 지운다. */
    fun reset() {
        searchingSinceMs = null
        holdSinceMs = null
        readyAnnouncedAtMs = null
        fired = false
        lastUtterance = null
        lastSpokenAtMs = Long.MIN_VALUE / 2
        lastInstructionAtMs = Long.MIN_VALUE / 2
    }

    /**
     * 매 분석 프레임마다 호출한다.
     *
     * @param observation 최신 텍스트 관측 — 없으면 null. [FRESH_MS] 보다 오래된 것은 없는 것으로 본다.
     * @param subjectStatic 서류(글자 영역)가 정지해 있는가([SubjectMotionDetector.isStatic]) —
     *        손에 든 신분증처럼 계속 움직이면 유지 시간이 쌓이지 않는다.
     * @param zoomInAvailable 아직 더 확대할 수 있는가 (배율 < 상한). false 면 크기 미달을 "가까이"로 푼다.
     * @param zoomOutAvailable 축소할 수 있는가 (배율 > 1.0). false 면 잘림을 "멀리"로 푼다.
     */
    fun onJudgment(
        observation: DocumentObservation?,
        subjectStatic: Boolean,
        nowMs: Long,
        zoomInAvailable: Boolean = false,
        zoomOutAvailable: Boolean = false,
    ): Outcome {
        val fresh = observation != null &&
            nowMs - observation.atMs <= FRESH_MS &&
            observation.lineCount >= MIN_LINES
        if (!fresh) {
            leaveGoodState()
            return searching(nowMs)
        }
        searchingSinceMs = null
        val inGoodState = readyAnnouncedAtMs != null
        val problem = evaluate(observation!!, zoomInAvailable, zoomOutAvailable, relaxed = inGoodState)
        if (problem != null) {
            leaveGoodState()
            if (problem.zoom != null) {
                // 줌은 말없이 진행한다 — 틱 소리는 호출부가 낸다
                return Outcome(statusText = problem.status, zoom = problem.zoom)
            }
            val utterance = speakIfDue(problem.utterance!!, nowMs)
            if (utterance != null) lastInstructionAtMs = nowMs
            return Outcome(utterance = utterance, statusText = problem.status)
        }

        // 방금 낸 교정 안내가 아직 재생 중일 수 있다 — 그 위에 "좋아요"를 얹지 않고 잠시 기다린다
        if (readyAnnouncedAtMs == null && nowMs - lastInstructionAtMs < SETTLE_AFTER_INSTRUCTION_MS) {
            return Outcome(statusText = STATUS_HOLD)
        }

        var utterance: String? = null
        var vibrate = false
        val readyAt = readyAnnouncedAtMs ?: nowMs.also {
            readyAnnouncedAtMs = it
            utterance = speakIfDue(READY_UTTERANCE, it)
            vibrate = true
        }
        if (fired) return Outcome(utterance, STATUS_DONE, vibrate)
        if (!subjectStatic) {
            holdSinceMs = null
            return Outcome(utterance, STATUS_HOLD, vibrate)
        }
        val since = holdSinceMs ?: nowMs.also { holdSinceMs = it }
        // "좋아요" 발화가 끝나고도 정지가 이어져야 찍는다 — 두 시계 모두 [HOLD_MS] 를 채워야 한다
        if (nowMs - since >= HOLD_MS && nowMs - readyAt >= HOLD_MS) {
            fired = true
            return Outcome(utterance, STATUS_DONE, vibrate = true, capture = true)
        }
        return Outcome(utterance, STATUS_HOLD, vibrate)
    }

    private fun leaveGoodState() {
        holdSinceMs = null
        readyAnnouncedAtMs = null
    }

    private class Problem(val status: String, val utterance: String? = null, val zoom: Zoom? = null)

    /**
     * 2~6단계 — 가장 먼저 걸리는 문제 하나만 돌려준다. 전부 통과면 null.
     * [relaxed] 면(이미 통과 상태) 위치·회전·반사 허용치를 [RELAX_FACTOR] 배, 크기 하한을 그만큼
     * 낮춰 경계에서 튀지 않게 한다.
     */
    private fun evaluate(
        o: DocumentObservation,
        zoomInAvailable: Boolean,
        zoomOutAvailable: Boolean,
        relaxed: Boolean,
    ): Problem? {
        val factor = if (relaxed) RELAX_FACTOR else 1f
        val leftMargin = o.left
        val rightMargin = 1f - o.right
        val topMargin = o.top
        val bottomMargin = 1f - o.bottom
        val clippedH = leftMargin < EDGE_MARGIN && rightMargin < EDGE_MARGIN
        val clippedV = topMargin < EDGE_MARGIN && bottomMargin < EDGE_MARGIN
        if (clippedH || clippedV) {
            return if (zoomOutAvailable) Problem(STATUS_ZOOMING_OUT, zoom = Zoom.OUT)
            else Problem(STATUS_TOO_CLOSE, FARTHER_UTTERANCE)
        }
        if (leftMargin < EDGE_MARGIN) return Problem(STATUS_CLIPPED, SHIFT_LEFT_UTTERANCE)
        if (rightMargin < EDGE_MARGIN) return Problem(STATUS_CLIPPED, SHIFT_RIGHT_UTTERANCE)
        if (topMargin < EDGE_MARGIN) return Problem(STATUS_CLIPPED, SHIFT_UP_UTTERANCE)
        if (bottomMargin < EDGE_MARGIN) return Problem(STATUS_CLIPPED, SHIFT_DOWN_UTTERANCE)

        // 크기: 줌으로 채우는 게 기본. 통과 상태에서는 목표까지 다시 줌하지 않는다(히스테리시스).
        if (!relaxed && o.area < ZOOM_TARGET_FILL && zoomInAvailable) {
            return Problem(STATUS_ZOOMING_IN, zoom = Zoom.IN)
        }
        if (o.area < MIN_FILL / factor) return Problem(STATUS_TOO_FAR, CLOSER_UTTERANCE)

        val dx = o.centerX - 0.5f
        val dy = o.centerY - 0.5f
        val positionTolerance = POSITION_TOLERANCE * factor
        if (abs(dx) > positionTolerance || abs(dy) > positionTolerance) {
            // 더 벗어난 축 하나만 — 서류가 오른쪽에 있으면 폰을 오른쪽으로 옮긴다
            val utterance = if (abs(dx) >= abs(dy)) {
                if (dx > 0f) SHIFT_RIGHT_UTTERANCE else SHIFT_LEFT_UTTERANCE
            } else {
                if (dy > 0f) SHIFT_DOWN_UTTERANCE else SHIFT_UP_UTTERANCE
            }
            return Problem(STATUS_OFF_CENTER, utterance)
        }

        if (TILT_GUIDANCE_ENABLED) {
            // 수직: 아랫줄 글자가 더 크면(양수) 아래가 가깝고 위가 멀다 = 폰 윗부분이 젖혀짐
            if (o.heightGradient > TILT_TOLERANCE * factor) return Problem(STATUS_TILTED, TILT_TOP_TOWARD_UTTERANCE)
            if (o.heightGradient < -TILT_TOLERANCE * factor) return Problem(STATUS_TILTED, TILT_TOP_AWAY_UTTERANCE)
        }

        val rotation = PhoneRoll.deviationFromNearestSnap(o.angleDegrees)
        if (abs(rotation) > ROTATION_TOLERANCE_DEG * factor) {
            // 글자 줄이 이미지에서 시계 방향으로 돌아가 있으면(양수, ML Kit 규약 가정 — 실기기
            // 확인 필요) 폰을 반시계(왼쪽)로 돌려야 한다. 반대로 나오면 ROTATION_SIGN 만 뒤집는다.
            val turnLeft = rotation * ROTATION_SIGN > 0f
            return Problem(
                STATUS_ROTATED,
                if (turnLeft) GuidanceDirection.ROLL_TURN_LEFT.utterance else GuidanceDirection.ROLL_TURN_RIGHT.utterance,
            )
        }

        if (o.glareFraction > GLARE_MAX * factor) return Problem(STATUS_GLARE, GLARE_UTTERANCE)
        return null
    }

    private fun searching(nowMs: Long): Outcome {
        val since = searchingSinceMs ?: nowMs.also { searchingSinceMs = it }
        val utterance = if (nowMs - since >= SEARCH_HINT_AFTER_MS) {
            speakIfDue(SEARCHING_UTTERANCE, nowMs)
        } else {
            null
        }
        if (utterance != null) lastInstructionAtMs = nowMs
        return Outcome(utterance = utterance, statusText = STATUS_SEARCHING)
    }

    /** 같은 문구는 [REPEAT_MS], 바뀐 문구는 [MIN_GAP_MS] 간격 — 통과하면 문구, 아니면 null. */
    private fun speakIfDue(text: String, nowMs: Long): String? {
        val elapsed = nowMs - lastSpokenAtMs
        val due = if (text == lastUtterance) elapsed >= REPEAT_MS else elapsed >= MIN_GAP_MS
        if (!due) return null
        lastUtterance = text
        lastSpokenAtMs = nowMs
        return text
    }

    companion object {
        /** 이보다 오래된 관측은 없는 것으로 본다 (인식 주기 ~350ms 의 3배 남짓). */
        const val FRESH_MS = 1_200L
        /** 서류로 인정할 최소 글자 줄 수 — 한 줄은 간판·라벨일 수 있다. */
        const val MIN_LINES = 2
        /** 글자 영역이 가장자리에서 이 안이면 잘린 것으로 본다. */
        const val EDGE_MARGIN = 0.015f
        /** 글자 영역이 프레임의 이 비율 미만이면 줌인으로 채운다 (여유가 있을 때). */
        const val ZOOM_TARGET_FILL = 0.45f
        /** 줌을 다 써도 이 비율 미만이면 "가까이" — 글자가 읽힐 해상도 하한. */
        const val MIN_FILL = 0.30f
        /** 중심이 이 이상 벗어나면 옮기라고 한다 (프레임 단위). */
        const val POSITION_TOLERANCE = 0.12f
        /**
         * 글자 높이 기울기 기반 수직 안내 on/off — **off** (2026-08-30 실기기: 제목·본문 글자 크기
         * 차이를 기울기로 오인해 정면인데도 "기울여 주세요"가 나왔다). 정면은 사용자가 맞춘다.
         * 모서리 검출이 붙으면 사다리꼴 비율로 다시 켠다.
         */
        const val TILT_GUIDANCE_ENABLED = false
        const val TILT_TOLERANCE = 0.18f
        /** 글자 줄 회전 허용치(도, 스냅 편차). */
        const val ROTATION_TOLERANCE_DEG = 5f
        /** ML Kit 줄 각도 → 돌릴 방향 부호. 실기기에서 반대로 안내되면 이것만 뒤집는다. */
        const val ROTATION_SIGN = 1f
        /** 서류 영역 포화 픽셀 비율 상한 — 이 이상이면 반사로 본다. */
        const val GLARE_MAX = 0.08f
        /** 통과 상태에서의 허용치 완화 배수 (히스테리시스). */
        const val RELAX_FACTOR = 1.5f
        /**
         * "좋아요" 발화 시점부터, 그리고 정지 시작부터 이만큼 유지되면 촬영 — 발화(약 2초)가
         * 끝난 뒤에 셔터가 나게 하는 길이.
         */
        const val HOLD_MS = 2_500L
        /** 교정 안내를 말한 뒤 이만큼은 "좋아요"를 내지 않는다 — 안내 위에 겹치지 않게. */
        const val SETTLE_AFTER_INSTRUCTION_MS = 2_500L
        /** 서류를 이만큼 못 찾으면 탐색 안내. */
        const val SEARCH_HINT_AFTER_MS = 3_000L
        const val REPEAT_MS = 2_500L
        const val MIN_GAP_MS = 1_000L

        // ---- 문구 (SpeechCatalog 음원 없음 — 내장 TTS 폴백) ----
        const val SEARCHING_UTTERANCE = "서류가 안 보여요. 서류가 화면 가운데 오도록 폰을 움직여 주세요."
        const val FARTHER_UTTERANCE = "조금 멀리 가 주세요."
        const val CLOSER_UTTERANCE = "조금 더 가까이 가 주세요."
        const val SHIFT_LEFT_UTTERANCE = "폰을 왼쪽으로 조금 옮겨 주세요."
        const val SHIFT_RIGHT_UTTERANCE = "폰을 오른쪽으로 조금 옮겨 주세요."
        const val SHIFT_UP_UTTERANCE = "폰을 위로 조금 옮겨 주세요."
        const val SHIFT_DOWN_UTTERANCE = "폰을 아래로 조금 옮겨 주세요."
        const val TILT_TOP_TOWARD_UTTERANCE = "폰 윗부분을 서류 쪽으로 기울여 주세요."
        const val TILT_TOP_AWAY_UTTERANCE = "폰 윗부분을 바깥쪽으로 기울여 주세요."
        const val GLARE_UTTERANCE = "빛이 반사돼요. 폰을 조금 기울이거나 자리를 옮겨 주세요."
        const val READY_UTTERANCE = "좋아요. 그대로 잠시 멈춰 주세요."

        // ---- 화면 카드 상태 ----
        const val STATUS_SEARCHING = "서류를 찾는 중이에요"
        const val STATUS_TOO_CLOSE = "너무 가까워요"
        const val STATUS_CLIPPED = "서류가 잘려요"
        const val STATUS_ZOOMING_IN = "확대하는 중이에요"
        const val STATUS_ZOOMING_OUT = "축소하는 중이에요"
        const val STATUS_TOO_FAR = "조금 더 가까이"
        const val STATUS_OFF_CENTER = "가운데로 맞춰 주세요"
        const val STATUS_TILTED = "폰을 서류와 나란히"
        const val STATUS_ROTATED = "폰을 반듯하게"
        const val STATUS_GLARE = "빛 반사가 있어요"
        const val STATUS_HOLD = "좋아요, 그대로 유지해 주세요"
        const val STATUS_DONE = "촬영했어요"
    }
}
