package com.example.snap_sight.camera.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ① STT 파이프라인용 마이크 녹음기.
 *
 * 클라우드 STT(구글/네이버/AWS 등)가 공통으로 받는 표준 포맷인
 * 16kHz / mono / 16bit PCM WAV 파일을 만든다.
 * ①은 [stop] 이 리턴한 File 을 그대로 STT API 에 업로드하면 된다.
 *
 * 사용: start(outputFile) → ... → stop()  (RECORD_AUDIO 권한 필요)
 */
class WavAudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var writerThread: Thread? = null

    @Volatile
    private var recording = false

    private var outputFile: File? = null

    val isRecording: Boolean get() = recording

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(output: File): Boolean {
        if (recording) return false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "이 기기는 16kHz mono PCM 녹음을 지원하지 않음")
            return false
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // 음성인식 최적화 소스 (AGC/NS 프로파일)
            SAMPLE_RATE, CHANNEL_IN, ENCODING, minBuffer * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 초기화 실패")
            record.release()
            return false
        }

        audioRecord = record
        outputFile = output
        recording = true
        record.startRecording()

        writerThread = Thread({ writeLoop(record, output, minBuffer * 2) }, "SnapSight-AudioWriter")
            .also { it.start() }
        return true
    }

    private fun writeLoop(record: AudioRecord, output: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        FileOutputStream(output).use { fos ->
            // WAV 헤더 자리 확보 (녹음 종료 후 실제 크기로 덮어씀)
            fos.write(ByteArray(WAV_HEADER_SIZE))
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) fos.write(buffer, 0, read)
            }
        }
        writeWavHeader(output)
    }

    /** 녹음을 종료하고 완성된 WAV 파일을 리턴한다. 실패/미녹음 상태면 null. */
    fun stop(): File? {
        if (!recording) return null
        recording = false
        writerThread?.join(2000)
        writerThread = null

        audioRecord?.run {
            try {
                stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop 실패", e)
            }
            release()
        }
        audioRecord = null

        val file = outputFile
        outputFile = null
        return file?.takeIf { it.length() > WAV_HEADER_SIZE }
    }

    private fun writeWavHeader(file: File) {
        val totalLen = file.length()
        val dataLen = totalLen - WAV_HEADER_SIZE
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((totalLen - 8).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                            // fmt 청크 크기
            putShort(1)                           // PCM
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray())
            putInt(dataLen.toInt())
        }

        RandomAccessFile(file, "rw").use {
            it.seek(0)
            it.write(header.array())
        }
    }

    private companion object {
        const val TAG = "WavAudioRecorder"
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val WAV_HEADER_SIZE = 44
    }
}
