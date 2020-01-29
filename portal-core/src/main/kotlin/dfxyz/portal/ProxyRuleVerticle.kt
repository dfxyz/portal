package dfxyz.portal

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import java.util.*

private const val LOCAL_RULE_FILENAME = "portal.rules.local"
private const val REMOTE_RULE_FILENAME = "portal.rules.remote"

enum class ProxyMode { DIRECT, RELAY, RULE }

class ProxyRuleVerticle(private val config: PortalDirectProxyConfig) : AbstractVerticle() {
    private var mode = config.defaultMode

    private val localHostRuleSet = HostRuleSet()
    private val remoteHostRuleSet = HostRuleSet()

    override fun start() {
        vertx.eventBus().consumer(MSG_ADDR_GET_PROXY_RULE_INFO, this::handleGetProxyRuleInfoRequest)
        vertx.eventBus().consumer(MSG_ADDR_SET_PROXY_MODE, this::handleSetProxyModeRequest)
        vertx.eventBus().consumer(MSG_ADDR_TEST_PROXY_RULE, this::handleTestProxyRuleRequest)
        vertx.eventBus().consumer(MSG_ADDR_UPDATE_LOCAL_PROXY_RULES, this::handleUpdateLocalRuleRequest)
        vertx.eventBus().consumer(MSG_ADDR_UPDATE_REMOTE_PROXY_RULES, this::handleUpdateRemoteRuleRequest)
        loadLocalRulesFromLocalFile()
        loadRemoteRulesFromLocalFile()
    }

    private fun loadRulesFromLocalFile(ruleSet: HostRuleSet, filename: String) = Future.future<Unit> { promise ->
        vertx.fileSystem().readFile(filename) {
            if (it.failed()) {
                logError("failed to load proxy rules from file '$filename'", it.cause())
                promise.fail(it.cause())
                return@readFile
            }
            kotlin.runCatching {
                it.result().bytes.inputStream().bufferedReader().readLines()
            }.onSuccess { lines ->
                ruleSet.load(lines)
                logInfo("proxy rules loaded from file '$filename'")
                promise.complete()
            }.onFailure { throwable ->
                logError("failed read lines from file '$filename'", throwable)
                promise.fail(throwable)
            }
        }
    }

    private fun loadLocalRulesFromLocalFile() = Future.future<Unit> { promise ->
        vertx.fileSystem().exists(LOCAL_RULE_FILENAME) {
            if (it.failed()) {
                logError("failed to check local proxy rule file", it.cause())
                promise.fail(it.cause())
                return@exists
            }

            if (it.result()) {
                loadRulesFromLocalFile(localHostRuleSet, LOCAL_RULE_FILENAME).setHandler { ar ->
                    promise.handle(ar)
                }
                return@exists
            }

            logInfo("local proxy rule file not exists; trying to create an empty one")
            vertx.fileSystem().createFile(LOCAL_RULE_FILENAME) { ar ->
                if (ar.failed()) logError("failed to create an empty local proxy rule file")
            }
            promise.complete()
        }
    }

    private fun loadRemoteRulesFromLocalFile() = Future.future<Unit> { promise ->
        vertx.fileSystem().exists(REMOTE_RULE_FILENAME) {
            if (it.failed()) {
                logError("failed to check remote proxy rule file", it.cause())
                promise.fail(it.cause())
                return@exists
            }

            if (it.result()) {
                loadRulesFromLocalFile(remoteHostRuleSet, REMOTE_RULE_FILENAME).setHandler { ar ->
                    promise.handle(ar)
                }
                return@exists
            }

            updateRemoteRulesFromRemoteSource()
            promise.complete()
        }
    }

    private fun updateRemoteRulesFromRemoteSource() = Future.future<Unit> { promise ->
        val remoteProxyRuleUrls = config.remoteProxyRuleUrls
        if (remoteProxyRuleUrls.isEmpty()) {
            logWarn("cannot update remote proxy rules; remote proxy rule urls not configured")
            promise.fail("remote proxy rule urls not configured")
            return@future
        }

        val url = remoteProxyRuleUrls.random()
        logInfo("updating remote proxy rules from '$url'")

        val httpClient = vertx.createHttpClient()
        @Suppress("DEPRECATION")
        httpClient.getAbs(url).exceptionHandler {
            logError("write stream exception caught when downloading remote proxy rules from '$url'", it)
            promise.fail(it)
            httpClient.close()
        }.handler { response ->
            val statusCode = response.statusCode()
            if (statusCode != HttpResponseStatus.OK.code()) {
                logWarn("$statusCode received when downloading remote proxy rules from '$url'")
                promise.fail("$statusCode received from '$url'")
                httpClient.close()
                return@handler
            }
            response.exceptionHandler {
                logError("read stream exception caught when downloading remote proxy rules from '$url'", it)
                promise.fail(it)
                httpClient.close()
            }.bodyHandler { buffer ->
                val bytes = Base64.getMimeDecoder().decode(buffer.bytes)
                vertx.fileSystem().writeFile(REMOTE_RULE_FILENAME, Buffer.buffer(bytes)) {
                    if (it.failed()) logError("failed to save remote proxy rules into local file", it.cause())
                }
                kotlin.runCatching {
                    bytes.inputStream().bufferedReader().readLines()
                }.onSuccess { lines ->
                    remoteHostRuleSet.load(lines)
                    logInfo("remote proxy rules updated from '$url")
                    promise.complete()
                }.onFailure { throwable ->
                    logError("failed to read lines from the response of '$url'", throwable)
                    promise.fail(throwable)
                }
                httpClient.close()
            }
        }.end()
    }

    private fun handleGetProxyRuleInfoRequest(message: Message<Unit>) {
        message.reply(
            ProxyRuleInfo(
                mode,
                localHostRuleSet.blackRuleNumber, localHostRuleSet.whiteRuleNumber,
                remoteHostRuleSet.blackRuleNumber, remoteHostRuleSet.whiteRuleNumber
            )
        )
    }

    private fun handleSetProxyModeRequest(message: Message<ProxyMode>) {
        val targetMode = message.body()
        if (mode != targetMode) {
            logInfo("proxy mode changed to ${targetMode.name}")
            mode = targetMode
        }
        message.reply(null)
    }

    private fun handleTestProxyRuleRequest(message: Message<TestProxyRuleArg>) {
        val arg = message.body()
        val useRelayHandler = if (arg.ignoreProxyMode) {
            testHost(arg.host)
        } else {
            when (mode) {
                ProxyMode.DIRECT -> false
                ProxyMode.RELAY -> true
                ProxyMode.RULE -> testHost(arg.host)
            }
        }
        message.reply(useRelayHandler)
    }

    private fun testHost(host: String): Boolean {
        val localResult = localHostRuleSet.test(host)
        if (localResult == ProxyRuleTestResult.BLACK) return true
        if (localResult == ProxyRuleTestResult.WHITE) return false
        return remoteHostRuleSet.test(host) == ProxyRuleTestResult.BLACK
    }

    private fun handleUpdateLocalRuleRequest(message: Message<Unit>) {
        loadLocalRulesFromLocalFile().setHandler {
            if (it.failed()) {
                message.fail(-1, it.cause().message)
                return@setHandler
            }
            message.reply(ProxyRuleNumberInfo(localHostRuleSet.blackRuleNumber, localHostRuleSet.whiteRuleNumber))
        }
    }

    private fun handleUpdateRemoteRuleRequest(message: Message<Unit>) {
        updateRemoteRulesFromRemoteSource().setHandler {
            if (it.failed()) {
                message.fail(-1, it.cause().message)
                return@setHandler
            }
            message.reply(ProxyRuleNumberInfo(remoteHostRuleSet.blackRuleNumber, remoteHostRuleSet.whiteRuleNumber))
        }
    }
}

@Suppress("unused") // used by object mapper
class ProxyRuleInfo(
    val mode: ProxyMode,
    val localBlackRuleNumber: Int,
    val localWhiteRuleNumber: Int,
    val remoteBlackRuleNumber: Int,
    val remoteWhiteRuleNumber: Int
)

class TestProxyRuleArg(
    val host: String,
    val ignoreProxyMode: Boolean = false
)

@Suppress("unused") // used by object mapper
class ProxyRuleNumberInfo(
    val blackRuleNumber: Int,
    val whiteRuleNumber: Int
)

private enum class ProxyRuleTestResult { BLACK, WHITE, UNKNOWN }

private class HostRuleSet {
    private val blackHosts = hashSetOf<String>()
    private val whiteHosts = hashSetOf<String>()

    val blackRuleNumber
        get() = blackHosts.size
    val whiteRuleNumber
        get() = whiteHosts.size

    fun load(lines: List<String>) {
        blackHosts.clear()
        whiteHosts.clear()

        for (rawLine in lines) {
            var line = rawLine.trim()

            // ignore comments and blank lines
            if (line.startsWith('[')) continue
            if (line.startsWith('!')) continue
            if (line.isEmpty()) continue

            // determine rule type
            val hosts = if (line.startsWith("@@")) {
                line = line.substring(2)
                whiteHosts
            } else {
                blackHosts
            }

            // ignore regex rules
            if (line.startsWith('/')) continue

            // is it a domain rule?
            if (line.startsWith("||")) {
                val host = line.substring(2)
                hosts.add(host)
                continue
            }

            // is it a start-with-rule or a contain-rule?
            if (line.startsWith('|')) {
                val index = line.indexOf("//")
                if (index < 0) continue // not a valid start-with-rule
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
            if (line.contains("*")) continue

            // if it contains path part, ignore it
            val indexOfPathRoot = line.indexOf("/")
            if (indexOfPathRoot >= 0) {
                if (indexOfPathRoot != line.length - 1) continue // contains path part
                line = line.substring(0, indexOfPathRoot)
            }

            // if it contains no "." (like 'localhost'), ignore it
            if (!line.contains(".")) continue

            hosts.add(line)
        }
    }

    fun test(host: String): ProxyRuleTestResult {
        val hostParts = host.split(".")

        // ignore host without dot like 'localhost'
        if (hostParts.size <= 1) return ProxyRuleTestResult.UNKNOWN

        if (test(hostParts, whiteHosts)) return ProxyRuleTestResult.WHITE
        if (test(hostParts, blackHosts)) return ProxyRuleTestResult.BLACK

        return ProxyRuleTestResult.UNKNOWN
    }

    private fun test(hostParts: List<String>, hosts: Set<String>): Boolean {
        var host = hostParts.last()
        for (i in hostParts.lastIndex - 1 downTo 0) {
            host = hostParts[i] + "." + host
            if (hosts.contains(host)) {
                return true
            }
        }
        return false
    }
}
