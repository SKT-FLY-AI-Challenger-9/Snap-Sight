package com.example.snap_sight.ux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * [OnboardingScreen] 권한 상태별 노출과 GRANTED 진입 시 [onContinue] 자동 호출을 검증한다.
 * MainActivity 연결(실제 권한 런처, 화면 전환) 이전 단계 — 화면 자체의 계약만 확인한다.
 */
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun notRequestedState_showsInitialPromptAndNoSettingsShortcut() {
        composeTestRule.setContent {
            OnboardingScreen(
                permissionState = OnboardingPermissionState.NOT_REQUESTED,
                onRequestPermissions = {},
                onOpenAppSettings = {},
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithText("촬영을 시작하려면 카메라와 마이크 권한이 필요합니다").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("카메라와 마이크 권한 허용하기").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("앱 설정 화면에서 권한 직접 허용하기").assertDoesNotExist()
    }

    @Test
    fun deniedState_showsRetryCopyAndSettingsShortcut() {
        composeTestRule.setContent {
            OnboardingScreen(
                permissionState = OnboardingPermissionState.DENIED,
                onRequestPermissions = {},
                onOpenAppSettings = {},
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithText("권한이 거부되었습니다. 다시 시도하거나 설정에서 직접 허용해주세요").assertIsDisplayed()
        composeTestRule.onNodeWithText("다시 요청하기").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("앱 설정 화면에서 권한 직접 허용하기").assertIsDisplayed()
    }

    @Test
    fun deniedState_settingsButtonClickInvokesCallback() {
        var openedSettings = false
        composeTestRule.setContent {
            OnboardingScreen(
                permissionState = OnboardingPermissionState.DENIED,
                onRequestPermissions = {},
                onOpenAppSettings = { openedSettings = true },
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("앱 설정 화면에서 권한 직접 허용하기").performClick()
        assertEquals(true, openedSettings)
    }

    @Test
    fun requestButtonClickInvokesCallback() {
        var requested = false
        composeTestRule.setContent {
            OnboardingScreen(
                permissionState = OnboardingPermissionState.NOT_REQUESTED,
                onRequestPermissions = { requested = true },
                onOpenAppSettings = {},
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("카메라와 마이크 권한 허용하기").performClick()
        assertEquals(true, requested)
    }

    @Test
    fun grantedState_invokesOnContinueOnce() {
        var continueCount = 0
        composeTestRule.setContent {
            OnboardingScreen(
                permissionState = OnboardingPermissionState.GRANTED,
                onRequestPermissions = {},
                onOpenAppSettings = {},
                onContinue = { continueCount++ },
            )
        }

        composeTestRule.waitForIdle()
        assertEquals(1, continueCount)
    }

    @Test
    fun transitionToGranted_invokesOnContinue() {
        var continueCount = 0
        var state by mutableStateOf(OnboardingPermissionState.NOT_REQUESTED)
        composeTestRule.setContent {
            OnboardingScreen(
                permissionState = state,
                onRequestPermissions = {},
                onOpenAppSettings = {},
                onContinue = { continueCount++ },
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(0, continueCount)

        state = OnboardingPermissionState.GRANTED
        composeTestRule.waitForIdle()
        assertEquals(1, continueCount)
    }
}
