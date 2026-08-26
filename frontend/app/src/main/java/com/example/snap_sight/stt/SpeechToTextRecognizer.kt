package com.example.snap_sight.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
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

    // 녹음 시작 신호(사용자 요청 2026-08-26) — 지금 듣고 있는지 알 방법이 없어서 추가.
    // 지연 생성해 실제로 인식을 한 번도 안 쓰면 리소스를 만들지 않는다.
    private val toneGenerator: ToneGenerator? by lazy {
        // STREAM_NOTIFICATION은 기기별로 무음/저볼륨인 경우가 많아 안 들릴 수 있다 — 다른
        // earcon(GuidanceFeedback)과 같은 STREAM_MUSIC으로 통일한다 (실사용 피드백 2026-08-26,
        // "모든 STT 부분에서 띠링 안 나오잖아").
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, LISTENING_TONE_VOLUME) }
            .onFailure { Log.w(TAG, "녹음 시작 신호음 생성 실패 — 신호음 없이 진행", it) }
            .getOrNull()
    }

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

        override fun onReadyForSpeech(params: Bundle?) {
            // start() 호출 시점이 아니라 마이크가 실제로 열려 듣기 시작하는 시점 — 여기서
            // 울려야 "지금부터 말하면 된다"는 신호로 정확하다.
            runCatching { toneGenerator?.startTone(LISTENING_START_TONE, LISTENING_TONE_MS) }
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            // 마이크가 닫히는 시점(결과 처리 시작 직전) — "이제 그만 들어요" 신호. 시작음과
            // 다른 톤이라 시작/끝을 구분해서 들을 수 있다 (사용자 요청 2026-08-27).
            runCatching { toneGenerator?.startTone(LISTENING_END_TONE, LISTENING_TONE_MS) }
        }
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
        // ACK/BEEP는 카메라 등록 스캔 시작·종료음(GuidanceFeedback.SCAN_START_TONE/
        // SCAN_END_TONE)과 완전히 같은 톤이라 헷갈렸다 — STT 전용으로 겹치지 않는 톤을 쓴다
        // (사용자 요청 2026-08-27, "카메라에서 쓰이는 음성이랑 너무 똑같다").
        val LISTENING_START_TONE = ToneGenerator.TONE_PROP_PROMPT
        val LISTENING_END_TONE = ToneGenerator.TONE_SUP_RADIO_ACK
        const val LISTENING_TONE_MS = 120
        // 70 -> 90으로 키움 (사용자 요청 2026-08-27 — "소리 좀만 키워줘")
        const val LISTENING_TONE_VOLUME = 90
    }
}
