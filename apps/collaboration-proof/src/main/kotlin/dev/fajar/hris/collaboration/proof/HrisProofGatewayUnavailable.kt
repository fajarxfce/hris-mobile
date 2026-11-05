package dev.fajar.hris.collaboration.proof

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** Only the owned loopback fixture; the driver has removed its exact ADB reverse mapping. */
internal fun requireHrisProofGatewayUnavailable(home: URI) {
    check(home.scheme == "http" && home.host == "127.0.0.1" && home.port in 1024..65535)
    val refused =
        try {
            Socket().use { it.connect(InetSocketAddress(home.host, home.port), 1000) }
            false
        } catch (_: ConnectException) {
            true
        }
    check(refused) { "Owned offline gateway must refuse connections" }
}
