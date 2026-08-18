package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GuidanceFeedbackSettingsMapper] — S5 설정값 → 실제 재생값 계산 검증.
 * clamp 경계값, 진동 강도 0(무음) 처리, TTS speechRate 반영을 다룬다.
 */
class GuidanceFeedbackSettingsMapperTest {

    private fun settings(vibration: Float = 1f, sound: Float = 1f, speechRate: Float = 1f) =
        SettingsUiState(vibrationIntensity = vibration, soundVolume = sound, speechRate = speechRate)

    // --- clampVibrationIntensity ---

    @Test
    fun clampWithinRangeIsUnchanged() {
        assertEquals(0.5f, GuidanceFeedbackSettingsMapper.clampVibrationIntensity(0.5f), 1e-6f)
    }

    @Test
    fun clampBelowZeroBecomesZero() {
        assertEquals(0f, GuidanceFeedbackSettingsMapper.clampVibrationIntensity(-0.3f), 1e-6f)
    }

    @Test
    fun clampAboveOneBecomesOne() {
        assertEquals(1f, GuidanceFeedbackSettingsMapper.clampVibrationIntensity(1.7f), 1e-6f)
    }

    // --- vibrationAmplitude (진동 0 = 무음 처리) ---

    @Test
    fun zeroIntensityReturnsNull() {
        assertNull(GuidanceFeedbackSettingsMapper.vibrationAmplitude(0f))
    }

    @Test
    fun negativeIntensityClampsToZeroAndReturnsNull() {
        assertNull(GuidanceFeedbackSettingsMapper.vibrationAmplitude(-1f))
    }

    @Test
    fun maxIntensityReturns255() {
        assertEquals(255, GuidanceFeedbackSettingsMapper.vibrationAmplitude(1f))
    }

    @Test
    fun aboveMaxIntensityClampsTo255() {
        assertEquals(255, GuidanceFeedbackSettingsMapper.vibrationAmplitude(2f))
    }

    @Test
    fun midIntensityMapsProportionally() {
        // 0.5 * 255 = 127.5 -> toInt() = 127
        assertEquals(127, GuidanceFeedbackSettingsMapper.vibrationAmplitude(0.5f))
    }

    @Test
    fun verySmallPositiveIntensityStillAtLeastOne() {
        // (0.001 * 255).toInt() = 0 -> coerceIn(1, 255) 로 1까지 올라와야 함 (완전 무음과 구분)
        assertEquals(1, GuidanceFeedbackSettingsMapper.vibrationAmplitude(0.001f))
    }

    // --- speechRate ---

    @Test
    fun speechRatePassesThroughUnmodified() {
        assertEquals(1.5f, GuidanceFeedbackSettingsMapper.speechRate(settings(speechRate = 1.5f)), 1e-6f)
        assertEquals(0.5f, GuidanceFeedbackSettingsMapper.speechRate(settings(speechRate = 0.5f)), 1e-6f)
    }

    // --- toneVolume ---

    @Test
    fun toneVolumeZeroIsNull() {
        assertNull(GuidanceFeedbackSettingsMapper.toneVolume(0f))
        assertNull(GuidanceFeedbackSettingsMapper.toneVolume(-1f))
    }

    @Test
    fun toneVolumeScalesToPercentAndClamps() {
        assertEquals(100, GuidanceFeedbackSettingsMapper.toneVolume(1f))
        assertEquals(50, GuidanceFeedbackSettingsMapper.toneVolume(0.5f))
        assertEquals(1, GuidanceFeedbackSettingsMapper.toneVolume(0.001f))
        assertEquals(100, GuidanceFeedbackSettingsMapper.toneVolume(3f))
    }
}
