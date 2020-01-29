@file:Suppress("PackageDirectoryMismatch")

package dfxyz.portal.extensions.httpClientRequest

import dfxyz.portal.PORTAL_HEADER_METHOD
import dfxyz.portal.PORTAL_HEADER_PASSWORD
import dfxyz.portal.PORTAL_HEADER_PREFIX
import dfxyz.portal.PORTAL_HEADER_URI
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest

fun HttpClientRequest.setHeaders(
    request: HttpServerRequest,
    prependPortalHeaderPrefix: Boolean = false
): HttpClientRequest {
    this.headers().clear()
    loop@ for (header in request.headers()) {
        val lowerCaseKey = header.key.toLowerCase()
        if (lowerCaseKey.startsWith("proxy-")) continue@loop
        val key = if (prependPortalHeaderPrefix) {
            PORTAL_HEADER_PREFIX + header.key
        } else {
            header.key
        }
        this.putHeader(key, header.value)
    }
    return this
}

fun HttpClientRequest.setHeadersWithPortalHeaderPrefix(request: HttpServerRequest): HttpClientRequest {
    this.headers().clear()
    request.headers().forEach {
        val lowerCaseKey = it.key.toLowerCase()
        if (lowerCaseKey.startsWith(PORTAL_HEADER_PREFIX)) {
            val key = it.key.substring(PORTAL_HEADER_PREFIX.length)
            this.putHeader(key, it.value)
        }
    }
    return this
}

fun HttpClientRequest.setPortalMethod(method: HttpMethod): HttpClientRequest {
    return this.putHeader(PORTAL_HEADER_METHOD, method.name)
}

fun HttpClientRequest.setPortalUri(uri: String): HttpClientRequest {
    return this.putHeader(PORTAL_HEADER_URI, uri)
}

fun HttpClientRequest.setPortalPassword(password: String): HttpClientRequest {
    if (password.isEmpty()) return this
    return this.putHeader(PORTAL_HEADER_PASSWORD, password)
}