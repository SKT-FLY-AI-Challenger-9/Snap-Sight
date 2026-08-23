// 이 파일: 촬영 중 생기는 일(셔터 눌림, 사진 저장됨, 실패)을 알려주는 알림 통로.
// 실제 코드는 없고 "이런 알림을 받겠다"는 약속(인터페이스)만 정의한다.
// 다른 파트(업로드·소리 담당)가 이 약속을 구현해서 알림을 받아 간다.
package com.example.snap_sight.camera

import android.net.Uri

/**
 * 촬영 결과를 받는 쪽([④ 백엔드 저장/MLLM] 전송 로직 또는 [⑥ UX 피드백])이 구현하는 계약.
 *
 * 모든 콜백은 메인 스레드에서 호출된다.
 */
interface CaptureEventListener {

    /** 셔터가 실제로 동작한 시점. ⑥이 여기서 셔터 사운드/진동을 재생하면 됨. */
    fun onShutter() {}

    /** 세션을 구분할 수 있는 확장 콜백. 기존 구현은 위 콜백으로 자동 위임된다. */
    fun onShutter(sessionId: String?) = onShutter()

    /**
     * 사진이 기기 저장소(MediaStore)에 저장 완료됨.
     * 이 Uri 를 대표 컷으로 백엔드 업로드(network.FrameUploader)에 넘긴다.
     */
    fun onPhotoSaved(uri: Uri)

    /** 늦게 도착한 저장 콜백을 걸러내기 위한 세션 확장 콜백. */
    fun onPhotoSaved(sessionId: String?, uri: Uri) = onPhotoSaved(uri)

    /** 촬영 실패. ⑥이 사용자에게 음성/진동으로 알려야 함. */
    fun onCaptureError(error: Throwable)

    /** 늦게 도착한 오류 콜백을 걸러내기 위한 세션 확장 콜백. */
    fun onCaptureError(sessionId: String?, error: Throwable) = onCaptureError(error)
}
