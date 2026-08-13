package com.example.snap_sight.cv

import androidx.camera.core.ImageProxy

/**
 * [② 온디바이스 CV 담당] 이 구현하는 계약.
 *
 * ⑤(카메라 통합)가 CameraX ImageAnalysis 스트림에서 프레임을 뽑아
 * 이 인터페이스로 흘려보낸다. ②는 여기서 얼굴/사물 위치를 추론해서
 * ③(판정 로직)으로 넘기면 된다.
 *
 * 스레딩 계약:
 *  - [onFrame] 은 카메라 분석 전용 스레드(단일)에서 호출된다. 메인 스레드 아님.
 *  - [onFrame] 이 리턴하면 ⑤가 ImageProxy 를 close 한다.
 *    → 구현체는 onFrame 안에서 동기적으로 추론을 끝내거나,
 *      필요한 데이터(ByteBuffer/Bitmap)를 복사한 뒤 리턴해야 한다.
 *      ImageProxy 참조를 밖으로 들고 나가면 안 됨.
 *  - 백프레셔는 KEEP_ONLY_LATEST: 추론이 느리면 중간 프레임은 자동으로 스킵된다.
 *    즉 onFrame 은 "가장 최신 프레임"만 받는다고 가정하면 된다.
 */
interface FrameProcessor {

    /**
     * @param image           YUV_420_888 포맷의 카메라 프레임. 리턴 후 ⑤가 close 함.
     * @param rotationDegrees 이미지를 정방향으로 만들기 위해 시계방향으로 돌려야 하는 각도 (0/90/180/270).
     */
    fun onFrame(image: ImageProxy, rotationDegrees: Int)

    /** 카메라 파이프라인에 연결된 직후 1회 호출. 모델 로딩 등 준비 작업용. */
    fun onAttached() {}

    /** 파이프라인에서 분리될 때 호출. 모델/리소스 해제용. */
    fun onDetached() {}
}
