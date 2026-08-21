package com.example.snap_sight.cv

/**
 * ② CV 파이프라인 뒤에 붙는 얼굴 신원 분석 훅 (기능 2).
 *
 * [SnapSightFrameProcessor] 가 분석 프레임마다 tracking 결과와 함께 **동기로** 호출한다
 * (CameraX 분석 스레드 — 오래 걸리면 다음 프레임이 드롭되므로 구현체가 스스로 스로틀링할 것).
 * [CvFrame] 버퍼는 호출이 리턴한 뒤 재사용되므로 밖으로 들고 나가면 안 된다 — 필요한
 * 영역은 호출 안에서 복사해야 한다.
 *
 * cv 패키지는 얼굴 구현(face 패키지)을 모른다 — 의존 방향: face → cv.
 */
fun interface FaceFrameAnalyzer {
    /** @return 이번 프레임에서 신원이 확인된 track_id → 등록 인물 이름. */
    fun analyze(frame: CvFrame, frameResult: FrameResult): Map<Int, String>

    /** 새 촬영 세션 — track_id 가 재시작되므로 신원 바인딩을 지운다. */
    fun reset() {}
}
