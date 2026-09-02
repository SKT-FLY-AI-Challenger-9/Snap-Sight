package com.example.snap_sight.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.snap_sight.R

// 저시력 사용자를 위한 유니버설 디자인 서체 — 앱 전체 텍스트에 적용
val KoddiUdOnGothic = FontFamily(
    Font(R.font.koddi_ud_ongothic_regular, FontWeight.Normal),
    Font(R.font.koddi_ud_ongothic_bold, FontWeight.Bold),
    Font(R.font.koddi_ud_ongothic_extrabold, FontWeight.ExtraBold)
)

private val defaultTypography = Typography()

// Material3 기본 타이포그래피의 크기·굵기·자간은 유지하고 서체만 KoddiUD 온고딕으로 교체
val Typography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = KoddiUdOnGothic),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = KoddiUdOnGothic),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = KoddiUdOnGothic),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = KoddiUdOnGothic),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = KoddiUdOnGothic),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = KoddiUdOnGothic),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = KoddiUdOnGothic),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = KoddiUdOnGothic),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = KoddiUdOnGothic),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = KoddiUdOnGothic),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = KoddiUdOnGothic),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = KoddiUdOnGothic),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = KoddiUdOnGothic),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = KoddiUdOnGothic),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = KoddiUdOnGothic)
)
