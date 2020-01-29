@file:Suppress("PackageDirectoryMismatch")

package dfxyz.portal.extensions.httpServerRequest

import dfxyz.portal.PORTAL_HEADER_METHOD
import dfxyz.portal.PORTAL_HEADER_PASSWORD
import dfxyz.portal.PORTAL_HEADER_REAL_IP
import dfxyz.portal.PORTAL_HEADER_URI
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest

fun HttpServerRequest.toLogString(): String {
    val host = this.getPortalRealIp() ?: this.remoteAddress()?.host()
    val method = this.getRawPortalMethod() ?: this.rawMethod()
    val uri = this.getPortalUri() ?: this.uri()
    return "$host $method $uri"
}

fun HttpServerRequest.getPortalRealIp(): String? {
    return this.getHeader(PORTAL_HEADER_REAL_IP)
}

private fun HttpServerRequest.getRawPortalMethod(): String? {
    return this.getHeader(PORTAL_HEADER_METHOD)
}

fun HttpServerRequest.getPortalMethod(): HttpMethod? {
    return getRawPortalMethod()?.let {
        kotlin.runCatching { HttpMethod.valueOf(it) }.getOrNull()
    }
}

fun HttpServerRequest.getPortalUri(): String? {
    return this.getHeader(PORTAL_HEADER_URI)
}

fun HttpServerRequest.getPortalPassword(): String? {
    return this.getHeader(PORTAL_HEADER_PASSWORD)
}

fun HttpServerRequest.getAuthorization(): String? {
    return this.getHeader("Authorization")
}

fun HttpServerRequest.getProxyAuthorization(): String? {
    return this.getHeader("Proxy-Authorization")
}
