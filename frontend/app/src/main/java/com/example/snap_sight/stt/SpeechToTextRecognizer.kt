package com.example.snap_sight.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * ① STT 파이프라인 — Android 내장 [SpeechRecognizer]를 사용한 온디바이스 음성 인식.
 *
 * 클라우드 API(Clova CSR 등) 대신 OS 제공 인식기를 사용해 지연을 줄이고 네트워크 의존을 낮춘다
 * ([EXTRA_PREFER_OFFLINE]로 기기가 지원하면 오프라인 인식 우선 시도).
 * 결과/오류는 전부 [Listener] 콜백으로 비동기 전달되며, [WavAudioRecorder]처럼 파일을 만들지 않는다 —
 * 인식된 텍스트를 그대로 백엔드로 보내면 됨 (백엔드의 ai/slot_parser.py가 텍스트 → 타겟 스펙 변환).
 *
 * 사용: start(listener) → (필요 시 stop()으로 조기 종료) → onRecognized/onError 콜백 수신
 * 반드시 메인 스레드(Looper 있는 스레드)에서 생성·호출해야 한다. (RECORD_AUDIO 권한 필요)
 */
class SpeechToTextRecognizer(private val context: Context) {

    interface Listener {
        /** 인식 성공. [text]는 공백이 아님이 보장됨. */
        fun onRecognized(text: String)

        /** 인식 실패 (무음/네트워크 오류/권한 없음 등). [message]는 사용자에게 그대로 보여줘도 되는 문구. */
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(listener: Listener) {
        release()

        if (!isAvailable) {
            listener.onError("이 기기는 음성 인식을 지원하지 않습니다.")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        this.recognizer = recognizer
        recognizer.setRecognitionListener(RecognitionListenerAdapter(listener))

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
    }

    /** 발화 종료 요청 (볼륨 버튼). 결과는 [Listener.onRecognized]/[onError]로 뒤이어 비동기 전달됨. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** 세션 취소. 콜백 없이 즉시 정리한다. */
    fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private inner class RecognitionListenerAdapter(
        private val listener: Listener,
    ) : RecognitionListener {

        override fun onResults(results: Bundle) {
            val text = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrEmpty()) {
                listener.onError("인식된 텍스트가 없습니다.")
            } else {
                listener.onRecognized(text)
            }
            release()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "인식 오류: $error")
            listener.onError(errorMessage(error))
            release()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "인식된 텍스트가 없습니다."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "발화 시간이 초과됐습니다."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 오류가 발생했습니다."
        SpeechRecognizer.ERROR_AUDIO -> "오디오 녹음 중 오류가 발생했습니다."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기가 사용 중입니다."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다."
        else -> "음성 인식에 실패했습니다. (오류 코드: $error)"
    }

    private companion object {
        const val TAG = "SpeechToTextRecognizer"
    }
}
