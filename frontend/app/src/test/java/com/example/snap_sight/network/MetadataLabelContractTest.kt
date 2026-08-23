package com.example.snap_sight.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataLabelContractTest {
    @Test
    fun `drops invented labels and deduplicates allowed labels`() {
        val result = MetadataLabelContract.sanitize(
            fixed = listOf("person", "invented", "person", "food"),
            custom = listOf("제주 여행", "서버가 지어낸 이름", "제주 여행"),
            allowedFixed = setOf("person", "food"),
            allowedCustom = setOf("제주 여행"),
        )

        assertEquals(listOf("person", "food"), result.fixed)
        assertEquals(listOf("제주 여행"), result.custom)
    }
}
