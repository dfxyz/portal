package dfxyz.portal

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import dfxyz.portal.extensions.string.toBase64EncodedString
import io.vertx.core.VertxOptions
import io.vertx.core.json.Json
import io.vertx.core.net.ProxyOptions
import io.vertx.core.net.ProxyType
import io.vertx.kotlin.core.net.proxyOptionsOf
import java.io.File
import java.net.URL

private const val CONFIG_FILENAME = "portal.config.json"

private const val DEFAULT_INSTANCE_NUMBER = 2
private const val DEFAULT_CLIENT_POOL_SIZE_PER_ENDPOINT = 32
private const val DEFAULT_CLIENT_POOL_SIZE_FOR_PORTAL_RELAY = 128

/** Load and return a [PortalConfig] instance; Return null if any exception caught */
fun loadConfig(): PortalConfig? {
    return runCatching {
        Json.decodeValue(File(CONFIG_FILENAME).readText(), PortalConfig::class.java).apply { validate() }
    }.onFailure {
        if (it !is PortalConfigException) logFatal("failed to load portal config", it)
    }.getOrNull()
}

private class PortalConfigException : RuntimeException()

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
abstract class AbstractPortalConfig {
    open fun validate() {}

    protected fun configException(fieldName: String): Nothing {
        logFatal("'${this.javaClass.simpleName}#$fieldName' is not properly configured")
        throw PortalConfigException()
    }
}

class PortalConfig : AbstractPortalConfig() {
    @JsonProperty("instanceNumber")
    private var _instanceNumber = ""
    @JsonIgnore
    var instanceNumber = DEFAULT_INSTANCE_NUMBER; private set

    var host = ""; private set
    var port = 0; private set

    var clientPoolSizePerEndpoint = DEFAULT_CLIENT_POOL_SIZE_PER_ENDPOINT; private set

    @JsonProperty("web")
    val webConfig = PortalWebConfig()

    @JsonProperty("directProxy")
    val directProxyConfig = PortalDirectProxyConfig()

    @JsonProperty("relayProxy")
    val relayProxyConfig = PortalRelayProxyConfig()

    override fun validate() {
        instanceNumber = if (_instanceNumber == "auto") {
            VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE
        } else {
            _instanceNumber.toIntOrNull() ?: configException(this::instanceNumber.name)
        }
        if (instanceNumber <= 0) {
            configException(this::instanceNumber.name)
        }

        host = host.trim()
        if (host.isEmpty()) {
            configException(this::host.name)
        }

        if (port !in 1..65535) {
            configException(this::port.name)
        }

        if (clientPoolSizePerEndpoint <= 0) {
            configException(this::clientPoolSizePerEndpoint.name)
        }

        webConfig.validate()
        directProxyConfig.validate()
        relayProxyConfig.validate()
    }
}

class PortalWebConfig : AbstractPortalConfig() {
    var enabled = false; private set

    @JsonIgnore
    var authorization = ""; private set
    private var username = ""
    private var password = ""

    override fun validate() {
        if (!enabled) return
        if (username.isNotEmpty() && password.isNotEmpty()) {
            authorization = "Basic " + "$username:$password".toBase64EncodedString()
        }
    }
}

class PortalDirectProxyConfig : AbstractPortalConfig() {
    var enabled = false; private set

    var defaultMode = ProxyMode.RULE; private set

    @JsonIgnore
    var authorization = ""; private set
    private var username = ""
    private var password = ""

    val remoteProxyRuleUrls = arrayListOf<String>()

    lateinit var relayHandlerType: RelayHandlerType

    @JsonProperty("portalRelayHandler")
    lateinit var portalRelayHandlerConfig: PortalRelayHandlerConfig

    @JsonProperty("proxyRelayHandler")
    lateinit var proxyRelayHandlerConfig: ProxyRelayHandlerConfig

    override fun validate() {
        if (!enabled) return
        if (username.isNotEmpty() && password.isNotEmpty()) {
            authorization = "Basic " + "$username:$password".toBase64EncodedString()
        }
        if (!this::relayHandlerType.isInitialized) {
            configException(this::relayHandlerType.name)
        }
        when (relayHandlerType) {
            RelayHandlerType.PORTAL -> {
                if (!this::portalRelayHandlerConfig.isInitialized) {
                    configException(this::portalRelayHandlerConfig.name)
                }
                portalRelayHandlerConfig.validate()
            }
            RelayHandlerType.PROXY -> {
                if (!this::proxyRelayHandlerConfig.isInitialized) {
                    configException(this::proxyRelayHandlerConfig.name)
                }
                proxyRelayHandlerConfig.validate()
            }
        }
    }
}

class PortalRelayProxyConfig : AbstractPortalConfig() {
    var enabled = false; private set
    var password = ""; private set

    override fun validate() {
        if (enabled) {
            password = password.toBase64EncodedString()
        }
    }
}

class PortalRelayHandlerConfig : AbstractPortalConfig() {
    var url = ""; private set
    var password = ""; private set
    var clientPoolSize = DEFAULT_CLIENT_POOL_SIZE_FOR_PORTAL_RELAY; private set

    override fun validate() {
        url = url.trim()
        if (url.isEmpty() || kotlin.runCatching { URL(url) }.getOrNull() == null) {
            configException(this::url.name)
        }

        password = password.toBase64EncodedString()

        if (clientPoolSize <= 0) {
            configException(this::clientPoolSize.name)
        }
    }
}

class ProxyRelayHandlerConfig : AbstractPortalConfig() {
    private lateinit var protocol: ProxyType
    private var host = ""
    private var port = 0
    private var username = ""
    private var password = ""
    var clientPoolSizePerEndpoint = DEFAULT_CLIENT_POOL_SIZE_PER_ENDPOINT; private set

    lateinit var proxyOptions: ProxyOptions; private set

    override fun validate() {
        if (!this::protocol.isInitialized) {
            configException(this::protocol.name)
        }

        host = host.trim()
        if (host.isEmpty()) {
            configException(this::host.name)
        }

        if (port !in 1..65535) {
            configException(this::port.name)
        }

        proxyOptions = proxyOptionsOf(type = protocol, host = host, port = port)
        if (username.isNotEmpty() && password.isNotEmpty()) {
            proxyOptions.username = username
            proxyOptions.password = password
        }

        if (clientPoolSizePerEndpoint <= 0) {
            configException(this::clientPoolSizePerEndpoint.name)
        }
    }
}
