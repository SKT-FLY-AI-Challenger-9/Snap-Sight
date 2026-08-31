// 이 파일: 폰 좌우 기울기(roll)의 공용 규약 — 풍경 안내([com.example.snap_sight.ux.LandscapeGuide]),
// 인물·사물 세션 안내([com.example.snap_sight.ux.GuidancePolicy]), 저장 시 수평 보정
// ([HorizonStraightener])이 같은 스냅 편차·임계값을 쓰도록 한 곳에 모은다 (2026-08-30).
package com.example.snap_sight.camera

/**
 * [TiltSensorMonitor.rollDegrees] 규약 (실기기 확정 2026-08-28): 폰을 **왼쪽(반시계)으로
 * 돌리면 roll 이 + 로 커진다**. 따라서 +편차 = 왼쪽으로 지나침 → 오른쪽으로 되돌리기.
 *
 * 가로 파지도 정상 촬영 자세다 (실기기 2026-08-28: 가로로 돌리자 계속 기울었다고 안내) —
 * 절대 0° 가 아니라 **가장 가까운 스냅(0°/±90°/180°)으로부터의 편차**만 기울어짐으로 본다.
 */
object PhoneRoll {

    /** 이 이상 기울면 수평 안내를 시작한다 (실기기 감으로 튜닝, 2026-08-28). */
    const val ENTER_DEG = 6f

    /** 안내 중 이 안으로 돌아오면 "수평이 맞았어요" 후 종료 (히스테리시스). */
    const val EXIT_DEG = 2.5f

    /** roll 을 가장 가까운 파지 스냅(0/±90/180°)으로부터의 편차로 정규화 (-45..45). */
    fun deviationFromNearestSnap(rollDegrees: Float): Float {
        val snapped = Math.round(rollDegrees / 90f) * 90f
        return rollDegrees - snapped
    }
}
