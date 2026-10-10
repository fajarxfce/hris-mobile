@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.observability

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/** Coroutine-scoped correlation. No ThreadLocal, global active span or retained coroutine scope. */
class OperationTrace private constructor(val traceId: String, val spanId: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OperationTrace> {
        fun create(parent: OperationTrace? = null) =
            OperationTrace(
                parent?.traceId ?: Uuid.random().toHexString(),
                Uuid.random().toHexString().take(16),
            )
    }

    val traceparent: String
        get() = "00-$traceId-$spanId-01"
}
