package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Exact retained user provenance. The realm is metadata and must never choose an HTTP origin. */
fun validCollaborationSpaceIdentity(
    owner: CollaborationSpaceOwner,
    value: CollaborationSpaceIdentity,
): Boolean {
    if (
        value.spaceId != owner.spaceId ||
            value.accountId != owner.parent.accountId ||
            !validCollaborationMessageId(value.spaceId) ||
            !validCollaborationMessageId(value.accountId) ||
            value.version != 0L ||
            value.createdAt.toEpochMilliseconds() !in 0..253_402_300_799_999L ||
            !validCollaborationSpaceServerName(value.serverName)
    )
        return false
    val prefix =
        when (value.origin) {
            CollaborationSpaceIdentityOrigin.LEGACY -> {
                val company = value.legacyCompanyId ?: return false
                if (!validCollaborationMessageId(company)) return false
                "hris_${company.replace("-", "")}_"
            }
            CollaborationSpaceIdentityOrigin.SPACE -> {
                if (value.legacyCompanyId != null) return false
                "hrisg_${value.spaceId.replace("-", "")}_"
            }
        }
    return value.matrixUserId == "@$prefix${value.accountId.replace("-", "")}:${value.serverName}"
}

/** Matrix realm metadata only; never chooses the configured transport origin. */
fun validCollaborationSpaceServerName(server: String): Boolean =
    server.length in 1..180 &&
        server.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".-:[]" } &&
        server.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
        validExplicitPort(server)

/** URL libraries may normalize explicit port zero to the scheme default. Validate raw intent. */
private fun validExplicitPort(server: String): Boolean {
    val suffix =
        if (server.startsWith('[')) {
            val end = server.indexOf(']')
            if (end < 2 || ':' !in server.substring(1, end)) return false
            server.substring(end + 1)
        } else {
            if ('[' in server || ']' in server || server.startsWith(':')) return false
            val start = server.indexOf(':')
            if (start < 0) "" else server.substring(start)
        }
    if (suffix.isEmpty()) return true
    if (!suffix.startsWith(':')) return false
    val port = suffix.drop(1)
    return port.isNotEmpty() && port.all { it in '0'..'9' } && port.toIntOrNull() in 1..65535
}
