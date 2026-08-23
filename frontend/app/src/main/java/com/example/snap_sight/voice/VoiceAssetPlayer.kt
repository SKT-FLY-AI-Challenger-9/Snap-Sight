package com.example.snap_sight.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log

/**
 * 미리 구워둔 안내 음원을 assets에서 바로 재생한다.
 *
 * [TtsPlayer][com.example.snap_sight.tts.TtsPlayer]와 달리 임시 파일을 만들지 않는다 —
 * 음원이 이미 apk 안에 있으므로 [android.content.res.AssetFileDescriptor]로 직접 연다.
 * 그래서 디스크 쓰기도, 정리해야 할 찌꺼기 파일도 없다.
 *
 * 한 번에 하나만 재생한다. 새 재생 요청이 오면 이전 것을 멈추고 그 완료 콜백을 즉시
 * 호출한다 — 안내가 끝나기를 기다리는 쪽(마이크 게이트 등)이 영영 안 열리면 안 되기
 * 때문이다. 이 규칙은 GuidanceFeedback의 utterance 콜백 계약과 같다.
 *
 * 모든 공개 메서드는 메인 스레드에서 호출해야 한다.
 */
class VoiceAssetPlayer(context: Context) {

    private val appContext = context.applicationContext

    private var player: MediaPlayer? = null
    private var activeCompletion: ((Boolean) -> Unit)? = null

    /**
     * [assetPath]를 재생한다.
     *
     * @param speechRate 1f = 원래 속도. 기기가 지원하지 않으면 무시되고 원래 속도로 난다.
     * @param onComplete 정상 종료(true) 또는 중단·실패(false) 시 정확히 한 번 호출된다.
     * @return 재생을 시작했으면 true. false면 호출부가 시스템 TTS로 넘겨야 한다
     *         (이 경우 [onComplete]는 호출되지 않는다).
     */
    fun play(
        assetPath: String,
        speechRate: Float = 1f,
        onComplete: (Boolean) -> Unit = {},
    ): Boolean {
        stop()

        val created = MediaPlayer()
        try {
            appContext.assets.openFd(assetPath).use { descriptor ->
                created.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length,
                )
            }
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "안내 음원을 열 수 없습니다: $assetPath", e)
            created.release()
            return false
        }

        player = created
        activeCompletion = onComplete

        created.setOnPreparedListener { prepared ->
            applySpeechRate(prepared, speechRate)
            runCatching { prepared.start() }.onFailure {
                Log.w(TAG, "안내 음원 재생 시작 실패: $assetPath", it)
                finish(prepared, completed = false)
            }
        }
        created.setOnCompletionListener { finished -> finish(finished, completed = true) }
        created.setOnErrorListener { failed, what, extra ->
            Log.w(TAG, "안내 음원 재생 오류: $assetPath what=$what extra=$extra")
            finish(failed, completed = false)
            true
        }

        return try {
            created.prepareAsync()
            true
        } catch (e: Exception) {
            Log.w(TAG, "안내 음원 준비 실패: $assetPath", e)
            finish(created, completed = false)
            false
        }
    }

    /** 재생 중이면 멈추고 완료 콜백을 중단(false)으로 호출한다. */
    fun stop() {
        val current = player ?: return
        finish(current, completed = false)
    }

    /** Activity onDestroy 등에서 호출. 콜백 없이 자원만 정리한다. */
    fun release() {
        activeCompletion = null
        player?.let { releaseQuietly(it) }
        player = null
    }

    private fun applySpeechRate(target: MediaPlayer, speechRate: Float) {
        if (speechRate == 1f) return
        // PlaybackParams 는 API 23+. minSdk 26 이라 항상 있지만, 기기에 따라 던질 수 있다.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            target.playbackParams = target.playbackParams.setSpeed(
                speechRate.coerceIn(MIN_RATE, MAX_RATE)
            )
        }.onFailure {
            Log.w(TAG, "재생 속도 적용 실패 — 기본 속도로 재생합니다", it)
        }
    }

    /** 한 재생의 수명을 끝낸다. 콜백은 정확히 한 번만 나간다. */
    private fun finish(target: MediaPlayer, completed: Boolean) {
        val isCurrent = player === target
        releaseQuietly(target)
        if (!isCurrent) return

        player = null
        val callback = activeCompletion
        activeCompletion = null
        callback?.invoke(completed)
    }

    private fun releaseQuietly(target: MediaPlayer) {
        runCatching {
            if (target.isPlaying) target.stop()
        }.onFailure { Log.w(TAG, "안내 음원 정지 중 오류", it) }
        runCatching { target.release() }
    }

    private companion object {
        const val TAG = "VoiceAssetPlayer"
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
    }
}
