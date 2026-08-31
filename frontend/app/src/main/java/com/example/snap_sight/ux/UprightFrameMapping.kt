// 이 파일: 정방향 분석 프레임 좌표 → 화면(세로 고정 미리보기) 좌표 변환 (2026-08-31 가로모드).
// 분석 스트림이 기기 방향을 따라가면서(CameraController 참고) CV·서류 좌표는 "사용자 기준
// 정방향" 프레임의 정규화 좌표가 됐다. 화면 미리보기는 여전히 세로 프레임을 보여주므로,
// 오버레이를 그릴 때는 이 역변환으로 세로 프레임 좌표로 되돌려야 상자가 피사체 위에 겹친다.
// android.* 의존이 없어 JVM 단위 테스트한다 (회전 상수 값은 android.view.Surface 와 동일).
package com.example.snap_sight.ux

object UprightFrameMapping {

    // android.view.Surface.ROTATION_* 과 같은 값 — android 의존 없이 테스트하려고 복제
    private const val ROTATION_90 = 1
    private const val ROTATION_180 = 2
    private const val ROTATION_270 = 3

    /**
     * 정방향 프레임의 정규화 점(0..1)을 화면에 보이는 세로 프레임의 정규화 점으로 되돌린다.
     *
     * 유도: ROTATION_90(기기 상단이 왼쪽)은 사용자 기준 위쪽 = 세로 프레임의 오른쪽 —
     * 정방향 프레임을 세로 프레임 위에 얹으면 반시계 90° 관계라 (x,y) → (1−y, x).
     * ROTATION_270 은 그 역, ROTATION_180 은 점대칭.
     *
     * @param mirrored 전면(셀카) 미리보기 좌우 반전 — 화면 좌표 기준이므로 회전 뒤에 적용한다
     */
    fun toDisplayPoint(
        x: Float,
        y: Float,
        surfaceRotation: Int,
        mirrored: Boolean = false,
    ): Pair<Float, Float> {
        val (px, py) = when (surfaceRotation) {
            ROTATION_90 -> (1f - y) to x
            ROTATION_180 -> (1f - x) to (1f - y)
            ROTATION_270 -> y to (1f - x)
            else -> x to y
        }
        return (if (mirrored) 1f - px else px) to py
    }
}
