package dfxyz.portal

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import java.util.*

private enum class HostRuleTestResult { WHITE, BLACK, UNKNOWN }

private class HostRules {
    val whiteHosts = HashSet<String>()
    val blackHosts = HashSet<String>()

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

    fun test(host: String): HostRuleTestResult {
        val hostParts = host.split(".")

        // ignore host without dot like 'localhost'
        if (hostParts.size <= 1) {
            return HostRuleTestResult.UNKNOWN
        }

        if (test(hostParts, whiteHosts)) {
            return HostRuleTestResult.WHITE
        }
        if (test(hostParts, blackHosts)) {
            return HostRuleTestResult.BLACK
        }

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

private const val LOCAL_RULES_FILENAME = "portal.rules.local"
private const val REMOTE_RULES_FILENAME = "portal.rules.remote"

private const val PK_REMOTE_RULE_B64_ENCODED = "portal.directProxy.remoteRule.b64Encoded"
private const val PK_REMOTE_RULE_URL = "portal.directProxy.remoteRule.url"

private val localRules = HostRules()
private val remoteRules = HostRules()

private var remoteRuleB64Encoded = false
private val remoteRuleUrl = mutableListOf<String>()

fun initProxyRules(properties: Properties) {
    loadRules(LOCAL_RULES_FILENAME, localRules)
    loadRules(REMOTE_RULES_FILENAME, remoteRules)
    properties.getProperty(PK_REMOTE_RULE_B64_ENCODED)?.toBoolean()?.also {
        remoteRuleB64Encoded = it
    }
    remoteRuleUrl.apply {
        clear()
        addAll(properties.getStringList(PK_REMOTE_RULE_URL))
    }
}

private fun loadRules(filename: String, rules: HostRules) {
    try {
        rules.load(getFile(filename).readLines())
    } catch (e: Exception) {
        // ignore
    }
}

fun hostBlocked(host: String): Boolean {
    localRules.test(host).also { if (it == HostRuleTestResult.BLACK) return true }
    return remoteRules.test(host) == HostRuleTestResult.BLACK
}

fun getLocalRuleWhiteListSize() = localRules.whiteHosts.size
fun getLocalRuleBlackListSize() = localRules.blackHosts.size
fun getRemoteRuleWhiteListSize() = remoteRules.whiteHosts.size
fun getRemoteRuleBlackListSize() = remoteRules.blackHosts.size

fun updateLocalRules(vertx: Vertx, callback: (Boolean) -> Unit) {
    val path = getFile(LOCAL_RULES_FILENAME).absolutePath
    vertx.fileSystem().exists(path) {
        if (it.failed()) {
            error("failed to check local rules")
            callback(false)
            return@exists
        }

        if (it.result()) {
            vertx.fileSystem().readFile(path) { readResult ->
                if (readResult.failed()) {
                    error("failed to read local rules")
                    callback(false)
                } else {
                    localRules.load(readResult.result().bytes.inputStream().bufferedReader().readLines())
                    info("local rules updated")
                    callback(true)
                }
            }
        } else {
            vertx.fileSystem().createFile(path) { createResult ->
                if (createResult.failed()) {
                    error("failed to create local rules")
                    callback(false)
                } else {
                    info("local rules created")
                    callback(true)
                }
            }
        }
    }
}

fun updateRemoteRules(vertx: Vertx, callback: ((Boolean) -> Unit)?) {
    if (remoteRuleUrl.isEmpty()) {
        error("property '$PK_REMOTE_RULE_URL' not set")
        callback?.invoke(false)
        return
    }

    val url = remoteRuleUrl.random()

    @Suppress("DEPRECATION")
    portalHttpClient.getAbs(url)
        .exceptionHandler {
            error("exception caught when updating remote rules", it)
            callback?.invoke(false)
        }.handler { response ->
            val statusCode = response.statusCode()
            if (statusCode != HttpResponseStatus.OK.code()) {
                error("received $statusCode when updating remote rules: $url")
                callback?.invoke(false)
                return@handler
            }
            response
                .exceptionHandler {
                    error("exception caught when updating remote rules", it)
                    callback?.invoke(false)
                }
                .bodyHandler { buffer ->
                    val rawBytes = if (remoteRuleB64Encoded) {
                        Base64.getMimeDecoder().decode(buffer.bytes)
                    } else {
                        buffer.bytes
                    }

                    vertx.fileSystem().writeFile(getFile(REMOTE_RULES_FILENAME).absolutePath, Buffer.buffer(rawBytes)) {
                        if (it.failed()) {
                            error("failed to save remote rules")
                        } else {
                            proxyStatus.setProperty(PK_REMOTE_RULE_UPDATE_TIME, System.currentTimeMillis().toString())
                            saveProxyStatus()
                            info("remote rules saved")
                        }
                    }

                    remoteRules.load(rawBytes.inputStream().bufferedReader().readLines())
                    info("remote rules updated")
                    callback?.invoke(true)
                }
        }.end()

}
