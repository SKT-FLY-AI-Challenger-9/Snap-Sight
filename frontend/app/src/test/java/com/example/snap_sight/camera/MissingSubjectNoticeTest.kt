package com.example.snap_sight.camera

import com.example.snap_sight.cv.TargetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "요청한 피사체 없이 찍힘" 안내 판정 테스트 — 촬영은 막지 않고, 알릴 근거가 확실할 때만
 * 피사체 호칭을 돌려준다.
 */
class MissingSubjectNoticeTest {

    private val labels = mapOf("laptop" to "노트북", "person" to "사람")

    private fun objectSpec(label: String = "laptop") = TargetSpec(
        sessionId = "s_test", rawText = "노트북 찍어줘", source = "backend",
        schemaVersion = "0.2", subjectType = TargetSpec.SubjectType.OBJECT, objectLabel = label,
    )

    @Test
    fun noticesWhenIntendedSubjectWasMissing() {
        assertEquals(
            "노트북",
            MissingSubjectNotice.targetNameIfMissing(
                objectSpec(), subjectDetected = false, hasFreshVerdict = true, koreanLabels = labels,
            ),
        )
    }

    @Test
    fun silentWhenSubjectWasVisible() {
        assertNull(
            MissingSubjectNotice.targetNameIfMissing(
                objectSpec(), subjectDetected = true, hasFreshVerdict = true, koreanLabels = labels,
            ),
        )
    }

    @Test
    fun silentWithoutIntentLandscapeOrFailedSpec() {
        assertNull(
            MissingSubjectNotice.targetNameIfMissing(
                spec = null, subjectDetected = false, hasFreshVerdict = true, koreanLabels = labels,
            ),
        )
        val landscape = TargetSpec(
            sessionId = "s_test", rawText = "풍경 찍어줘", source = "backend",
            subjectType = TargetSpec.SubjectType.LANDSCAPE,
        )
        assertNull(
            MissingSubjectNotice.targetNameIfMissing(
                landscape, subjectDetected = false, hasFreshVerdict = true, koreanLabels = labels,
            ),
        )
        val failed = TargetSpec(
            sessionId = "s_test", rawText = "", source = "ondevice",
            status = TargetSpec.Status.FAILED,
        )
        assertNull(
            MissingSubjectNotice.targetNameIfMissing(
                failed, subjectDetected = false, hasFreshVerdict = true, koreanLabels = labels,
            ),
        )
    }

    @Test
    fun silentWithoutFreshCvVerdict() {
        // CV 모델 없음·멈춤 등 — 근거 없이 "없었다"고 단정하지 않는다
        assertNull(
            MissingSubjectNotice.targetNameIfMissing(
                objectSpec(), subjectDetected = false, hasFreshVerdict = false, koreanLabels = labels,
            ),
        )
    }

    @Test
    fun targetNameFallsBackToRawLabelAndPerson() {
        assertEquals(
            "cup",
            MissingSubjectNotice.targetNameIfMissing(
                objectSpec(label = "cup"), subjectDetected = false, hasFreshVerdict = true,
                koreanLabels = labels,
            ),
        )
        val person = TargetSpec(
            sessionId = "s_test", rawText = "인물 사진", source = "backend",
            subjectType = TargetSpec.SubjectType.PERSON,
        )
        assertEquals(
            "사람",
            MissingSubjectNotice.targetNameIfMissing(
                person, subjectDetected = false, hasFreshVerdict = true, koreanLabels = labels,
            ),
        )
    }
}
