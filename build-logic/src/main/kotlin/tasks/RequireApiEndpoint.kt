package tasks

import java.net.URI

/** Plain HTTP is limited to local development addresses, without resolving DNS. */
fun requireApiEndpoint(environment: String, value: String) {
    val uri = URI(value)
    val host = uri.host.orEmpty()
    val octets =
        host.split('.').mapNotNull { part ->
            part
                .takeIf { it.matches(Regex("0|[1-9][0-9]{0,2}")) }
                ?.toIntOrNull()
                ?.takeIf { it in 0..255 }
        }
    val privateIpv4 =
        octets.size == 4 &&
            host.count { it == '.' } == 3 &&
            (octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168))
    val developmentHttp =
        environment == "dev" &&
            uri.scheme == "http" &&
            (host in setOf("127.0.0.1", "localhost") || privateIpv4)
    require(
        (uri.scheme == "https" || developmentHttp) &&
            host.isNotBlank() &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null
    ) {
        "API endpoints require HTTPS; dev also allows loopback or private IPv4 HTTP. Credentials, queries and fragments are forbidden."
    }
}
