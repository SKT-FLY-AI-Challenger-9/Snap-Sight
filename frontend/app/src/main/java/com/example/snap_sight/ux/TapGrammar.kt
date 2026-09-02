// 이 파일: 전역 화면 조작 문법 (#84) — 앱 어디서든 "두 번 탭=메인 기능, 세 번 탭=서브 기능,
// 길게 누르기=뒤로 가기"가 동작하는 제스처 Modifier. 한 번 탭은 일반 터치 UI 몫으로 남긴다.
// 버튼·슬라이더 등 자식이 소비한 탭은 건드리지 않아 일반 터치 조작과 공존한다.
// 화면별 "메인/서브"의 의미는 호출부(MainActivity)가 정한다.
package com.example.snap_sight.ux

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 화면 아무 곳의 탭 횟수·길게 누르기를 감지한다.
 *
 * - 두 번 탭 → [onDoubleTap] (그 화면의 메인 기능)
 * - 세 번 탭 → [onTripleTap] (그 화면의 서브 기능)
 * - 길게 누르기 → [onLongPress] (뒤로 가기)
 * - 한 번 탭 → 아무것도 하지 않음 (버튼 등 일반 터치 UI 몫)
 *
 * 자식 컴포저블(버튼 등)이 소비한 이벤트는 무시하므로 일반 터치 UI와 충돌하지 않는다.
 * 두 번 탭은 세 번째 탭이 [MULTI_TAP_WINDOW_MS] 안에 오는지 기다린 뒤 확정되고,
 * 세 번 탭은 즉시 확정된다.
 *
 * 타이밍 (실사용 피드백 2026-08-22 반영):
 *  - 길게 누르기(뒤로)는 시스템 기본(≈400ms)이 너무 민감해 [BACK_LONG_PRESS_MS]로 늘렸다
 *  - 탭 간격도 시스템 doubleTapTimeout(≈300ms)이 빠듯해 [MULTI_TAP_WINDOW_MS]로 늘렸다 —
 *    대신 두 번 탭(셔터 포함) 확정이 그만큼 늦어지는 트레이드오프가 있다
 */
fun Modifier.appTapGrammar(
    onDoubleTap: () -> Unit,
    onTripleTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Main)
        if (down.isConsumed) return@awaitEachGesture

        // 첫 손가락: 길게 누르기인지, 탭인지 판정
        var cancelled = false
        val firstUp = withTimeoutOrNull(BACK_LONG_PRESS_MS) {
            waitForUpOrCancellation().also { if (it == null) cancelled = true }
        }
        when {
            cancelled -> return@awaitEachGesture
            firstUp == null -> {
                // BACK_LONG_PRESS_MS 를 넘겼다 = 길게 누르기(뒤로). 손을 뗄 때까지 이벤트를 소진한다.
                onLongPress()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }
            firstUp.isConsumed -> return@awaitEachGesture
        }

        // 이어지는 탭을 센다 — 3탭에서 즉시 확정, 2탭은 세 번째가 안 오면 확정
        var taps = 1
        while (taps < 3) {
            val nextDown = withTimeoutOrNull(MULTI_TAP_WINDOW_MS) {
                awaitFirstDown(pass = PointerEventPass.Main)
            } ?: break
            if (nextDown.isConsumed) return@awaitEachGesture
            var nextCancelled = false
            val nextUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation().also { if (it == null) nextCancelled = true }
            }
            if (nextCancelled || nextUp == null || nextUp.isConsumed) return@awaitEachGesture
            taps++
        }
        when (taps) {
            2 -> onDoubleTap()
            3 -> onTripleTap()
        }
    }
}

/** 길게 누르기(뒤로) 판정 시간 — 시스템 기본(≈400ms)이 실사용에서 오작동이 잦아 늘렸다. */
private const val BACK_LONG_PRESS_MS = 900L

/** 연속 탭으로 인정하는 최대 간격 — 시스템 doubleTapTimeout(≈300ms)보다 여유를 뒀다. */
private const val MULTI_TAP_WINDOW_MS = 500L


/** 화면 하나의 전역 문법 액션 묶음 — [grammarClickable] 이 요소 위 탭을 이 액션들로 넘긴다. */
@Immutable
data class TapGrammarActions(
    val onDoubleTap: () -> Unit,
    val onTripleTap: () -> Unit,
    val onLongPress: () -> Unit,
)

/** null 이면 [grammarClickable] 은 일반 clickable 로 동작한다 (문법 위임 없음). */
val LocalTapGrammarActions = compositionLocalOf<TapGrammarActions?> { null }

/**
 * 클릭 요소가 화면 대부분을 덮는 화면(갤러리 등)용 클릭 Modifier (2026-08-23).
 *
 * 일반 [clickable] 은 첫 탭을 즉시 소비해 그 위에서 전역 두 번·세 번 탭이 죽는다 —
 * "빈 공간을 찾아야 문법이 된다"는 실사용 문제. 이 Modifier 는 한 번 탭(클릭)의 확정을
 * [MULTI_TAP_WINDOW_MS] 만큼 미루고, 그 안에 탭이 더 오면 클릭 대신
 * [LocalTapGrammarActions] 의 전역 문법으로 보낸다. 길게 누르기(복귀)도 동일하게 위임한다.
 *
 * 비용: 요소 클릭이 창 길이만큼 늦게 반응한다. 시각 우선 UI라면 손해지만, 이 앱은
 * "아무 곳이나 두 번 탭"이 항상 통하는 것이 우선이다.
 * TalkBack: 표준 클릭 액션(semantics onClick)은 즉시 동작한다 — TalkBack 사용자는
 * 전역 탭 문법 대신 노드 탐색을 쓰므로 지연이 필요 없다.
 */
fun Modifier.grammarClickable(onClick: () -> Unit): Modifier = composed {
    val actions = LocalTapGrammarActions.current
    if (actions == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
            .semantics {
                onClick {
                    onClick()
                    true
                }
            }
            .pointerInput(actions) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Main)
                    down.consume() // 부모 appTapGrammar 가 같은 제스처를 중복 집계하지 않게

                    var cancelled = false
                    val firstUp = withTimeoutOrNull(BACK_LONG_PRESS_MS) {
                        waitForUpOrCancellation().also { if (it == null) cancelled = true }
                    }
                    when {
                        cancelled -> return@awaitEachGesture
                        firstUp == null -> {
                            actions.onLongPress()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                            } while (event.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        else -> firstUp.consume()
                    }

                    var taps = 1
                    while (taps < 3) {
                        val nextDown = withTimeoutOrNull(MULTI_TAP_WINDOW_MS) {
                            awaitFirstDown(pass = PointerEventPass.Main)
                        } ?: break
                        nextDown.consume()
                        var nextCancelled = false
                        val nextUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation().also { if (it == null) nextCancelled = true }
                        }
                        if (nextCancelled || nextUp == null) return@awaitEachGesture
                        nextUp.consume()
                        taps++
                    }
                    when (taps) {
                        1 -> onClick()
                        2 -> actions.onDoubleTap()
                        3 -> actions.onTripleTap()
                    }
                }
            }
    }
}
