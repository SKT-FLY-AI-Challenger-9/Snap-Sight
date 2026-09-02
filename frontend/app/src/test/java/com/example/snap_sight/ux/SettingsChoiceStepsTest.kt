package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 설정 화면의 3단계 선택([SpeechSpeed], [GridThickness]) 검증.
 *
 * 두 설정 모두 UI 는 3버튼이지만 저장값은 계속 연속값(배속 Float, 굵기 dp)이다. 예전에
 * 슬라이더로 저장해 둔 임의의 값이 들어와도 반드시 어느 한 단계로 떨어져야 버튼 하이라이트가
 * 비는 일이 없다 — 그 매핑이 이 테스트의 핵심이다.
 */
class SettingsChoiceStepsTest {

    @Test
    fun speechSpeedRoundTripsThroughItsOwnRate() {
        SpeechSpeed.entries.forEach { speed ->
            assertEquals(speed, SpeechSpeed.fromRate(speed.rate))
        }
    }

    @Test
    fun speechSpeedSnapsLegacySliderValuesToNearestStep() {
        // 예전 슬라이더 범위는 0.5f..2f 였다. 그 안의 어떤 값이든 가장 가까운 단계로 떨어진다.
        assertEquals(SpeechSpeed.SLOW, SpeechSpeed.fromRate(0.5f))
        assertEquals(SpeechSpeed.SLOW, SpeechSpeed.fromRate(0.85f))
        assertEquals(SpeechSpeed.NORMAL, SpeechSpeed.fromRate(1.1f))
        assertEquals(SpeechSpeed.FAST, SpeechSpeed.fromRate(1.4f))
        assertEquals(SpeechSpeed.FAST, SpeechSpeed.fromRate(2f))
    }

    @Test
    fun gridThicknessRoundTripsThroughItsOwnDp() {
        GridThickness.entries.forEach { thickness ->
            assertEquals(thickness, GridThickness.fromDp(thickness.dp))
        }
    }

    @Test
    fun gridThicknessSnapsLegacySliderValuesToNearestStep() {
        // 예전 슬라이더 범위는 1f..5f 였다.
        assertEquals(GridThickness.THIN, GridThickness.fromDp(1f))
        assertEquals(GridThickness.THIN, GridThickness.fromDp(2f))
        assertEquals(GridThickness.MEDIUM, GridThickness.fromDp(3.5f))
        assertEquals(GridThickness.THICK, GridThickness.fromDp(4.5f))
        assertEquals(GridThickness.THICK, GridThickness.fromDp(5f))
    }

    /** 저장된 적 없는 사용자가 보는 기본 상태가 실제로 각 enum 의 DEFAULT 와 같아야 한다. */
    @Test
    fun defaultsMatchTheStateDefaults() {
        val state = SettingsUiState(vibrationIntensity = 1f, soundVolume = 1f, speechRate = 1f)

        assertEquals(SpeechSpeed.DEFAULT, SpeechSpeed.fromRate(state.speechRate))
        assertEquals(GridThickness.DEFAULT, GridThickness.fromDp(state.gridThicknessDp))
    }
}
