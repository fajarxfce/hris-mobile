package dev.fajar.starter.collaboration.data.sdk

/** Positions include virtual/non-message entries: filtering first would corrupt SDK indices. */
internal sealed interface SdkTimelineDelta<out T> {
    data class Reset<T>(val values: List<T>) : SdkTimelineDelta<T>

    data class Append<T>(val values: List<T>) : SdkTimelineDelta<T>

    data class Insert<T>(val index: UInt, val value: T) : SdkTimelineDelta<T>

    data class Set<T>(val index: UInt, val value: T) : SdkTimelineDelta<T>

    data class PushFront<T>(val value: T) : SdkTimelineDelta<T>

    data class PushBack<T>(val value: T) : SdkTimelineDelta<T>

    data class Remove(val index: UInt) : SdkTimelineDelta<Nothing>

    data class Truncate(val length: UInt) : SdkTimelineDelta<Nothing>

    data object Clear : SdkTimelineDelta<Nothing>

    data object PopFront : SdkTimelineDelta<Nothing>

    data object PopBack : SdkTimelineDelta<Nothing>
}

/** A whole ordered batch succeeds or leaves the previous snapshot unchanged. */
internal fun <T> reduceSdkTimeline(
    previous: List<T>,
    changes: List<SdkTimelineDelta<T>>,
    maximum: Int = 512,
): List<T> {
    require(maximum in 1..512 && previous.size <= maximum && changes.size <= 4096)
    val next = previous.toMutableList()
    for (change in changes) {
        when (change) {
            is SdkTimelineDelta.Reset -> {
                require(change.values.size <= maximum)
                next.clear()
                next.addAll(change.values)
            }
            is SdkTimelineDelta.Append -> {
                require(change.values.size <= maximum - next.size)
                next.addAll(change.values)
            }
            is SdkTimelineDelta.Insert -> {
                require(change.index <= next.size.toUInt() && next.size < maximum)
                next.add(change.index.toInt(), change.value)
            }
            is SdkTimelineDelta.Set -> {
                require(change.index < next.size.toUInt())
                next[change.index.toInt()] = change.value
            }
            is SdkTimelineDelta.PushFront -> {
                require(next.size < maximum)
                next.add(0, change.value)
            }
            is SdkTimelineDelta.PushBack -> {
                require(next.size < maximum)
                next.add(change.value)
            }
            is SdkTimelineDelta.Remove -> {
                require(change.index < next.size.toUInt())
                next.removeAt(change.index.toInt())
            }
            is SdkTimelineDelta.Truncate ->
                if (change.length < next.size.toUInt())
                    next.subList(change.length.toInt(), next.size).clear()
            SdkTimelineDelta.Clear -> next.clear()
            SdkTimelineDelta.PopFront -> {
                require(next.isNotEmpty())
                next.removeAt(0)
            }
            SdkTimelineDelta.PopBack -> {
                require(next.isNotEmpty())
                next.removeAt(next.lastIndex)
            }
        }
    }
    return next.toList()
}
