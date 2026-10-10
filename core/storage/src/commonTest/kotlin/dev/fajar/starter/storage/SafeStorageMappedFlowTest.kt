package dev.fajar.starter.storage

import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class SafeStorageMappedFlowTest {
    @Test
    fun invalidRecordDoesNotPreventTheNextRecordAndCollectorFailuresRemainVisible() = runTest {
        val errors = mutableListOf<Exception>()
        val mapped =
            safeStorageFlow(
                    flowOf("1", "bad", "2"),
                    onException = errors::add,
                    mapValue = { it.toInt() },
                )
                .toList()
        assertEquals(listOf(1, null, 2), mapped.map { (it as? AppResult.Success)?.value })
        assertEquals(1, errors.size)
        val downstream = IllegalStateException("collector")
        val thrown =
            assertFailsWith<IllegalStateException> {
                safeStorageFlow(flowOf("1"), mapValue = { it.toInt() }).collect { throw downstream }
            }
        assertSame(downstream, thrown)
    }
}
