package dfxyz.portal

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Launcher
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.core.net.NetSocket
import io.vertx.core.net.ProxyOptions
import io.vertx.core.net.ProxyType
import io.vertx.kotlin.core.http.httpClientOptionsOf
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.core.net.netClientOptionsOf
import io.vertx.kotlin.core.net.proxyOptionsOf
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.*

private const val VERTICLE_ID = "dfxyz:portal"

private const val PROPERTIES_FILENAME = "portal.properties"
private const val MODE_FILENAME = "portal.mode"
private const val LOCAL_RULE_FILENAME = "portal.rules.local"
private const val EXTERNAL_RULE_FILENAME = "portal.rules.external"

private const val PK_HOST = "portal.host"
private const val PK_PORT = "portal.port"
private const val PK_CLIENT_POOL_SIZE = "portal.clientPoolSize"
private const val PK_PROXY_ENABLE = "portal.proxy.enable"
private const val PK_PROXY_EXTERNAL_RULE_URL = "portal.proxy.externalRule.url"
private const val PK_PROXY_EXTERNAL_RULE_BASE64_ENCODED = "portal.proxy.externalRule.base64Encoded"
private const val PK_PROXY_RELAY_TYPE = "portal.proxy.relay.type"
private const val PK_PROXY_RELAY_PORTAL_URL = "portal.proxy.relay.portal.url"
private const val PK_PROXY_RELAY_PORTAL_AUTH = "portal.proxy.relay.portal.authenticate"
private const val PK_PROXY_RELAY_PORTAL_CLIENT_POOL_SIZE = "portal.proxy.relay.portal.clientPoolSize"
private const val PK_PROXY_RELAY_PROXY_PROTOCOL = "portal.proxy.relay.proxy.protocol"
private const val PK_PROXY_RELAY_PROXY_HOST = "portal.proxy.relay.proxy.host"
private const val PK_PROXY_RELAY_PROXY_PORT = "portal.proxy.relay.proxy.port"
private const val PK_PROXY_RELAY_PROXY_USERNAME = "portal.proxy.relay.proxy.username"
private const val PK_PROXY_RELAY_PROXY_PASSWORD = "portal.proxy.relay.proxy.password"
private const val PK_PROXY_RELAY_PROXY_CLIENT_POOL_SIZE = "portal.proxy.relay.proxy.clientPoolSize"
private const val PK_RELAY_ENABLE = "portal.relay.enable"
private const val PK_RELAY_AUTH = "portal.relay.authenticate"

private const val PORTAL_HTTP_METHOD = "PORTAL"
private const val PORTAL_HTTP_HEADER_METHOD = "x-portal-method"
private const val PORTAL_HTTP_HEADER_URI = "x-portal-uri"
private const val PORTAL_HTTP_HEADER_AUTH = "x-portal-authenticate"
private const val PORTAL_HTTP_HEADER_REAL_IP = "x-portal-real-ip"
private const val PORTAL_200_OK = "HTTP/1.1 200 OK\r\n\r\n"

private enum class ProxyMode { RULE, DIRECT, RELAY }

private enum class RelayType { PORTAL, PROXY }

private val ignoredHeaderPrefixes = listOf("proxy-", "x-portal-")

private lateinit var logger: Logger

private lateinit var vertxInstance: Vertx

private lateinit var httpClient: HttpClient

private var proxyEnabled = true
private var proxyMode = ProxyMode.RULE
private lateinit var proxyLocalRuleSet: HostRuleSet
private lateinit var proxyExternalRuleSet: HostRuleSet
private lateinit var proxyExternalRuleUrl: String
private var proxyExternalRuleBase64Encoded = true
private lateinit var proxyRelayHandler: RelayHandler

private var relayEnabled = false
private lateinit var relayAuthenticate: String

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "start" -> Launcher.main(
            arrayOf(
                "start", Portal::class.java.name,
                "-id", VERTICLE_ID,
                "--java-opts=-Dlog4j.configurationFile=log4j2.xml"
            )
        )

        "stop" -> Launcher.main(arrayOf("stop", VERTICLE_ID))

        "list" -> Launcher.main(arrayOf("list"))

        else -> {
            System.setProperty("log4j.configurationFile", "log4j2.xml")
            Launcher.main(arrayOf("run", Portal::class.java.name))
        }
    }
}

class Portal : AbstractVerticle() {
    override fun start() {
        logger = LogManager.getLogger(this::class.java.packageName)
        vertxInstance = vertx
        initialize()
    }

    override fun stop() {
        logger.info("shutdown")
    }
}


private fun initialize() {
    val properties = Properties()
    try {
        File(PROPERTIES_FILENAME).inputStream().use { properties.load(it) }
    } catch (e: Exception) {
        logger.error("failed to load '$PROPERTIES_FILENAME'")
        vertxInstance.close()
        return
    }
    try {
        initHttpServerAndClient(properties)
        initProxyFunction(properties)
        initRelayFunction(properties)
    } catch (e: Exception) {
        logger.error("failed to initialize")
        recordException(e)
        vertxInstance.close()
    }
}

private fun initHttpServerAndClient(properties: Properties) {
    val host = properties.getString(PK_HOST)
    val port = properties.getInt(PK_PORT)
    vertxInstance.createHttpServer(httpServerOptionsOf(host = host, port = port))
        .requestHandler(::handleRequest)
        .listen {
            if (it.failed()) {
                logger.error("failed to listen at $host:$port")
                vertxInstance.close()
            } else {
                logger.info("listening at $host:$port")
            }
        }

    val clientOptions = httpClientOptionsOf()
    val clientPoolSize = properties.getOptionalString(PK_CLIENT_POOL_SIZE)?.toIntOrNull()
    if (clientPoolSize != null) {
        clientOptions.maxPoolSize = clientPoolSize
    }
    httpClient = vertxInstance.createHttpClient(clientOptions)
}

private fun initProxyFunction(properties: Properties) {
    properties.getOptionalString(PK_PROXY_ENABLE)?.toBoolean()?.also { proxyEnabled = it }
    if (!proxyEnabled) {
        return
    }

    loadPreviousProxyMode()
    loadProxyRules(LOCAL_RULE_FILENAME, HostRuleSet().also { proxyLocalRuleSet = it })
    loadProxyRules(EXTERNAL_RULE_FILENAME, HostRuleSet().also { proxyExternalRuleSet = it })

    proxyExternalRuleUrl = properties.getOptionalString(PK_PROXY_EXTERNAL_RULE_URL) ?: ""
    properties.getOptionalString(PK_PROXY_EXTERNAL_RULE_BASE64_ENCODED)?.toBoolean()?.also {
        proxyExternalRuleBase64Encoded = it
    }

    val relayType = properties.getString(PK_PROXY_RELAY_TYPE).toUpperCase()
    proxyRelayHandler = when (RelayType.valueOf(relayType)) {
        RelayType.PORTAL -> PortalRelayHandler(properties)
        RelayType.PROXY -> ProxyRelayHandler(properties)
    }
}

private fun loadProxyRules(filename: String, hostRuleSet: HostRuleSet) {
    try {
        File(filename).inputStream().use {
            hostRuleSet.load(it.readAllBytes().toString(Charset.defaultCharset()))
        }
    } catch (ignore: Exception) {
    }
}

private fun loadPreviousProxyMode() {
    try {
        File(MODE_FILENAME).inputStream().use {
            proxyMode = ProxyMode.valueOf(it.readAllBytes().toString(Charset.defaultCharset()))
        }
    } catch (ignore: Exception) {
    }
    logger.info("proxy mode: ${proxyMode.name}")
}

private fun initRelayFunction(properties: Properties) {
    properties.getOptionalString(PK_RELAY_ENABLE)?.toBoolean()?.also { relayEnabled = it }
    if (!relayEnabled) {
        return
    }

    val rawAuthenticate = properties.getString(PK_RELAY_AUTH)
    relayAuthenticate = if (rawAuthenticate.isEmpty()) {
        rawAuthenticate
    } else {
        Base64.getEncoder().encodeToString(rawAuthenticate.toByteArray())
    }
}


private fun handleRequest(request: HttpServerRequest) {
    val uri = request.uri()
    when {
        uri.startsWith("*") -> {
            // ignore asterisk-form
            request.response().setStatus(HttpResponseStatus.BAD_REQUEST).endAndClose()
            recordDeniedAccess(request)
        }
        uri.startsWith("/") -> when (request.method()) {
            HttpMethod.GET -> handleGetRequest(request)
            HttpMethod.OTHER -> handlePortalRequest(request)
            else -> {
                request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
                recordDeniedAccess(request)
            }
        }
        else -> when (request.method()) {
            HttpMethod.CONNECT -> proxyConnectRequest(request, asRelay = false)
            else -> proxyNonConnectRequest(request, asRelay = false)
        }
    }
}


private fun handleGetRequest(request: HttpServerRequest) {
    if (!proxyEnabled || !isLoopbackAddress(request.remoteAddress().host())) {
        request.response().setStatus(HttpResponseStatus.NOT_FOUND).endAndClose()
        recordDeniedAccess(request)
        return
    }

    when (request.path()) {
        "/" -> {
            val buffer = Buffer.buffer().apply {
                appendString("mode=${proxyMode.name}\n")
                appendString("localRuleNumber=${proxyLocalRuleSet.size}\n")
                appendString("externalRuleNumber=${proxyExternalRuleSet.size}\n")
            }
            request.response().end(buffer)
        }
        "/ruleMode" -> changeProxyMode(request, ProxyMode.RULE)
        "/directMode" -> changeProxyMode(request, ProxyMode.DIRECT)
        "/relayMode" -> changeProxyMode(request, ProxyMode.RELAY)
        "/updateLocalRules" -> {
            vertxInstance.fileSystem().readFile(LOCAL_RULE_FILENAME) {
                if (it.succeeded()) {
                    proxyLocalRuleSet.load(it.result().bytes.toString(Charset.defaultCharset()))
                    logger.info("local rules reloaded")
                } else {
                    vertxInstance.fileSystem().createFile(LOCAL_RULE_FILENAME, null)
                    logger.info("local rules not exist, an empty file has been created")
                }
                request.response().end("localRuleNumber=${proxyLocalRuleSet.size}\n")
            }
        }
        "/updateExternalRules" -> {
            if (proxyExternalRuleUrl.isEmpty()) {
                request.response().end("property '$PK_PROXY_EXTERNAL_RULE_URL' not set\n")
                return
            }
            @Suppress("DEPRECATION")
            httpClient.getAbs(proxyExternalRuleUrl)
                .exceptionHandler {
                    request.response().end("exception caught on sending request\n")
                    logger.error("failed to update external rules")
                    recordException(it)
                }.handler { response ->
                    val statusCode = response.statusCode()
                    if (statusCode != HttpResponseStatus.OK.code()) {
                        request.response().end("$statusCode received\n")
                        logger.error("failed to update external rules; $statusCode received")
                        return@handler
                    }
                    response
                        .exceptionHandler {
                            request.response().end("exception caught on receiving response\n")
                            logger.error("failed to update external rules")
                            recordException(it)
                        }
                        .bodyHandler { buffer ->
                            try {
                                val rawBytes = if (proxyExternalRuleBase64Encoded) {
                                    Base64.getMimeDecoder().decode(buffer.bytes)
                                } else {
                                    buffer.bytes
                                }
                                vertxInstance.fileSystem()
                                    .writeFile(EXTERNAL_RULE_FILENAME, Buffer.buffer(rawBytes), null)
                                proxyExternalRuleSet.load(rawBytes.toString(Charset.defaultCharset()))
                                request.response().end("externalRuleNumber=${proxyExternalRuleSet.size}\n")
                                logger.info("external rules updated")
                            } catch (e: Exception) {
                                request.response().end("exception caught on handling response\n")
                                logger.error("failed to update external rules")
                                recordException(e)
                            }
                        }
                }.end()
        }
        "/test" -> {
            val query = request.query()
            if (query == null || query.isEmpty()) {
                request.response().setStatus(HttpResponseStatus.BAD_REQUEST).endAndClose()
                return
            }
            val result = testHost(query)
            request.response().end("query=$query\nresult=${result.name}\n")
        }
        "/shutdown" -> {
            request.response().endAndClose()
            vertxInstance.close()
        }

        else -> request.response().setStatus(HttpResponseStatus.NOT_FOUND).end()
    }
}

private fun isLoopbackAddress(host: String): Boolean {
    return try {
        InetAddress.getByName(host).isLoopbackAddress
    } catch (e: Exception) {
        false
    }
}

private fun changeProxyMode(request: HttpServerRequest, mode: ProxyMode) {
    val modeName = mode.name
    if (proxyMode != mode) {
        proxyMode = mode
        vertxInstance.fileSystem().writeFile(MODE_FILENAME, Buffer.buffer(modeName), null)
        logger.info("proxy mode switched: $modeName")
    }
    request.response().end("mode=$modeName\n")
}


private fun handlePortalRequest(request: HttpServerRequest) {
    if (!relayEnabled || request.rawMethod() != PORTAL_HTTP_METHOD) {
        request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
        recordDeniedAccess(request)
        return
    }

    val method: HttpMethod
    try {
        method = HttpMethod.valueOf(request.getHeader(PORTAL_HTTP_HEADER_METHOD))
    } catch (e: Exception) {
        request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
        recordDeniedAccess(request)
        return
    }

    val uri = request.getHeader(PORTAL_HTTP_HEADER_URI) ?: ""
    if (uri.isEmpty()) {
        request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
        recordDeniedAccess(request)
        return
    }

    val authenticate = request.getHeader(PORTAL_HTTP_HEADER_AUTH) ?: ""
    if (authenticate != relayAuthenticate) {
        request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
        recordDeniedAccess(request)
        return
    }

    when (method) {
        HttpMethod.CONNECT -> proxyConnectRequest(request, asRelay = true)
        else -> proxyNonConnectRequest(request, asRelay = true)
    }
}


private fun proxyConnectRequest(request: HttpServerRequest, asRelay: Boolean) {
    val uri: URI? = try {
        URI("//${request.getIntendedUri()}").let { if (it.port == -1) null else it }
    } catch (ignore: Exception) {
        null
    }
    if (uri == null) {
        val status = if (asRelay) HttpResponseStatus.METHOD_NOT_ALLOWED else HttpResponseStatus.BAD_GATEWAY
        request.response().setStatus(status).endAndClose()
        recordDeniedAccess(request)
        return
    }

    if (!asRelay && !canProxyDirectly(uri.host)) {
        proxyRelayHandler.relayConnectRequest(request)
        recordRelayedAccess(request)
        return
    }

    if (asRelay && isLoopbackAddress(uri.host)) {
        val status = HttpResponseStatus.BAD_GATEWAY
        request.response().setStatus(status).endAndClose()
        recordDeniedAccess(request)
        return
    }

    vertxInstance.createNetClient().connect(uri.port, uri.host) { onProxyRequestConnected(request, it, asRelay) }

    if (asRelay) {
        recordAcceptedAccess(request)
    } else {
        recordDirectAccess(request)
    }
}

private fun onProxyRequestConnected(
    request: HttpServerRequest,
    asyncResult: AsyncResult<NetSocket>,
    writeOkResponse: Boolean = false
) {
    if (asyncResult.failed()) {
        request.response().setStatus(HttpResponseStatus.BAD_GATEWAY).endAndClose()
        recordFailedAccess(request)
        recordException(asyncResult.cause())
        return
    }

    val sourceSocket = request.netSocket()
    if (writeOkResponse) {
        sourceSocket.write(PORTAL_200_OK)
    }
    val targetSocket = asyncResult.result()

    sourceSocket.pipeTo(targetSocket)
    targetSocket.pipeTo(sourceSocket)
}


private fun proxyNonConnectRequest(request: HttpServerRequest, asRelay: Boolean) {
    val requestMethod = request.getIntendedMethod()
    val requestUri = request.getIntendedUri()
    val url: URL? = try {
        URL(requestUri).let { if (it.protocol != "http") null else it }
    } catch (ignore: Exception) {
        null
    }
    if (url == null) {
        val status = if (asRelay) HttpResponseStatus.METHOD_NOT_ALLOWED else HttpResponseStatus.BAD_GATEWAY
        request.response().setStatus(status).endAndClose()
        recordDeniedAccess(request)
        return
    }

    if (!asRelay && !canProxyDirectly(url.host)) {
        proxyRelayHandler.relayNonConnectRequest(request)
        recordRelayedAccess(request)
        return
    }

    if (asRelay && isLoopbackAddress(url.host)) {
        val status = HttpResponseStatus.BAD_GATEWAY
        request.response().setStatus(status).endAndClose()
        recordDeniedAccess(request)
        return
    }

    @Suppress("DEPRECATION")
    val proxyRequest = httpClient.requestAbs(requestMethod, requestUri)
        .exceptionHandler { onProxyRequestException(request, it) }
        .handler { onProxyRequestResponded(request, it) }
    copyHeaders(proxyRequest, request)
    request.pipeTo(proxyRequest)

    if (asRelay) {
        recordAcceptedAccess(request)
    } else {
        recordDirectAccess(request)
    }
}

private fun copyHeaders(proxyRequest: HttpClientRequest, request: HttpServerRequest) {
    headerLoop@ for (header in request.headers()) {
        val key = header.key.toLowerCase()
        for (prefix in ignoredHeaderPrefixes) {
            if (key.startsWith(prefix)) {
                continue@headerLoop
            }
        }
        proxyRequest.putHeader(header.key, header.value)
    }
}

private fun onProxyRequestException(request: HttpServerRequest, throwable: Throwable) {
    request.response().setStatus(HttpResponseStatus.BAD_GATEWAY).endAndClose()
    recordFailedAccess(request)
    recordException(throwable)
}

private fun onProxyRequestResponded(request: HttpServerRequest, response: HttpClientResponse) {
    request.response().statusCode = response.statusCode()
    for (header in response.headers()) {
        request.response().putHeader(header.key, header.value)
    }
    response.pipeTo(request.response())
}


private enum class HostTestResult { WHITE, BLACK, UNKNOWN }

private class HostRuleSet {
    private val whiteHosts = HashSet<String>()
    private val blackHosts = HashSet<String>()

    val size
        get() = whiteHosts.size + blackHosts.size

    fun load(fileContent: String) {
        whiteHosts.clear()
        blackHosts.clear()

        for (rawLine in fileContent.split("\n")) {
            // ignore comments and blank lines
            if (rawLine.startsWith("[") || rawLine.startsWith("!") || rawLine.isBlank()) {
                continue
            }

            // determine rule type
            val hosts: MutableSet<String>
            var line = rawLine.trim()
            if (line.startsWith("@@")) {
                hosts = whiteHosts
                line = rawLine.substring(2)
            } else {
                hosts = blackHosts
                line = rawLine
            }

            // ignore regex rules
            if (line.startsWith("/") && line.endsWith("/")) {
                if (logger.isDebugEnabled) {
                    logger.debug("regex-rule ignored: $rawLine")
                }
                continue
            }

            // is it a domain rule?
            if (line.startsWith("||")) {
                val host = line.substring(2)
                if (logger.isDebugEnabled) {
                    logger.debug("host '$host' added: $rawLine")
                }
                hosts.add(host)
                continue
            }

            // is it a start-with-rule or a contain-rule?
            if (line.startsWith("|")) {
                val index = line.indexOf("//")
                if (index < 0) {
                    if (logger.isDebugEnabled) {
                        logger.debug("invalid start-with-rule ignored: $rawLine")
                    }
                    continue
                }
                // remove 'scheme://' part
                line = line.substring(index + 2)
            } else if (line.startsWith(".")) {
                // remove leading dot
                line = line.substring(1)
            }

            // remove leading "*." part
            if (line.startsWith("*.")) {
                line = line.substring(2)
            }

            // if it still contains "*", ignore it
            if (line.contains("*")) {
                if (logger.isDebugEnabled) {
                    logger.debug("wildcard-rule ignored: $rawLine")
                }
                continue
            }

            // if it contains path part, ignore it
            val indexOfPathRoot = line.indexOf("/")
            if (indexOfPathRoot >= 0) {
                if (indexOfPathRoot != line.length - 1) {
                    if (logger.isDebugEnabled) {
                        logger.debug("path-rule ignored: $rawLine")
                    }
                    continue
                }
                line = line.substring(0, indexOfPathRoot)
            }

            // if it contains no "." (like 'localhost'), ignore it
            if (!line.contains(".")) {
                if (logger.isDebugEnabled) {
                    logger.debug("non-domain-rule ignored: $rawLine")
                }
                continue
            }

            if (logger.isDebugEnabled) {
                logger.debug("host '$line' added: $rawLine")
            }
            hosts.add(line)
        }
    }

    fun test(host: String): HostTestResult {
        val hostParts = host.split(".")

        // ignore host without dot like 'localhost'
        if (hostParts.size <= 1) {
            return HostTestResult.UNKNOWN
        }

        if (test(hostParts, whiteHosts)) {
            return HostTestResult.WHITE
        }
        if (test(hostParts, blackHosts)) {
            return HostTestResult.BLACK
        }

        return HostTestResult.UNKNOWN
    }

    private fun test(hostParts: List<String>, hostSet: Set<String>): Boolean {
        var host = hostParts.last()
        for (i in hostParts.lastIndex - 1 downTo 0) {
            host = hostParts[i] + "." + host
            if (hostSet.contains(host)) {
                return true
            }
        }
        return false
    }
}

private fun testHost(host: String): HostTestResult {
    val localResult = proxyLocalRuleSet.test(host)
    if (localResult != HostTestResult.UNKNOWN) {
        return localResult
    }
    return proxyExternalRuleSet.test(host)
}

private fun canProxyDirectly(host: String): Boolean {
    return when (proxyMode) {
        ProxyMode.RULE -> testHost(host) != HostTestResult.BLACK
        ProxyMode.DIRECT -> true
        ProxyMode.RELAY -> false
    }
}


private interface RelayHandler {
    fun relayConnectRequest(request: HttpServerRequest)
    fun relayNonConnectRequest(request: HttpServerRequest)
}

private class PortalRelayHandler(properties: Properties) : RelayHandler {
    private val httpClient: HttpClient
    private val url: String
    private val authenticate: String

    init {
        val clientOptions = httpClientOptionsOf()
        val clientPoolSize = properties.getOptionalString(PK_PROXY_RELAY_PORTAL_CLIENT_POOL_SIZE)?.toIntOrNull()
        if (clientPoolSize != null) {
            clientOptions.maxPoolSize = clientPoolSize
        }
        httpClient = vertxInstance.createHttpClient(clientOptions)

        url = properties.getString(PK_PROXY_RELAY_PORTAL_URL)
        URL(url) // check if the url is valid

        val rawAuthenticate = properties.getString(PK_PROXY_RELAY_PORTAL_AUTH)
        authenticate = if (rawAuthenticate.isEmpty()) {
            rawAuthenticate
        } else {
            Base64.getEncoder().encodeToString(rawAuthenticate.toByteArray())
        }
    }

    private fun copyPortalHeaders(proxyRequest: HttpClientRequest, request: HttpServerRequest) {
        proxyRequest
            .putHeader(PORTAL_HTTP_HEADER_METHOD, request.rawMethod())
            .putHeader(PORTAL_HTTP_HEADER_URI, request.uri())
            .putHeader(PORTAL_HTTP_HEADER_AUTH, authenticate)
    }

    override fun relayConnectRequest(request: HttpServerRequest) {
        // always create a new client to avoid pool size problem
        @Suppress("DEPRECATION")
        val proxyRequest = vertxInstance.createHttpClient().requestAbs(HttpMethod.OTHER, url)
            .setRawMethod(PORTAL_HTTP_METHOD)
            .exceptionHandler { onProxyRequestException(request, it) }
            .handler { proxyResponse ->
                val statusCode = proxyResponse.statusCode()
                if (statusCode != HttpResponseStatus.OK.code()) {
                    request.response().setStatusCode(statusCode).endAndClose()
                    recordFailedAccess(request)
                    return@handler
                }

                val sourceSocket = request.netSocket()
                val targetSocket = proxyResponse.netSocket()

                sourceSocket.pipeTo(targetSocket)
                targetSocket.pipeTo(sourceSocket)
            }
        copyHeaders(proxyRequest, request)
        copyPortalHeaders(proxyRequest, request)
        proxyRequest.sendHead()
    }

    override fun relayNonConnectRequest(request: HttpServerRequest) {
        @Suppress("DEPRECATION")
        val proxyRequest = httpClient.requestAbs(HttpMethod.OTHER, url).setRawMethod(PORTAL_HTTP_METHOD)
            .exceptionHandler { onProxyRequestException(request, it) }
            .handler { onProxyRequestResponded(request, it) }
        copyHeaders(proxyRequest, request)
        copyPortalHeaders(proxyRequest, request)
        request.pipeTo(proxyRequest)
    }
}

private class ProxyRelayHandler(properties: Properties) : RelayHandler {
    private val httpClient: HttpClient
    private val proxyOptions: ProxyOptions

    init {
        val proxyType = ProxyType.valueOf(properties.getString(PK_PROXY_RELAY_PROXY_PROTOCOL).toUpperCase())
        val host = properties.getString(PK_PROXY_RELAY_PROXY_HOST)
        val port = properties.getInt(PK_PROXY_RELAY_PROXY_PORT)
        val username = properties.getOptionalString(PK_PROXY_RELAY_PROXY_USERNAME) ?: ""
        val password = properties.getOptionalString(PK_PROXY_RELAY_PROXY_PASSWORD) ?: ""
        val clientPoolSize = properties.getOptionalString(PK_PROXY_RELAY_PROXY_CLIENT_POOL_SIZE)?.toIntOrNull()
        proxyOptions = proxyOptionsOf(type = proxyType, host = host, port = port)
        if (username.isNotEmpty() && password.isNotEmpty()) {
            proxyOptions.username = username
            proxyOptions.password = password
        }

        val clientOptions = httpClientOptionsOf(proxyOptions = proxyOptions)
        if (clientPoolSize != null) {
            clientOptions.maxPoolSize = clientPoolSize
        }
        httpClient = vertxInstance.createHttpClient(clientOptions)
    }

    override fun relayConnectRequest(request: HttpServerRequest) {
        val uri = URI("//${request.uri()}") // checked before
        vertxInstance.createNetClient(netClientOptionsOf(proxyOptions = proxyOptions))
            .connect(uri.port, uri.host) { onProxyRequestConnected(request, it) }
    }

    override fun relayNonConnectRequest(request: HttpServerRequest) {
        @Suppress("DEPRECATION")
        val proxyRequest = httpClient.requestAbs(request.method(), request.uri())
            .exceptionHandler { onProxyRequestException(request, it) }
            .handler { onProxyRequestResponded(request, it) }
        copyHeaders(proxyRequest, request)
        request.pipeTo(proxyRequest)
    }
}


private fun Properties.getOptionalString(key: String): String? {
    return this.getProperty(key)?.trim()
}

private fun Properties.getString(key: String): String {
    return this.getProperty(key)?.trim() ?: throw RuntimeException("failed to load property '$key'")
}

private fun Properties.getInt(key: String): Int {
    return this.getProperty(key)?.toIntOrNull() ?: throw RuntimeException("failed to load property '$key'")
}


private fun HttpServerRequest.toLogString(): String {
    val host = this.getHeader(PORTAL_HTTP_HEADER_REAL_IP) ?: this.remoteAddress().host()
    val originalMethod = this.rawMethod()
    val method: String
    val uri: String
    if (originalMethod == PORTAL_HTTP_METHOD) {
        method = this.getHeader(PORTAL_HTTP_HEADER_METHOD) ?: originalMethod
        uri = this.getHeader(PORTAL_HTTP_HEADER_URI) ?: this.uri()
    } else {
        method = originalMethod
        uri = this.uri()
    }
    return "$host $method $uri"
}

private fun HttpServerRequest.getIntendedMethod(): HttpMethod {
    val portalRelayedMethod = this.getHeader(PORTAL_HTTP_HEADER_METHOD)
    if (portalRelayedMethod != null) {
        return HttpMethod.valueOf(portalRelayedMethod)
    }
    return this.method()
}

private fun HttpServerRequest.getIntendedUri(): String {
    return this.getHeader(PORTAL_HTTP_HEADER_URI) ?: this.uri()
}


private fun HttpServerResponse.setStatus(status: HttpResponseStatus): HttpServerResponse {
    return this.setStatusCode(status.code())
}

private fun HttpServerResponse.endAndClose() {
    this.putHeader("connection", "close").endHandler { this.close() }.end()
}


private fun recordException(throwable: Throwable) {
    if (logger.isDebugEnabled) {
        logger.debug("exception caught:", throwable)
    } else {
        logger.error("exception caught: ${throwable.message}")
    }
}

private fun recordDirectAccess(request: HttpServerRequest) {
    logger.info("DIRECT ${request.toLogString()}")
}

private fun recordRelayedAccess(request: HttpServerRequest) {
    logger.info("RELAY ${request.toLogString()}")
}

private fun recordAcceptedAccess(request: HttpServerRequest) {
    logger.info("ACCEPTED ${request.toLogString()}")
}

private fun recordDeniedAccess(request: HttpServerRequest) {
    logger.info("DENIED ${request.toLogString()}")
}

private fun recordFailedAccess(request: HttpServerRequest) {
    logger.warn("FAILED ${request.toLogString()}")
}
