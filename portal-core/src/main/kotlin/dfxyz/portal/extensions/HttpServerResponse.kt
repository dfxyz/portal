@file:Suppress("PackageDirectoryMismatch")

package dfxyz.portal.extensions.httpServerResponse

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpServerResponse

fun HttpServerResponse.setStatus(status: HttpResponseStatus): HttpServerResponse {
    return this.setStatusCode(status.code())
}

fun HttpServerResponse.setHeaders(response: HttpClientResponse): HttpServerResponse {
    this.headers().clear()
    response.headers().forEach { this.putHeader(it.key, it.value) }
    return this
}
