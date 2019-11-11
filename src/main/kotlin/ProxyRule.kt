package dfxyz.portal.proxyrule

import dfxyz.portal.getStringList
import dfxyz.portal.httpClient
import dfxyz.portal.logger.error
import dfxyz.portal.logger.info
import dfxyz.portal.updateRemoteRuleUpdateTime
import dfxyz.portal.vertx
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.buffer.Buffer
import java.io.File
import java.util.*

private const val LOCAL_RULES_FILENAME = "portal.rules.local"
private const val REMOTE_RULES_FILENAME = "portal.rules.remote"

private const val PK_REMOTE_RULE_B64_ENCODED = "portal.directProxy.remoteRule.b64Encoded"
private const val PK_REMOTE_RULE_URL = "portal.directProxy.remoteRule.url"

private val localRules = HostRules(LOCAL_RULES_FILENAME)
private val remoteRules = HostRules(REMOTE_RULES_FILENAME)

private var remoteRuleB64Encoded = true
private val remoteRuleUrls = mutableListOf<String>()

private enum class HostRuleTestResult { WHITE, BLACK, UNKNOWN }

private class HostRules(val filename: String) {
    val whiteHosts = HashSet<String>()
    val blackHosts = HashSet<String>()

    fun loadFromFile() {
        try {
            load(File(filename).readLines())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun load(lines: List<String>) {
        whiteHosts.clear()
        blackHosts.clear()

        for (rawLine in lines) {
            var line = rawLine.trim()

            // ignore comments and blank lines
            if (line.startsWith('[')) continue
            if (line.startsWith('!')) continue
            if (line.isEmpty()) continue

            // determine rule type
            val hosts: MutableSet<String>
            if (line.startsWith("@@")) {
                hosts = whiteHosts
                line = rawLine.substring(2)
            } else {
                hosts = blackHosts
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

    fun testHost(host: String): HostRuleTestResult {
        val hostParts = host.split(".")

        // ignore host without dot like 'localhost'
        if (hostParts.size <= 1) return HostRuleTestResult.UNKNOWN

        if (test(hostParts, whiteHosts)) return HostRuleTestResult.WHITE
        if (test(hostParts, blackHosts)) return HostRuleTestResult.BLACK

        return HostRuleTestResult.UNKNOWN
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

fun init(properties: Properties) {
    localRules.loadFromFile()
    remoteRules.loadFromFile()
    properties.getProperty(PK_REMOTE_RULE_B64_ENCODED)?.toBoolean()?.also {
        remoteRuleB64Encoded = it
    }
    properties.getStringList(PK_REMOTE_RULE_URL).also {
        remoteRuleUrls.clear()
        remoteRuleUrls.addAll(it)
    }
}

fun hostBlocked(host: String): Boolean {
    localRules.testHost(host).also { if (it == HostRuleTestResult.BLACK) return true }
    return remoteRules.testHost(host) == HostRuleTestResult.BLACK
}

fun getLocalRuleWhiteListSize() = localRules.whiteHosts.size
fun getLocalRuleBlackListSize() = localRules.blackHosts.size
fun getRemoteRuleWhiteListSize() = remoteRules.whiteHosts.size
fun getRemoteRuleBlackListSize() = remoteRules.blackHosts.size

fun updateLocalRules() = Future.future<Unit> { promise ->
    val path = localRules.filename
    vertx.fileSystem().exists(path) {
        if (it.failed()) {
            error("failed to check local rules", it.cause())
            promise.fail(it.cause())
            return@exists
        }

        if (it.result()) {
            vertx.fileSystem().readFile(path) { readResult ->
                if (readResult.failed()) {
                    error("failed to read local rules", readResult.cause())
                    promise.fail(readResult.cause())
                    return@readFile
                }
                localRules.load(readResult.result().bytes.inputStream().bufferedReader().readLines())
                info("local rules updated")
                promise.complete()
            }
            return@exists
        }

        vertx.fileSystem().createFile(path) { createResult ->
            if (createResult.failed()) {
                error("failed to create local rules", createResult.cause())
                promise.fail(createResult.cause())
                return@createFile
            }
            info("local rules created")
            promise.complete()
        }
    }
}

fun updateRemoteRules() = Future.future<Boolean> { promise ->
    if (remoteRuleUrls.isEmpty()) {
        error("property '$PK_REMOTE_RULE_URL' not set")
        promise.complete(false)
        return@future
    }

    val url = remoteRuleUrls.random()

    @Suppress("DEPRECATION")
    httpClient.getAbs(url).exceptionHandler {
        error("exception caught when updating remote rules", it)
        promise.fail(it)
    }.handler { response ->
        val statusCode = response.statusCode()
        if (statusCode != HttpResponseStatus.OK.code()) {
            error("received $statusCode when updating remote rules: $url")
            promise.complete(false)
            return@handler
        }
        response.exceptionHandler {
            error("exception caught when receiving remote rules", it)
            promise.fail(it)
        }.bodyHandler { buffer ->
            val rawBytes = if (remoteRuleB64Encoded) {
                Base64.getMimeDecoder().decode(buffer.bytes)
            } else {
                buffer.bytes
            }

            vertx.fileSystem().writeFile(remoteRules.filename, Buffer.buffer(rawBytes)) {
                if (it.failed()) {
                    error("failed to save remote rules")
                } else {
                    updateRemoteRuleUpdateTime()
                    info("remote rules saved")
                }
            }

            remoteRules.load(rawBytes.inputStream().bufferedReader().readLines())
            info("remote rules updated")
            promise.complete(true)
        }
    }.end()
}
