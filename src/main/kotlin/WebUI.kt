package dfxyz.portal

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerRequest

private const val PATH_ROOT = "/"
private const val PATH_WEB_ROOT = "/web/"
private const val PATH_WEB_DIRECT_MODE = "/web/directMode"
private const val PATH_WEB_RELAY_MODE = "/web/relayMode"
private const val PATH_WEB_RULE_MODE = "/web/ruleMode"
private const val PATH_WEB_UPDATE_LOCAL_RULES = "/web/updateLocalRules"
private const val PATH_WEB_UPDATE_REMOTE_RULES = "/web/updateRemoteRules"
private const val PATH_CMD_STATUS = "/cmd/status"
private const val PATH_CMD_DIRECT_MODE = "/cmd/directMode"
private const val PATH_CMD_RELAY_MODE = "/cmd/relayMode"
private const val PATH_CMD_RULE_MODE = "/cmd/ruleMode"
private const val PATH_CMD_UPDATE_LOCAL_RULES = "/cmd/updateLocalRules"
private const val PATH_CMD_UPDATE_REMOTE_RULES = "/cmd/updateRemoteRules"
private const val PATH_CMD_RELOAD = "/cmd/reload"

fun handleGetRequest(request: HttpServerRequest) {
    if (!directProxyEnabled || request.remoteAddress().host() != "127.0.0.1") {
        request.response().setStatus(HttpResponseStatus.NOT_FOUND).endAndClose()
        deniedAccess(request)
        return
    }

    when (request.path()) {
        PATH_ROOT -> {
            request.response()
                .setStatus(HttpResponseStatus.MOVED_PERMANENTLY)
                .putHeader("location", PATH_WEB_ROOT)
                .end()
        }
        PATH_WEB_ROOT -> showServerStatus(request)
        PATH_WEB_DIRECT_MODE -> changeProxyMode(request, ProxyMode.DIRECT)
        PATH_WEB_RELAY_MODE -> changeProxyMode(request, ProxyMode.RELAY)
        PATH_WEB_RULE_MODE -> changeProxyMode(request, ProxyMode.RULE)
        PATH_WEB_UPDATE_LOCAL_RULES -> updateLocalRules(request)
        PATH_WEB_UPDATE_REMOTE_RULES -> updateRemoteRules(request)
        PATH_CMD_STATUS -> showServerStatus(request, false)
        PATH_CMD_DIRECT_MODE -> changeProxyMode(request, ProxyMode.DIRECT, false)
        PATH_CMD_RELAY_MODE -> changeProxyMode(request, ProxyMode.RELAY, false)
        PATH_CMD_RULE_MODE -> changeProxyMode(request, ProxyMode.RULE, false)
        PATH_CMD_UPDATE_LOCAL_RULES -> updateLocalRules(request, false)
        PATH_CMD_UPDATE_REMOTE_RULES -> updateRemoteRules(request, false)
        PATH_CMD_RELOAD -> reloadPortal(request)
        else -> request.response().setStatus(HttpResponseStatus.NOT_FOUND).endAndClose()
    }
}

private fun showServerStatus(request: HttpServerRequest, returnHtml: Boolean = true) {
    if (returnHtml) {
        request.response().end(Buffer.buffer().apply {
            appendString("<h1>Portal</h1>")
            appendString("<hr/>")

            appendString("<h2>Proxy Mode</h2>")
            appendString("<ul>")
            appendString("<li>")
            if (proxyMode == ProxyMode.DIRECT) {
                appendString("DIRECT")
            } else {
                appendString("<a href=\"$PATH_WEB_DIRECT_MODE\">DIRECT</a>")
            }
            appendString("</li>")
            appendString("<li>")
            if (proxyMode == ProxyMode.RELAY) {
                appendString("RELAY")
            } else {
                appendString("<a href=\"$PATH_WEB_RELAY_MODE\">RELAY</a>")
            }
            appendString("</li>")
            appendString("<li>")
            if (proxyMode == ProxyMode.RULE) {
                appendString("RULE")
            } else {
                appendString("<a href=\"$PATH_WEB_RULE_MODE\">RULE</a>")
            }
            appendString("</li>")
            appendString("</ul>")

            appendString("<h2>Proxy Rules</h2>")
            appendString("Local Rules (<a href=\"$PATH_WEB_UPDATE_LOCAL_RULES\">Update</a>)")
            appendString("<ul>")
            appendString("<li>Black: ${getLocalRuleBlackListSize()}")
            appendString("<li>White: ${getLocalRuleWhiteListSize()}")
            appendString("</ul>")
            appendString("Remote Rules (<a href=\"$PATH_WEB_UPDATE_REMOTE_RULES\">Update</a>)")
            appendString("<ul>")
            appendString("<li>Black: ${getRemoteRuleBlackListSize()}")
            appendString("<li>White: ${getRemoteRuleWhiteListSize()}")
            appendString("</ul>")

            appendString("<h2>Server Control</h2>")
            appendString("<ul>")
            appendString("<li><a href=\"$PATH_CMD_RELOAD\">Reload</a></li>")
            appendString("</ul>")
        })
        return
    }

    request.response().end(Buffer.buffer().apply {
        appendString("proxyMode=${proxyMode.name}\n")
        appendString("localBlackHosts=${getLocalRuleBlackListSize()}\n")
        appendString("localWhiteHosts=${getLocalRuleWhiteListSize()}\n")
        appendString("remoteBlackHosts=${getRemoteRuleBlackListSize()}\n")
        appendString("remoteWhiteHosts=${getRemoteRuleWhiteListSize()}\n")
    })
}

private fun changeProxyMode(request: HttpServerRequest, mode: ProxyMode, returnHtml: Boolean = true) {
    changeProxyMode(mode)
    if (returnHtml) {
        request.response().setStatus(HttpResponseStatus.TEMPORARY_REDIRECT).putHeader("location", PATH_WEB_ROOT).end()
        return
    }
    request.response().endAndClose("proxyMode=${proxyMode.name}\n")
}

private fun showMessageAndRedirect(request: HttpServerRequest, message: String, redirectUri: String = PATH_WEB_ROOT) {
    request.response().end(Buffer.buffer().apply {
        appendString("<meta http-equiv=\"refresh\" content=\"3; url=$redirectUri\"/>")
        appendString("<h1>Portal</h1>")
        appendString("<hr/>")
        appendString(message)
    })
}

private fun updateLocalRules(request: HttpServerRequest, returnHtml: Boolean = true) {
    updateLocalRules(vertx) { success: Boolean ->
        if (returnHtml) {
            val message = if (success) {
                "Local rules updated. (Black: ${getLocalRuleBlackListSize()}; White: ${getLocalRuleWhiteListSize()})"
            } else {
                "Failed to update local rules."
            }
            showMessageAndRedirect(request, message)
        } else {
            request.response().endAndClose(if (success) "OK\n" else "FAILED\n")
        }
    }
}

private fun updateRemoteRules(request: HttpServerRequest, returnHtml: Boolean = true) {
    updateRemoteRules(vertx) { success: Boolean ->
        if (returnHtml) {
            val message = if (success) {
                "Remote rules updated. (Black: ${getRemoteRuleBlackListSize()}; White: ${getRemoteRuleWhiteListSize()})"
            } else {
                "Failed to update remote rules."
            }
            showMessageAndRedirect(request, message)
        } else {
            request.response().endAndClose(if (success) "OK\n" else "FAILED\n")
        }
    }
}

private fun reloadPortal(request: HttpServerRequest) {
    request.response().endAndClose("OK\n")
    init()
}
