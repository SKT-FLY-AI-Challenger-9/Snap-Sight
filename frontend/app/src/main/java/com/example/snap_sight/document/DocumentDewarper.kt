// 이 파일: 서류 촬영본 원근 보정 (2026-08-31, 외곽 v2) — 조준 중 잡은 모서리 4점(사다리꼴)을
// 정확한 직사각형으로 펴서 저장한다. 조준 안내가 자동촬영을 정지 유지에서만 허용하므로 셔터
// 순간의 모서리는 촬영본과 어긋나지 않는다. 실패는 전부 null(호출부가 글자 각도 회전 보정으로
// 폴백, 그것도 실패하면 원본 유지).
package com.example.snap_sight.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.example.snap_sight.camera.PortraitAutoCrop
import com.example.snap_sight.ux.DocumentQuad
import java.io.File

object DocumentDewarper {

    /**
     * [file] 의 원근 보정본을 새 파일(_flat.jpg)로 만들어 돌려준다. 보정 범위 밖이거나 실패하면
     * null. 연사 채점 스레드처럼 백그라운드에서만 호출할 것 (대형 비트맵 디코드 ×2).
     */
    fun apply(file: File, quad: DocumentQuad): File? = try {
        dewarp(file, quad)
    } catch (t: Throwable) {
        Log.w(TAG, "원근 보정 실패 — 폴백", t)
        null
    }

    private fun dewarp(file: File, quad: DocumentQuad): File? {
        // 심한 사다리꼴은 먼 쪽 해상도가 무너져 펴도 화질이 안 나온다 — 안내가 촬영 전에 막는 영역
        if (quad.verticalConvergence !in MIN_CONVERGENCE..1f / MIN_CONVERGENCE) return null
        if (quad.horizontalConvergence !in MIN_CONVERGENCE..1f / MIN_CONVERGENCE) return null
        if (quad.topWidth < MIN_SIDE_FRACTION || quad.leftHeight < MIN_SIDE_FRACTION) return null

        val exifRotation = PortraitAutoCrop.exifRotationDegrees(file)
        var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (exifRotation != 0) {
            val matrix = Matrix().apply { postRotate(exifRotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }
        try {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val dstWidth = (maxOf(quad.topWidth, quad.bottomWidth) * w).toInt()
            val dstHeight = (maxOf(quad.leftHeight, quad.rightHeight) * h).toInt()
            if (dstWidth < MIN_OUTPUT_PX || dstHeight < MIN_OUTPUT_PX) return null

            val src = floatArrayOf(
                quad.tl.x * w, quad.tl.y * h,
                quad.tr.x * w, quad.tr.y * h,
                quad.br.x * w, quad.br.y * h,
                quad.bl.x * w, quad.bl.y * h,
            )
            val dst = floatArrayOf(
                0f, 0f,
                dstWidth.toFloat(), 0f,
                dstWidth.toFloat(), dstHeight.toFloat(),
                0f, dstHeight.toFloat(),
            )
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null

            val out = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
            try {
                Canvas(out).drawBitmap(
                    bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
                val target = File(file.parentFile, file.nameWithoutExtension + "_flat.jpg")
                target.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                Log.i(
                    TAG,
                    "원근 보정 적용: 수렴 V=%.2f H=%.2f → %d×%d".format(
                        quad.verticalConvergence, quad.horizontalConvergence, dstWidth, dstHeight,
                    ),
                )
                return target
            } finally {
                out.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private const val TAG = "DocumentDewarper"
    /** 이보다 심한 수렴(원근)은 펴지 않는다 — 먼 쪽 해상도 손실이 과함. */
    internal const val MIN_CONVERGENCE = 0.70f
    /** 서류 변이 프레임의 이 비율보다 짧으면 노이즈로 본다. */
    internal const val MIN_SIDE_FRACTION = 0.15f
    private const val MIN_OUTPUT_PX = 320
    private const val JPEG_QUALITY = 95
}
