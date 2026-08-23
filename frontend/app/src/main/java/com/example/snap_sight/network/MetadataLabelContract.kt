package com.example.snap_sight.network

/** 서버 응답 라벨을 앱이 실제로 요청·지원한 폐쇄형 집합 안으로 다시 제한한다. */
internal object MetadataLabelContract {
    data class Result(
        val fixed: List<String>,
        val custom: List<String>,
    )

    fun sanitize(
        fixed: List<String>,
        custom: List<String>,
        allowedFixed: Set<String>,
        allowedCustom: Set<String>,
    ): Result = Result(
        fixed = fixed.filter { it in allowedFixed }.distinct(),
        custom = custom.filter { it in allowedCustom }.distinct(),
    )
}
