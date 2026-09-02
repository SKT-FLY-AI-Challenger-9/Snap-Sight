// 이 파일: 서류 외곽 v2 — "텍스트 앵커 스트립 직선 피팅" (2026-08-31, 사용자 승인 B안).
// 글자 합집합 상자의 바깥 네 방향에서, 변과 나란한 구간을 스트립 [STRIPS]개로 쪼개 스트립마다
// 그래디언트 피크를 찾고 최소제곱으로 직선을 세운다 — 변의 위치뿐 아니라 **기울기**가 나와
// 네 변의 교점 = 모서리 4점([com.example.snap_sight.ux.DocumentQuad])을 얻는다. 이것이
// 기울임(원근) 판정과 촬영 후 사다리꼴 보정의 입력이다. 부분 가림(손가락)은 중앙값에서 크게
// 벗어난 스트립을 버려 견딘다. 대비가 없으면 변별로 null — 호출부가 글자 여백으로 폴백.
// android.* 의존 없음 — 합성 이미지로 JVM 단위 테스트한다.
package com.example.snap_sight.document

import com.example.snap_sight.ux.DocLine
import kotlin.math.abs

object DocumentEdgeFinder {

    /** 찾은 변 직선(정규화). 대비가 없어 못 찾은 변은 null. */
    data class Edges(
        val left: DocLine? = null,
        val top: DocLine? = null,
        val right: DocLine? = null,
        val bottom: DocLine? = null,
    ) {
        val count: Int get() = listOfNotNull(left, top, right, bottom).size
    }

    /**
     * @param luma 그레이스케일(0..255), 크기 [width]×[height] — 분석 프레임을 축소한 것
     * @param textLeft..textBottom 글자 합집합 상자 (정규화 0..1)
     */
    fun find(
        luma: IntArray,
        width: Int,
        height: Int,
        textLeft: Float,
        textTop: Float,
        textRight: Float,
        textBottom: Float,
    ): Edges {
        if (width < MIN_DIMENSION || height < MIN_DIMENSION || luma.size < width * height) return Edges()
        val x0 = (textLeft * width).toInt().coerceIn(0, width - 1)
        val x1 = (textRight * width).toInt().coerceIn(x0 + 1, width)
        val y0 = (textTop * height).toInt().coerceIn(0, height - 1)
        val y1 = (textBottom * height).toInt().coerceIn(y0 + 1, height)

        val top = fitSide(
            alongFromPx = x0, alongUntilPx = x1, alongTotalPx = width,
            searchFrom = BORDER_EXCLUDE_PX, searchUntil = y0, posTotalPx = height,
            nearTextAtEnd = true,
            score = { pos, from, until -> horizontalEdgeScore(luma, width, pos, from, until) },
            bandMean = { posFrom, posUntil, from, until ->
                horizontalBandMean(luma, width, posFrom, posUntil, from, until)
            },
        )
        val bottom = fitSide(
            alongFromPx = x0, alongUntilPx = x1, alongTotalPx = width,
            searchFrom = y1, searchUntil = height - BORDER_EXCLUDE_PX, posTotalPx = height,
            nearTextAtEnd = false,
            score = { pos, from, until -> horizontalEdgeScore(luma, width, pos, from, until) },
            bandMean = { posFrom, posUntil, from, until ->
                horizontalBandMean(luma, width, posFrom, posUntil, from, until)
            },
        )
        val left = fitSide(
            alongFromPx = y0, alongUntilPx = y1, alongTotalPx = height,
            searchFrom = BORDER_EXCLUDE_PX, searchUntil = x0, posTotalPx = width,
            nearTextAtEnd = true,
            score = { pos, from, until -> verticalEdgeScore(luma, width, pos, from, until) },
            bandMean = { posFrom, posUntil, from, until ->
                verticalBandMean(luma, width, posFrom, posUntil, from, until)
            },
        )
        val right = fitSide(
            alongFromPx = y0, alongUntilPx = y1, alongTotalPx = height,
            searchFrom = x1, searchUntil = width - BORDER_EXCLUDE_PX, posTotalPx = width,
            nearTextAtEnd = false,
            score = { pos, from, until -> verticalEdgeScore(luma, width, pos, from, until) },
            bandMean = { posFrom, posUntil, from, until ->
                verticalBandMean(luma, width, posFrom, posUntil, from, until)
            },
        )
        return Edges(left = left, top = top, right = right, bottom = bottom)
    }

    /** 행 pos 의 가로 변 점수 — 지정 가로 구간에서 세로 중앙차분 크기의 평균. */
    private fun horizontalEdgeScore(luma: IntArray, width: Int, pos: Int, from: Int, until: Int): Float {
        var sum = 0
        for (x in from until until) {
            sum += abs(luma[(pos + 1) * width + x] - luma[(pos - 1) * width + x])
        }
        return sum.toFloat() / (until - from)
    }

    /** 열 pos 의 세로 변 점수 — 지정 세로 구간에서 가로 중앙차분 크기의 평균. */
    private fun verticalEdgeScore(luma: IntArray, width: Int, pos: Int, from: Int, until: Int): Float {
        var sum = 0
        for (y in from until until) {
            sum += abs(luma[y * width + pos + 1] - luma[y * width + pos - 1])
        }
        return sum.toFloat() / (until - from)
    }

    /** 행 [posFrom, posUntil) × 열 [from, until) 영역의 평균 밝기 — 종이색 검증용. */
    private fun horizontalBandMean(luma: IntArray, width: Int, posFrom: Int, posUntil: Int, from: Int, until: Int): Float {
        var sum = 0L
        var count = 0
        for (y in posFrom until posUntil) {
            for (x in from until until) {
                sum += luma[y * width + x]
                count++
            }
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    /** 열 [posFrom, posUntil) × 행 [from, until) 영역의 평균 밝기 — 종이색 검증용. */
    private fun verticalBandMean(luma: IntArray, width: Int, posFrom: Int, posUntil: Int, from: Int, until: Int): Float {
        var sum = 0L
        var count = 0
        for (y in from until until) {
            for (x in posFrom until posUntil) {
                sum += luma[y * width + x]
                count++
            }
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    /**
     * 한 변을 스트립별 피크 → 최소제곱 직선으로 세운다. 스트립이 하나도 피크를 못 찾으면 null,
     * 하나만 찾으면 기울기 0 직선. 스트립 3개 중 하나가 중앙값에서 [OUTLIER_PX] 넘게 벗어나면
     * (손가락 가림·그림자) 그 스트립은 버린다.
     */
    private inline fun fitSide(
        alongFromPx: Int,
        alongUntilPx: Int,
        alongTotalPx: Int,
        searchFrom: Int,
        searchUntil: Int,
        posTotalPx: Int,
        nearTextAtEnd: Boolean,
        crossinline score: (pos: Int, from: Int, until: Int) -> Float,
        crossinline bandMean: (posFrom: Int, posUntil: Int, from: Int, until: Int) -> Float,
    ): DocLine? {
        val span = alongUntilPx - alongFromPx
        if (span < MIN_ALONG_SPAN) return null
        val strips = if (span >= STRIPS * MIN_STRIP_SPAN) STRIPS else 1
        var points = ArrayList<Pair<Float, Float>>(strips) // (변과 나란한 축 중심 px, 피크 px)
        for (s in 0 until strips) {
            val from = alongFromPx + span * s / strips
            val until = alongFromPx + span * (s + 1) / strips
            val peak = findEdgePos(
                searchFrom, searchUntil, nearTextAtEnd,
                profile = { pos -> score(pos, from, until) },
                interiorMean = { posFrom, posUntil -> bandMean(posFrom, posUntil, from, until) },
            ) ?: continue
            points.add((from + until) / 2f to peak.toFloat())
        }
        if (points.isEmpty()) return null
        if (points.size >= 3) {
            val median = points.map { it.second }.sorted()[points.size / 2]
            val kept = points.filterTo(ArrayList()) { abs(it.second - median) <= OUTLIER_PX }
            if (kept.size >= 2) points = kept
        }
        if (points.size == 1) {
            val p = points[0].second / posTotalPx
            return DocLine(p, p)
        }
        // 최소제곱 (정규화 좌표) — t: 변과 나란한 축 0..1, p: 변 위치 0..1
        var meanT = 0f
        var meanP = 0f
        for ((t, p) in points) {
            meanT += t / alongTotalPx
            meanP += p / posTotalPx
        }
        meanT /= points.size
        meanP /= points.size
        var cov = 0f
        var varT = 0f
        for ((tPx, pPx) in points) {
            val dt = tPx / alongTotalPx - meanT
            cov += dt * (pPx / posTotalPx - meanP)
            varT += dt * dt
        }
        val slope = if (varT < 1e-6f) 0f else cov / varT
        val at0 = meanP - slope * meanT
        return DocLine(at0, at0 + slope)
    }

    /**
     * [searchFrom, searchUntil) 구간의 프로파일에서 서류 변으로 볼 피크 인덱스 — 없으면 null.
     * [nearTextAtEnd] true 면 글자 상자가 구간 끝쪽(인덱스 큰 쪽)에 있다.
     *
     * 선택 규칙 (2026-08-31 개정 — 실기기: 신분증 안쪽 무늬 경계를 변으로 잡아 사진이 잘렸다):
     * 국소 극대 후보들을 **글자에서 먼 쪽부터** 보되, "후보와 글자 사이 영역이 종이색과 같은가"
     * ([interiorMean] vs 글자 상자에 붙은 기준 띠)로 검증해 처음 통과하는 후보를 고른다 —
     *  - 진짜 서류 변: 안쪽이 전부 종이 → 통과 (카드 안쪽 무늬·사진 경계보다 바깥이라 이긴다)
     *  - 서류 너머 책상 모서리·벽 경계: 안쪽에 배경색 구간이 낌 → 탈락
     * 아무 후보도 통과 못 하면(종이가 균일하지 않은 서류 등) 기존 규칙 — 동급 피크
     * (≥[NEAR_PEAK_RATIO]×최대) 중 글자에 가까운 쪽 — 으로 폴백한다.
     */
    internal fun findEdgePos(
        searchFrom: Int,
        searchUntil: Int,
        nearTextAtEnd: Boolean,
        profile: (Int) -> Float,
        interiorMean: (posFrom: Int, posUntil: Int) -> Float,
    ): Int? {
        if (searchUntil - searchFrom < MIN_SEARCH_SPAN) return null
        val raw = FloatArray(searchUntil - searchFrom) { profile(searchFrom + it) }
        // 반경 1 박스 평활 — 한 픽셀짜리 노이즈 피크 제거
        val smoothed = FloatArray(raw.size) { i ->
            val a = raw[maxOf(0, i - 1)]
            val b = raw[i]
            val c = raw[minOf(raw.size - 1, i + 1)]
            (a + b + c) / 3f
        }
        var maxValue = 0f
        for (value in smoothed) if (value > maxValue) maxValue = value
        if (maxValue < MIN_EDGE_STRENGTH) return null
        val sorted = smoothed.clone().also { it.sort() }
        val median = sorted[sorted.size / 2]
        if (maxValue < median * DOMINANCE) return null

        // 국소 극대 후보 (충분히 강한 것만)
        val candidateThreshold = maxOf(MIN_EDGE_STRENGTH, maxValue * CANDIDATE_RATIO)
        val candidates = ArrayList<Int>()
        for (i in smoothed.indices) {
            val prev = smoothed[maxOf(0, i - 1)]
            val next = smoothed[minOf(smoothed.size - 1, i + 1)]
            if (smoothed[i] >= candidateThreshold && smoothed[i] >= prev && smoothed[i] >= next) {
                candidates.add(i)
            }
        }
        // 종이 기준 밝기 — 글자 상자에 붙은 얇은 띠 (그 옆은 확실히 서류 안쪽이다)
        val paperReference = if (nearTextAtEnd) {
            interiorMean(searchUntil - PAPER_REF_BAND, searchUntil)
        } else {
            interiorMean(searchFrom, searchFrom + PAPER_REF_BAND)
        }
        val farFirst = if (nearTextAtEnd) candidates else candidates.asReversed()
        for (i in farFirst) {
            val pos = searchFrom + i
            val interiorFrom = if (nearTextAtEnd) pos + 1 else searchFrom
            val interiorUntil = if (nearTextAtEnd) searchUntil else pos
            val interior = if (interiorUntil <= interiorFrom) paperReference
            else interiorMean(interiorFrom, interiorUntil)
            if (abs(interior - paperReference) <= PAPER_LUMA_TOLERANCE) return pos
        }

        // 폴백: 동급 피크 중 글자에 가까운 쪽 (기존 규칙)
        val threshold = maxValue * NEAR_PEAK_RATIO
        val indices = if (nearTextAtEnd) smoothed.indices.reversed() else smoothed.indices
        for (i in indices) {
            if (smoothed[i] >= threshold) return searchFrom + i
        }
        return null
    }

    /** 변마다 나눠 보는 스트립 수 — 기울기 추정의 표본점 수. */
    internal const val STRIPS = 3
    /** 스트립 하나의 최소 폭 — 이보다 좁으면 스트립을 나누지 않고 통짜로 본다. */
    internal const val MIN_STRIP_SPAN = 8
    /** 변과 나란한 구간이 이보다 좁으면 판단하지 않는다. */
    internal const val MIN_ALONG_SPAN = 8
    /** 스트립 피크가 중앙값에서 이보다 벗어나면 이상치(가림·그림자)로 버린다 (px). */
    internal const val OUTLIER_PX = 6f
    /** 이보다 좁은 검색 구간은 판단하지 않는다 — 글자가 프레임을 거의 채운 경우. */
    internal const val MIN_SEARCH_SPAN = 6
    /** 프레임 테두리의 센서·보정 아티팩트를 피크로 잡지 않게 제외하는 픽셀 수. */
    internal const val BORDER_EXCLUDE_PX = 2
    /** 변으로 인정할 최소 평균 그래디언트(밝기 레벨) — 이 미만이면 대비 없음. */
    internal const val MIN_EDGE_STRENGTH = 8f
    /** 피크가 프로파일 중앙값의 이 배수 이상이어야 한다 — 무늬 배경의 균일한 노이즈 배제. */
    internal const val DOMINANCE = 3f
    /** 최대 피크의 이 비율 이상이면 동급 피크로 보고 글자 쪽을 우선한다 (종이색 검증 폴백 전용). */
    internal const val NEAR_PEAK_RATIO = 0.7f
    /** 국소 극대가 최대 피크의 이 비율 이상이면 서류 변 후보로 본다. */
    internal const val CANDIDATE_RATIO = 0.5f
    /** 후보~글자 사이 평균 밝기가 종이 기준과 이 이내면 "안쪽이 전부 종이"로 본다 (0..255 레벨). */
    internal const val PAPER_LUMA_TOLERANCE = 25f
    /** 종이 기준 밝기를 재는, 글자 상자에 붙은 띠의 두께 (px). */
    internal const val PAPER_REF_BAND = 3
    private const val MIN_DIMENSION = 16
}
