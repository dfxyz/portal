package dfxyz.portal.relay

import dfxyz.portal.getString
import io.vertx.core.http.HttpServerRequest
import java.util.*

private const val PK_RELAY_TYPE = "portal.directProxy.relayType"

interface RelayHandler {
    fun relayConnectRequest(request: HttpServerRequest)
    fun relayNonConnectRequest(request: HttpServerRequest)
}

private lateinit var relayHandler: RelayHandler

fun init(properties: Properties) {
    relayHandler = when (properties.getString(PK_RELAY_TYPE)) {
        "portal" -> RelayByPortal(properties)
        "proxy" -> RelayByProxy(properties)
        else -> throw RuntimeException("unknown relay type '${properties.getString(PK_RELAY_TYPE)}'")
    }
}

fun relayConnectRequest(request: HttpServerRequest) = relayHandler.relayConnectRequest(request)
fun relayNonConnectRequest(request: HttpServerRequest) = relayHandler.relayNonConnectRequest(request)
