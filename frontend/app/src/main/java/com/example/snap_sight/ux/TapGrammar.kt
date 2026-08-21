// 이 파일: 전역 화면 조작 문법 (#84) — 앱 어디서든 "한 번 탭=메인 기능, 두 번 탭=뒤로,
// 세 번 탭=서브 기능, 길게 누르기=뒤로(보조)"가 동작하는 제스처 Modifier.
// 버튼·슬라이더 등 자식이 소비한 탭은 건드리지 않아 일반 터치 조작과 공존한다.
// 화면별 "메인/서브"의 의미는 호출부(MainActivity)가 정한다.
package com.example.snap_sight.ux

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 화면 아무 곳의 탭 횟수·길게 누르기를 감지한다.
 *
 * - 한 번 탭 → [onSingleTap] (그 화면의 메인 기능)
 * - 두 번 탭 → [onDoubleTap] (뒤로 가기)
 * - 세 번 탭 → [onTripleTap] (그 화면의 서브 기능)
 * - 길게 누르기 → [onLongPress] (뒤로 가기 보조 경로)
 *
 * 자식 컴포저블(버튼 등)이 소비한 이벤트는 무시하므로 일반 터치 UI와 충돌하지 않는다.
 * 탭 횟수는 다음 탭이 doubleTapTimeout(약 300ms) 안에 오는지로 확정한다 — 한 번/두 번 탭은
 * 그만큼 늦게 확정되는 트레이드오프가 있고, 세 번 탭은 즉시 확정된다.
 */
fun Modifier.appTapGrammar(
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onTripleTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Main)
        if (down.isConsumed) return@awaitEachGesture

        // 첫 손가락: 길게 누르기인지, 탭인지 판정
        var cancelled = false
        val firstUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation().also { if (it == null) cancelled = true }
        }
        when {
            cancelled -> return@awaitEachGesture
            firstUp == null -> {
                // longPressTimeout 을 넘겼다 = 길게 누르기. 손을 뗄 때까지 이벤트를 소진한다.
                onLongPress()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }
            firstUp.isConsumed -> return@awaitEachGesture
        }

        // 이어지는 탭을 센다 — 3탭에서 즉시 확정, 1·2탭은 다음 탭이 안 오면 확정
        var taps = 1
        while (taps < 3) {
            val nextDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
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
            1 -> onSingleTap()
            2 -> onDoubleTap()
            3 -> onTripleTap()
        }
    }
}
