package com.example.snap_sight.ux

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * [SettingsScreen] 현재값 표시(라벨 텍스트 + Slider contentDescription)와 [onBack] 콜백을 검증한다.
 * 값 영속화·GuidanceFeedback 실제 반영은 범위 밖(SettingsScreen.kt 상단 KDoc 참고) — 다루지 않는다.
 */
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val state = SettingsUiState(
        vibrationIntensity = 0.7f,
        soundVolume = 0.3f,
        speechRate = 1f,
    )

    @Test
    fun labelsShowCurrentValueAsPercentage() {
        composeTestRule.setContent {
            SettingsScreen(
                state = state,
                onVibrationIntensityChange = {},
                onSoundVolumeChange = {},
                onSpeechRateChange = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("진동 강도, 현재 70퍼센트").assertIsDisplayed()
        composeTestRule.onNodeWithText("사운드 강도, 현재 30퍼센트").assertIsDisplayed()
        composeTestRule.onNodeWithText("음성 속도, 현재 100퍼센트 속도").assertIsDisplayed()
    }

    @Test
    fun slidersExposeCurrentValueInContentDescription() {
        composeTestRule.setContent {
            SettingsScreen(
                state = state,
                onVibrationIntensityChange = {},
                onSoundVolumeChange = {},
                onSpeechRateChange = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("진동 강도 조절, 현재 70퍼센트").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("사운드 강도 조절, 현재 30퍼센트").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("음성 속도 조절, 현재 100퍼센트 속도").assertIsDisplayed()
    }

    @Test
    fun backButtonHasAccessibleDescriptionAndInvokesCallback() {
        var backPressed = false
        composeTestRule.setContent {
            SettingsScreen(
                state = state,
                onVibrationIntensityChange = {},
                onSoundVolumeChange = {},
                onSpeechRateChange = {},
                onBack = { backPressed = true },
            )
        }

        composeTestRule.onNodeWithContentDescription("설정 닫고 이전 화면으로 돌아가기").performClick()
        assertEquals(true, backPressed)
    }

    @Test
    fun differentValues_updateDisplayedPercentages() {
        val quiet = SettingsUiState(vibrationIntensity = 0f, soundVolume = 1f, speechRate = 0.5f)
        composeTestRule.setContent {
            SettingsScreen(
                state = quiet,
                onVibrationIntensityChange = {},
                onSoundVolumeChange = {},
                onSpeechRateChange = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("진동 강도, 현재 0퍼센트").assertIsDisplayed()
        composeTestRule.onNodeWithText("사운드 강도, 현재 100퍼센트").assertIsDisplayed()
        composeTestRule.onNodeWithText("음성 속도, 현재 50퍼센트 속도").assertIsDisplayed()
    }
}
