package dev.fajar.starter.notifications.data.mappers

/** Internal path segments only. A notification never chooses a scheme, host, query or fragment. */
fun notificationDestinationSegments(destination: String): List<String> {
    require(destination.length in 1..512)
    val segments = destination.split('/')
    require(segments.size in 1..12)
    require(
        segments.all { segment ->
            segment.length in 1..80 &&
                segment.all {
                    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
                }
        }
    )
    return segments
}
