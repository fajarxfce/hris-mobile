package dev.fajar.starter.collaboration.domain.policies

/** Plain text for native rendering; bound retained previews without splitting UTF-16 pairs. */
fun collaborationNotificationPreviewText(value: String, limit: Int): String {
    require(limit in 1..400)
    val selected = buildString {
        for (index in 0 until minOf(value.length, 4096)) {
            val char = value[index]
            if (length >= limit) break
            if (!char.isISOControl() || char == '\n' || char == '\t') append(char)
        }
    }
    return (if (selected.lastOrNull()?.isHighSurrogate() == true) selected.dropLast(1)
        else selected)
        .trim()
}
