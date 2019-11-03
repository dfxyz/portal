package dfxyz.portal

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.HttpServerResponse
import java.util.*

private const val HEADER_RELAYED_REAL_IP = "x-portal-real-ip"
private const val HEADER_RELAYED_METHOD = "x-portal-method"
private const val HEADER_RELAYED_URI = "x-portal-uri"
private const val HEADER_PORTAL_AUTH = "x-portal-auth"

fun HttpServerRequest.getRelayedRealIp(): String? {
    return this.getHeader(HEADER_RELAYED_REAL_IP)
}

private fun HttpServerRequest.getRelayedRawMethod(): String? {
    return this.getHeader(HEADER_RELAYED_METHOD)
}

fun HttpServerRequest.getRelayedMethod(): HttpMethod? {
    return getRelayedRawMethod()?.let {
        try {
            return@let HttpMethod.valueOf(it)
        } catch (e: Exception) {
            return@let null
        }
    }
}

fun HttpServerRequest.getRelayedUri(): String? {
    return this.getHeader(HEADER_RELAYED_URI)
}

fun HttpServerRequest.getPortalAuth(): String? {
    return this.getHeader(HEADER_PORTAL_AUTH)
}

fun HttpServerRequest.toLogString(): String {
    val host = this.getRelayedRealIp() ?: this.remoteAddress().host()
    val method = this.getRelayedRawMethod() ?: this.rawMethod()
    val uri = this.getRelayedUri() ?: this.uri()
    return "$host $method $uri"
}

fun HttpServerResponse.setStatus(status: HttpResponseStatus): HttpServerResponse {
    return this.setStatusCode(status.code())
}

fun HttpServerResponse.endAndClose() {
    this.putHeader("connection", "close").endHandler { this.close() }.end()
}

fun HttpServerResponse.endAndClose(message: String) {
    this.putHeader("connection", "close").endHandler { this.close() }.end(message)
}

private val ignoredHeaderPrefixes = listOf("proxy-", "x-portal-")

fun HttpClientRequest.copyHeaders(other: HttpServerRequest) {
    headerLoop@ for (header in other.headers()) {
        val key = header.key.toLowerCase()
        for (prefix in ignoredHeaderPrefixes) {
            if (key.startsWith(prefix)) {
                continue@headerLoop
            }
        }
        this.putHeader(header.key, header.value)
    }
}

fun HttpClientRequest.setRelayedMethod(rawMethod: String): HttpClientRequest {
    return this.putHeader(HEADER_RELAYED_METHOD, rawMethod)
}

fun HttpClientRequest.setRelayedUri(uri: String): HttpClientRequest {
    return this.putHeader(HEADER_RELAYED_URI, uri)
}

fun HttpClientRequest.setPortalAuth(auth: String): HttpClientRequest {
    return this.putHeader(HEADER_PORTAL_AUTH, auth)
}

fun Properties.getString(key: String): String {
    return this.getProperty(key) ?: throw RuntimeException("failed to load property '$key'")
}

fun Properties.getInt(key: String): Int {
    return this.getProperty(key)?.toIntOrNull() ?: throw RuntimeException("failed to load property '$key'")
}

fun Properties.getStringList(key: String): List<String> {
    return this.getProperty(key)?.split(',') ?: emptyList()
}
