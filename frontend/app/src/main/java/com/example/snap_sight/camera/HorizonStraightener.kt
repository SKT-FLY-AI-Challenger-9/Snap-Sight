// 이 파일: 촬영 순간 폰이 좌우로 기울어져 있었으면 저장 전에 사진을 수평으로 되돌리는 후처리
// (2026-08-30, 엔드유저 피드백 "폰이 좌우로 기울어진 경우"). 음성 안내([GuidancePolicy] 의
// 수평 문구)는 큰 기울기(10° 이상)만 다루므로, 그 아래 작은 기울기는 여기서 앱이 조용히 고친다.
package com.example.snap_sight.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 셔터 순간의 roll([TiltSensorMonitor.rollDegrees])만큼 사진을 역회전하고, 검은 모서리가
 * 생기지 않도록 원본과 같은 종횡비의 최대 내접 사각형으로 잘라 새 JPEG 를 만든다.
 *
 * 적용 범위:
 *  - |roll| < [MIN_ROLL_DEG] 는 손대지 않는다(원본 유지, null) — 티도 안 나는데 화질만 깎인다.
 *  - |roll| > [MAX_ROLL_DEG] 도 손대지 않는다 — 잘려 나가는 면적이 너무 크다(12° 에서 이미
 *    가로 약 79%). 이 구간은 음성 안내가 촬영 전에 막는 것이 정책이다.
 *  - 후면 카메라 전용 — 전면(셀피)은 미러링·광축 방향 때문에 부호가 달라 호출부가 걸러낸다.
 *
 * 결과 파일은 회전을 픽셀에 구운(upright) JPEG 라 EXIF orientation 이 없다([PortraitAutoCrop] 과
 * 같은 규약). 실패는 전부 null(원본 유지, fail-open).
 *
 * 기울기는 절대 roll 이 아니라 가장 가까운 파지 스냅(0/±90/180°)으로부터의 편차
 * ([PhoneRoll.deviationFromNearestSnap])다 — 가로 파지 사진도 그 자세 기준으로 수평을 맞춘다.
 * EXIF 가 upright 로 세운 뒤의 잔여 회전은 파지 방향과 무관하게 같은 각·같은 방향이다.
 *
 * 부호 ([PhoneRoll] 규약, 실기기 확정 2026-08-28: 폰을 왼쪽(반시계)으로 돌리면 roll 이 +):
 * 편차 + 면 사진 속 장면은 시계 방향으로 돌아가 보이므로 반시계(음수)로 되돌린다 —
 * 보정각 = [CORRECTION_SIGN] × 편차. 실기기에서 반대로 보정되면 이 상수 하나만 뒤집는다.
 */
object HorizonStraightener {

    /**
     * [file] 의 수평 보정본을 새 파일로 만들어 돌려준다. 보정이 필요 없거나 불가능하면 null.
     * 연사 채점 스레드처럼 백그라운드에서만 호출할 것 (대형 비트맵 디코드).
     */
    fun apply(file: File, rollDegrees: Float): File? = try {
        correctionDegrees(rollDegrees)?.let { straighten(file, it, "roll %.1f°".format(rollDegrees)) }
    } catch (t: Throwable) {
        Log.w(TAG, "수평 보정 실패 — 원본 유지", t)
        null
    }

    /**
     * 서류 모드(2026-08-30) — 센서 roll 대신 **사진 속 글자 줄의 회전각**으로 되돌린다. 벽·거치대
     * 어디에 있든 서류 변에 맞추는 게 목표라 중력 기준 수평이 아니다. 각도는 가장 가까운
     * 0/±90/180° 스냅 편차를 쓰고([TEXT_MAX_ROTATION_DEG] 까지), 보정각 = [TEXT_CORRECTION_SIGN] × 편차.
     */
    fun applyTextRotation(file: File, textAngleDegrees: Float): File? = try {
        textCorrectionDegrees(textAngleDegrees)?.let {
            straighten(file, it, "글자 각도 %.1f°".format(textAngleDegrees))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "서류 회전 보정 실패 — 원본 유지", t)
        null
    }

    private fun straighten(file: File, correction: Float, sourceLabel: String): File? {
        val exifRotation = PortraitAutoCrop.exifRotationDegrees(file)
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        try {
            // EXIF 90/270 이면 upright 기준으로 가로세로가 바뀐다
            val uprightWidth = if (exifRotation % 180 != 0) source.height else source.width
            val uprightHeight = if (exifRotation % 180 != 0) source.width else source.height
            val scale = inscribedScale(uprightWidth, uprightHeight, correction)
            val outWidth = (uprightWidth * scale).toInt().coerceAtLeast(1)
            val outHeight = (uprightHeight * scale).toInt().coerceAtLeast(1)

            // 원본 중심을 원점으로 → EXIF 회전 → 수평 보정 회전 → 출력 중심으로. 회전은 모두
            // 같은 중심을 기준으로 하므로 출력 캔버스가 곧 내접 크롭이다(별도 크롭 비트맵 없음).
            val matrix = Matrix().apply {
                postTranslate(-source.width / 2f, -source.height / 2f)
                postRotate(exifRotation.toFloat())
                postRotate(correction)
                postTranslate(outWidth / 2f, outHeight / 2f)
            }
            val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            try {
                Canvas(out).drawBitmap(
                    source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
                val target = File(file.parentFile, file.nameWithoutExtension + "_level.jpg")
                target.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                Log.i(
                    TAG,
                    "수평 보정 적용: %s → %.1f° 회전, 원본 대비 %.0f%% 크롭".format(
                        sourceLabel, correction, 100f * scale,
                    ),
                )
                return target
            } finally {
                out.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    /**
     * roll → 사진에 적용할 회전각(도, [Matrix.postRotate] 규약: 양수 = 시계 방향). 가장 가까운
     * 파지 스냅으로부터의 편차를 쓰며, 보정 범위 밖([MIN_ROLL_DEG] 미만, [MAX_ROLL_DEG] 초과)이면 null.
     */
    internal fun correctionDegrees(rollDegrees: Float): Float? {
        if (!rollDegrees.isFinite()) return null
        val deviation = PhoneRoll.deviationFromNearestSnap(rollDegrees)
        val magnitude = abs(deviation)
        if (magnitude < MIN_ROLL_DEG || magnitude > MAX_ROLL_DEG) return null
        return CORRECTION_SIGN * deviation
    }

    /**
     * 글자 줄 각도 → 사진에 적용할 회전각. 서류는 텍스트가 반듯해질 때까지 돌리므로 roll 보정보다
     * 큰 각([TEXT_MAX_ROTATION_DEG])까지 허용한다. 범위 밖이면 null.
     */
    internal fun textCorrectionDegrees(textAngleDegrees: Float): Float? {
        if (!textAngleDegrees.isFinite()) return null
        val deviation = PhoneRoll.deviationFromNearestSnap(textAngleDegrees)
        val magnitude = abs(deviation)
        if (magnitude < MIN_ROLL_DEG || magnitude > TEXT_MAX_ROTATION_DEG) return null
        return TEXT_CORRECTION_SIGN * deviation
    }

    /**
     * 순수 기하 (JVM 테스트 대상) — [width]×[height] 사각형을 [angleDegrees] 만큼 돌렸을 때
     * 그 안에 들어가는, 같은 종횡비의 축 정렬 최대 사각형 배율(0..1).
     * 배율 s 의 사각형 모서리 (s·w/2, s·h/2) 가 회전된 원본 안에 있으려면
     * s·(w·|cos| + h·|sin|) ≤ w 이고 s·(w·|sin| + h·|cos|) ≤ h 이어야 한다.
     */
    internal fun inscribedScale(width: Int, height: Int, angleDegrees: Float): Float {
        if (width <= 0 || height <= 0) return 1f
        val radians = Math.toRadians(angleDegrees.toDouble())
        val sinA = abs(sin(radians))
        val cosA = abs(cos(radians))
        val w = width.toDouble()
        val h = height.toDouble()
        val byWidth = w / (w * cosA + h * sinA)
        val byHeight = h / (w * sinA + h * cosA)
        return minOf(byWidth, byHeight).coerceIn(0.0, 1.0).toFloat()
    }

    private const val TAG = "HorizonStraightener"

    /** 이보다 작은 기울기는 보정하지 않는다 — 체감 없이 화질만 손해. */
    internal const val MIN_ROLL_DEG = 2f

    /** 이보다 큰 기울기는 보정하지 않는다 — 잘리는 면적이 과하다(음성 안내가 촬영 전에 막는 구간). */
    internal const val MAX_ROLL_DEG = 12f

    /** 보정각 = 이 부호 × roll. 실기기에서 반대로 보정되면 여기만 뒤집는다 (파일 상단 KDoc). */
    internal const val CORRECTION_SIGN = -1f

    /** 서류 모드 글자 각도 보정 상한 — 글자가 반듯해질 때까지 돌리므로 roll 보다 넓게. */
    internal const val TEXT_MAX_ROTATION_DEG = 20f

    /**
     * 글자 줄이 이미지에서 이만큼 돌아가 있으면 그 반대로 돌려 되돌린다 — ML Kit 각도 규약(시계
     * 방향 양수로 가정)에 따라 −1. 실기기에서 반대로 보정되면 [com.example.snap_sight.ux.DocumentGuide.ROTATION_SIGN]
     * 과 함께 뒤집는다.
     */
    internal const val TEXT_CORRECTION_SIGN = -1f

    private const val JPEG_QUALITY = 95
}
