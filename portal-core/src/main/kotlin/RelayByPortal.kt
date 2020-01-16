package dfxyz.portal.relay

import dfxyz.portal.*
import dfxyz.portal.logger.*
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.kotlin.core.http.httpClientOptionsOf
import java.net.URL
import java.util.*

private const val PK_RELAY_PORTAL_URL = "portal.directProxy.relay.portal.url"
private const val PK_RELAY_PORTAL_AUTH = "portal.directProxy.relay.portal.auth"
private const val PK_RELAY_PORTAL_CLIENT_MAX_POOL_SIZE = "portal.directProxy.relay.portal.client.maxPoolSize"

class RelayByPortal(properties: Properties) : RelayHandler {
    private val client: HttpClient
    private val url: String
    private val auth: String

    init {
        val clientOptions = httpClientOptionsOf()
        val clientPoolSize = properties.getProperty(PK_RELAY_PORTAL_CLIENT_MAX_POOL_SIZE)?.toIntOrNull()
        if (clientPoolSize != null) {
            clientOptions.maxPoolSize = clientPoolSize
        }
        client = vertx.createHttpClient(clientOptions)

        url = properties.getString(PK_RELAY_PORTAL_URL)
        URL(url) // check if the url is valid

        val rawAuth = properties.getProperty(PK_RELAY_PORTAL_AUTH)
        auth = if (rawAuth.isEmpty()) {
            rawAuth
        } else {
            Base64.getEncoder().encodeToString(rawAuth.toByteArray())
        }
    }

    private fun setupHeaders(relayedRequest: HttpClientRequest, request: HttpServerRequest) {
        relayedRequest.copyHeaders(request)
        relayedRequest
            .putHeader("upgrade", "portal")
            .putHeader("connection", "upgrade")
            .setRelayedMethod(request.rawMethod())
            .setRelayedUri(request.uri())
            .setPortalAuth(auth)
    }

    override fun relayConnectRequest(request: HttpServerRequest) {
        @Suppress("DEPRECATION")
        val relayedRequest = vertx.createHttpClient().requestAbs(HttpMethod.OTHER, url)
            .setRawMethod(PORTAL_HTTP_METHOD)
            .exceptionHandler { onProxiedRequestException(request, it) }
            .handler { relayedResponse ->
                val statusCode = relayedResponse.statusCode()
                if (statusCode != HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
                    request.response().setStatusCode(statusCode).endAndClose()
                    failedAccess(request)
                    return@handler
                }

                val sourceSocket = request.netSocket()
                val targetSocket = relayedResponse.netSocket()

                sourceSocket.pipeTo(targetSocket)
                targetSocket.pipeTo(sourceSocket)
            }
        setupHeaders(relayedRequest, request)
        relayedRequest.sendHead()
    }

    override fun relayNonConnectRequest(request: HttpServerRequest) {
        @Suppress("DEPRECATION")
        val relayedRequest = client.requestAbs(HttpMethod.OTHER, url).setRawMethod(PORTAL_HTTP_METHOD)
            .exceptionHandler { onProxiedRequestException(request, it) }
            .handler { onProxiedRequestResponded(request, it) }
        setupHeaders(relayedRequest, request)
        request.pipeTo(relayedRequest)
    }
}
