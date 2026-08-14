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

    /**
     * 사진이 기기 저장소(MediaStore)에 저장 완료됨.
     * 이 Uri 를 대표 컷으로 백엔드 업로드(network.FrameUploader)에 넘긴다.
     */
    fun onPhotoSaved(uri: Uri)

    /** 촬영 실패. ⑥이 사용자에게 음성/진동으로 알려야 함. */
    fun onCaptureError(error: Throwable)
}