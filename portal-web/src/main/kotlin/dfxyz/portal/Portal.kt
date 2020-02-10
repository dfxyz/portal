package dfxyz.portal

import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSpanElement
import org.w3c.xhr.JSON
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import kotlin.browser.document
import kotlin.browser.window
import kotlin.js.Json

private const val STATUS_OK = 200.toShort()

private val urlRoot = document.URL.trimEnd('/')

private enum class ProxyMode { DIRECT, RELAY, RULE }

private lateinit var directModeAnchor: ProxyModeAnchor
private lateinit var relayModeAnchor: ProxyModeAnchor
private lateinit var ruleModeAnchor: ProxyModeAnchor

private class ProxyModeAnchor(
    private val proxyMode: ProxyMode,
    private val element: HTMLAnchorElement
) {
    fun enable() {
        element.href = "javascript:;"
        element.onclick = { requestSetProxyMode(proxyMode) }
    }

    fun disable() {
        element.removeAttribute("href")
        element.onclick = null
    }
}

private lateinit var updateLocalRuleAnchor: HTMLAnchorElement
private lateinit var localBlackRuleNumberSpan: HTMLSpanElement
private lateinit var localWhiteRuleNumberSpan: HTMLSpanElement

private lateinit var updateRemoteRuleAnchor: HTMLAnchorElement
private lateinit var remoteBlackRuleNumberSpan: HTMLSpanElement
private lateinit var remoteWhiteRuleNumberSpan: HTMLSpanElement

private lateinit var testHostText: HTMLInputElement
private lateinit var testHostSubmit: HTMLInputElement
private lateinit var testResultDivision: HTMLDivElement
private lateinit var testedHostSpan: HTMLSpanElement
private lateinit var testedResultSpan: HTMLSpanElement

private lateinit var reloadConfigurationAnchor: HTMLAnchorElement

fun main() {
    directModeAnchor = ProxyModeAnchor(
        ProxyMode.DIRECT,
        document.getElementById("directModeAnchor") as HTMLAnchorElement
    )
    relayModeAnchor = ProxyModeAnchor(
        ProxyMode.RELAY,
        document.getElementById("relayModeAnchor") as HTMLAnchorElement
    )
    ruleModeAnchor = ProxyModeAnchor(
        ProxyMode.RULE,
        document.getElementById("ruleModeAnchor") as HTMLAnchorElement
    )

    localBlackRuleNumberSpan = document.getElementById("localBlackRuleNumberSpan") as HTMLSpanElement
    localWhiteRuleNumberSpan = document.getElementById("localWhiteRuleNumberSpan") as HTMLSpanElement
    updateLocalRuleAnchor = document.getElementById("updateLocalRuleAnchor") as HTMLAnchorElement
    updateLocalRuleAnchor.onclick = { requestUpdateLocalRules() }

    remoteBlackRuleNumberSpan = document.getElementById("remoteBlackRuleNumberSpan") as HTMLSpanElement
    remoteWhiteRuleNumberSpan = document.getElementById("remoteWhiteRuleNumberSpan") as HTMLSpanElement
    updateRemoteRuleAnchor = document.getElementById("updateRemoteRuleAnchor") as HTMLAnchorElement
    updateRemoteRuleAnchor.onclick = { requestUpdateRemoteRules() }

    testHostText = document.getElementById("testHostText") as HTMLInputElement
    testHostSubmit = document.getElementById("testHostSubmit") as HTMLInputElement
    testHostSubmit.onclick = { requestTestHost() }

    testResultDivision = document.getElementById("testResultDivision") as HTMLDivElement
    testedHostSpan = document.getElementById("testedHostSpan") as HTMLSpanElement
    testedResultSpan = document.getElementById("testedResultSpan") as HTMLSpanElement

    reloadConfigurationAnchor = document.getElementById("reloadConfigurationAnchor") as HTMLAnchorElement
    reloadConfigurationAnchor.onclick = { requestReloadConfigurations() }

    requestProxyRuleInfo()
}

private fun requestProxyRuleInfo() {
    val request = XMLHttpRequest()
    request.open("GET", "$urlRoot/api/proxyRuleInfo")
    request.responseType = XMLHttpRequestResponseType.JSON
    request.onload = { onProxyRuleInfoRequested(request) }
    request.send()
}

private fun onProxyRuleInfoRequested(request: XMLHttpRequest) {
    if (request.status == STATUS_OK) {
        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        val data = request.response as Json

        val proxyMode = ProxyMode.valueOf(data["mode"] as String)
        onProxyModeLoaded(proxyMode)

        val localBlackRuleNumber = data["localBlackRuleNumber"] as Int
        val localWhiteRuleNumber = data["localWhiteRuleNumber"] as Int
        onLocalRuleNumberInfoLoaded(localBlackRuleNumber, localWhiteRuleNumber)

        val remoteBlackRuleNumber = data["remoteBlackRuleNumber"] as Int
        val remoteWhiteRuleNumber = data["remoteWhiteRuleNumber"] as Int
        onRemoteRuleNumberInfoLoaded(remoteBlackRuleNumber, remoteWhiteRuleNumber)
    }
}

private fun requestSetProxyMode(proxyMode: ProxyMode) {
    val request = XMLHttpRequest()
    request.open("GET", "$urlRoot/api/proxyMode/${proxyMode.name.toLowerCase()}")
    request.onload = { onSetProxyModeDone(request, proxyMode) }
    request.send()
}

private fun onSetProxyModeDone(request: XMLHttpRequest, proxyMode: ProxyMode) {
    if (request.status == STATUS_OK) {
        onProxyModeLoaded(proxyMode)
    }
}

private fun onProxyModeLoaded(proxyMode: ProxyMode) {
    when (proxyMode) {
        ProxyMode.DIRECT -> {
            directModeAnchor.disable()
            relayModeAnchor.enable()
            ruleModeAnchor.enable()
        }
        ProxyMode.RELAY -> {
            directModeAnchor.enable()
            relayModeAnchor.disable()
            ruleModeAnchor.enable()
        }
        ProxyMode.RULE -> {
            directModeAnchor.enable()
            relayModeAnchor.enable()
            ruleModeAnchor.disable()
        }
    }
}

private fun requestUpdateLocalRules() {
    val request = XMLHttpRequest()
    request.open("GET", "$urlRoot/api/updateLocalProxyRules")
    request.responseType = XMLHttpRequestResponseType.JSON
    request.onload = { onUpdateLocalRuleResponded(request) }
    request.send()
}

private fun onUpdateLocalRuleResponded(request: XMLHttpRequest) {
    if (request.status != STATUS_OK) {
        window.alert("Failed to update local proxy rules!")
        return
    }

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    val data = request.response as Json
    val blackRuleNumber = data["blackRuleNumber"] as Int
    val whiteRuleNumber = data["whiteRuleNumber"] as Int
    onLocalRuleNumberInfoLoaded(blackRuleNumber, whiteRuleNumber)
    window.alert("Local proxy rules updated!")
}

private fun onLocalRuleNumberInfoLoaded(blackRuleNumber: Int, whiteRuleNumber: Int) {
    localBlackRuleNumberSpan.innerText = blackRuleNumber.toString()
    localWhiteRuleNumberSpan.innerText = whiteRuleNumber.toString()
}

private fun requestUpdateRemoteRules() {
    val request = XMLHttpRequest()
    request.open("GET", "$urlRoot/api/updateRemoteProxyRules")
    request.responseType = XMLHttpRequestResponseType.JSON
    request.onload = { onUpdateRemoteRuleResponded(request) }
    request.send()
}

private fun onUpdateRemoteRuleResponded(request: XMLHttpRequest) {
    if (request.status != STATUS_OK) {
        window.alert("Failed to update remote proxy rules!")
        return
    }

    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    val data = request.response as Json
    val blackRuleNumber = data["blackRuleNumber"] as Int
    val whiteRuleNumber = data["whiteRuleNumber"] as Int
    onRemoteRuleNumberInfoLoaded(blackRuleNumber, whiteRuleNumber)
    window.alert("Remote proxy rules updated!")
}

private fun onRemoteRuleNumberInfoLoaded(blackRuleNumber: Int, whiteRuleNumber: Int) {
    remoteBlackRuleNumberSpan.innerText = blackRuleNumber.toString()
    remoteWhiteRuleNumberSpan.innerText = whiteRuleNumber.toString()
}

private fun requestTestHost() {
    val host = testHostText.value
    if (host.isEmpty()) {
        window.alert("Please input the host name to test")
        return
    }
    val request = XMLHttpRequest()
    request.open("GET", "$urlRoot/api/testProxyRule?$host")
    request.onload = { onTestHostDone(request, host) }
    request.send()
}

private fun onTestHostDone(request: XMLHttpRequest, host: String) {
    if (request.status != STATUS_OK) {
        window.alert("Failed to test the host name!")
        return
    }
    testResultDivision.removeAttribute("style")
    testedHostSpan.innerText = host
    testedResultSpan.innerText = request.responseText
}

private fun requestReloadConfigurations() {
    val request = XMLHttpRequest()
    request.open("GET", "$urlRoot/api/reloadConfigurations")
    request.onload = { onReloadConfigurationDone(request) }
    request.send()
}

private fun onReloadConfigurationDone(request: XMLHttpRequest) {
    if (request.status == STATUS_OK) {
        window.setInterval("location.reload()", 2000)
    }
}
