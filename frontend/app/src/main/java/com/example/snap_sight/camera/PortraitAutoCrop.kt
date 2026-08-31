package com.example.snap_sight.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import kotlin.math.abs

/**
 * 인물 자동 크롭 (2026-08-25) — 자동촬영 승자 컷의 얼굴을 3분할 구도로 배치한 크롭본을
 * 만든다. "인스타 구도" 기획의 층위 2: 라이브 안내 축을 늘리는 대신 후처리로 구도를 완성한다.
 *
 * 조준 스트림의 bbox 를 원본 사진 좌표로 매핑하는 대신, 저장된 사진에 얼굴 검출을 다시
 * 돌린다 — 해상도·회전·뷰포트 차이 문제가 아예 생기지 않는다.
 *
 * 얼굴이 정확히 1명일 때만 자른다. 단체 사진의 3분할 크롭은 다른 인물을 잘라낼 위험이
 * 있어 보류. 검출 실패·여백 부족·의미 없는 크롭은 전부 null(원본 유지, fail-open)이다.
 *
 * 크롭 결과는 회전을 픽셀에 구운(upright) JPEG 라 EXIF orientation 이 필요 없다.
 */
object PortraitAutoCrop {

    /** android.graphics.Rect 없이 순수 기하 계산이 가능하도록 하는 정수 박스. */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    /**
     * [file]의 인물 크롭본을 새 파일로 만들어 돌려준다. 크롭할 수 없거나 의미가 없으면 null.
     * 연사 채점 스레드처럼 백그라운드에서만 호출할 것 (Tasks.await 블로킹 + 대형 비트맵 디코드).
     */
    fun apply(file: File): File? = try {
        cropUpright(file)
    } catch (t: Throwable) {
        Log.w(TAG, "인물 크롭 실패 — 원본 유지", t)
        null
    }

    private fun cropUpright(file: File): File? {
        val rotation = exifRotationDegrees(file)
        var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }
        try {
            // 검출은 축소본으로 (12MP 원본은 느리다) — 좌표는 원본 크기로 되돌려 쓴다
            val detectScale = (DETECT_MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
                .coerceAtMost(1f)
            val detectBitmap = if (detectScale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * detectScale).toInt().coerceAtLeast(1),
                    (bitmap.height * detectScale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val faces = try {
                Tasks.await(detector.process(InputImage.fromBitmap(detectBitmap, 0)))
            } finally {
                if (detectBitmap != bitmap) detectBitmap.recycle()
            }
            if (faces.size != 1) {
                Log.i(TAG, "인물 크롭 건너뜀 — 얼굴 ${faces.size}개 (정확히 1명일 때만)")
                return null
            }
            val raw = faces[0].boundingBox
            val inv = 1f / detectScale
            val face = Box(
                left = (raw.left * inv).toInt().coerceIn(0, bitmap.width - 1),
                top = (raw.top * inv).toInt().coerceIn(0, bitmap.height - 1),
                width = (raw.width() * inv).toInt().coerceAtLeast(1),
                height = (raw.height() * inv).toInt().coerceAtLeast(1),
            )
            val crop = computeCrop(face, bitmap.width, bitmap.height) ?: run {
                Log.i(TAG, "인물 크롭 건너뜀 — 여백 부족이거나 이미 3분할 구도")
                return null
            }
            val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
            val out = File(file.parentFile, file.nameWithoutExtension + "_crop.jpg")
            try {
                out.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            } finally {
                cropped.recycle()
            }
            Log.i(
                TAG,
                "인물 크롭 적용: 얼굴 3분할 배치 (원본 대비 %.0f%%)".format(
                    100f * crop.width / bitmap.width,
                ),
            )
            return out
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 순수 기하 계산 (JVM 테스트 대상) — 얼굴 중심을 가까운 세로 3분할선 × 상단 1/3 지점에
     * 놓는, 원본과 같은 종횡비의 최대 크롭을 구한다.
     *
     * null 을 돌려주는 경우:
     *  - 필요한 축소율이 [MIN_SCALE] 미만 (얼굴이 가장자리에 붙어 여백이 없음)
     *  - 크롭이 사실상 원본과 같음 (이미 3분할 구도, [NOOP_SCALE] 이상)
     *  - 얼굴이 크롭 대비 너무 큼 (초근접 — 3분할이 성립하지 않음)
     */
    internal fun computeCrop(face: Box, imageWidth: Int, imageHeight: Int): Box? {
        if (imageWidth < 3 || imageHeight < 3) return null
        val faceCx = face.left + face.width / 2f
        val faceCy = face.top + face.height / 2f
        val thirdFrac = if (faceCx <= imageWidth / 2f) 1f / 3f else 2f / 3f

        // 얼굴 중심이 (thirdFrac·W', H'/3) 에 오면서 크롭이 화면 안에 들어가는 최대 배율 s.
        var s = 1f
        s = minOf(s, faceCx / (thirdFrac * imageWidth))
        s = minOf(s, (imageWidth - faceCx) / ((1f - thirdFrac) * imageWidth))
        s = minOf(s, 3f * faceCy / imageHeight)
        s = minOf(s, 3f * (imageHeight - faceCy) / (2f * imageHeight))
        if (s < MIN_SCALE) return null
        if (s >= NOOP_SCALE) return null

        val cropW = (imageWidth * s).toInt().coerceAtLeast(1)
        val cropH = (imageHeight * s).toInt().coerceAtLeast(1)
        if (face.width > cropW * MAX_FACE_FRACTION || face.height > cropH * MAX_FACE_FRACTION) {
            return null
        }
        val left = (faceCx - thirdFrac * cropW).toInt().coerceIn(0, imageWidth - cropW)
        val top = (faceCy - cropH / 3f).toInt().coerceIn(0, imageHeight - cropH)
        return Box(left, top, cropW, cropH)
    }

    /** EXIF orientation → upright 로 만들기 위한 회전(0/90/180/270). [HorizonStraightener] 도 쓴다. */
    internal fun exifRotationDegrees(file: File): Int = try {
        when (
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (t: Throwable) {
        0
    }

    private const val TAG = "PortraitAutoCrop"

    /** 검출용 축소 상한 — 얼굴 위치·크기 판단에는 충분하고 수백 ms 이내로 끝난다. */
    private const val DETECT_MAX_SIDE = 1280

    /** 이보다 더 잘라야 3분할이 성립하면 포기 — 화질·맥락 손실이 과하다. */
    internal const val MIN_SCALE = 0.6f

    /** 사실상 원본과 같은 크롭은 만들지 않는다 (이미 3분할 구도). */
    internal const val NOOP_SCALE = 0.97f

    /** 얼굴이 크롭의 이 비율보다 크면 3분할 배치가 무의미한 초근접 컷. */
    internal const val MAX_FACE_FRACTION = 0.7f

    private const val JPEG_QUALITY = 95
}
