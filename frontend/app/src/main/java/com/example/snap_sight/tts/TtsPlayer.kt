package com.example.snap_sight.tts

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * [TtsClient][com.example.snap_sight.network.TtsClient]가 받아온 mp3 바이트를 재생한다.
 *
 * [MediaPlayer]는 파일 경로나 스트림에서만 재생 가능해, 바이트 배열을 캐시 파일에 잠깐
 * 써두고 재생한 뒤 정리한다. 재생 도중 새 요청이 오면 이전 재생을 멈추고 새로 시작한다 —
 * 겹쳐 말하면 안 되는 건 TalkBack과의 중첩 방지 원칙(docs/ux/talkback-behavior.md)과
 * 같은 이유다. [prepareAsync]를 써서 메인 스레드를 막지 않는다.
 */
class TtsPlayer(private val cacheDir: File) {

    private var mediaPlayer: MediaPlayer? = null
    private var currentFile: File? = null

    fun play(audioBytes: ByteArray) {
        stop()

        val file = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        currentFile = file
        try {
            FileOutputStream(file).use { it.write(audioBytes) }
        } catch (e: Exception) {
            Log.e(TAG, "TTS 오디오 파일 쓰기 실패", e)
            return
        }

        val player = MediaPlayer()
        mediaPlayer = player
        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener { finished ->
            finished.release()
            if (mediaPlayer === finished) mediaPlayer = null
            if (currentFile === file) currentFile = null
            file.delete()
        }
        player.setOnErrorListener { failed, what, extra ->
            Log.e(TAG, "TTS 재생 오류: what=$what extra=$extra")
            failed.release()
            if (mediaPlayer === failed) mediaPlayer = null
            if (currentFile === file) currentFile = null
            file.delete()
            true
        }

        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "TTS 재생 준비 실패", e)
            player.release()
            mediaPlayer = null
            currentFile = null
            file.delete()
        }
    }

    /**
     * 재생 중이면 즉시 멈추고 리소스를 정리한다. 새 안내가 이전 안내와 겹치지 않도록 호출.
     *
     * 자연 종료·에러 시엔 각 리스너가 자신의 캐시 파일을 지우지만, 이렇게 중간에 끊기는
     * 경우엔 그 리스너들이 아예 안 불리므로 여기서 직접 [currentFile]을 지워야 한다 —
     * 안 그러면 끊길 때마다 캐시 파일이 하나씩 남는다.
     */
    fun stop() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "TTS 정지 중 오류", e)
            }
            it.release()
        }
        mediaPlayer = null
        currentFile?.delete()
        currentFile = null
    }

    private companion object {
        const val TAG = "TtsPlayer"
    }
}
