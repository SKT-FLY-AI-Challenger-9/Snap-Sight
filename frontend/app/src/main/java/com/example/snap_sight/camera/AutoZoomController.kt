// 이 파일: 타겟이 너무 작으면(20% 미만) 줌인, 너무 크면(60% 초과) 줌아웃해 40%에 맞춘다.
// 면적은 줌의 제곱에 비례하므로 필요 줌 = 현재줌 × √(목표면적/현재면적).
package com.example.snap_sight.camera

class AutoZoomController(private val cameraController: CameraController) {

    @Volatile
    private var lastZoomAtMs = 0L

    // CV 분석 스레드에서 매 프레임 호출된다. 쿨다운으로 줌 진동을 막는다.
    fun onTargetArea(areaRatio: Float) {
        if (areaRatio <= 0f) return
        if (areaRatio in TRIGGER_MIN_AREA..TRIGGER_MAX_AREA) return
        val now = System.currentTimeMillis()
        if (now - lastZoomAtMs < COOLDOWN_MS) return
        lastZoomAtMs = now
        cameraController.setZoomRatio(requiredZoom(cameraController.zoomRatio, areaRatio))
    }

    // 세션 시작 시 호출 — 이전 세션의 줌을 원상 복귀한다.
    fun reset() {
        lastZoomAtMs = 0L
        cameraController.setZoomRatio(1f)
    }

    companion object {
        const val TRIGGER_MIN_AREA = 0.20f
        const val TRIGGER_MAX_AREA = 0.60f
        const val TARGET_AREA = 0.40f
        const val COOLDOWN_MS = 2_000L

        internal fun requiredZoom(currentZoom: Float, areaRatio: Float): Float =
            currentZoom * kotlin.math.sqrt(TARGET_AREA / areaRatio)
    }
}
