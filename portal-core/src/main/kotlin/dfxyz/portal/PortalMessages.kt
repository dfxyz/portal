package dfxyz.portal

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.EventBus
import io.vertx.core.eventbus.MessageCodec
import io.vertx.core.json.Json

/** [Unit] -> [Nothing] */
const val MSG_ADDR_REDEPLOY = "dfxyz.portal#redeploy"

/** [String] -> [StaticWebResource] */
const val MSG_ADDR_GET_STATIC_WEB_RESOURCE = "dfxyz.portal#getStaticWebResource"

/** [Unit] -> [Nothing] */
const val MSG_ADDR_RELOAD_STATIC_WEB_RESOURCE = "dfxyz.portal#reloadStaticWebResource"

/** [Unit] -> [ProxyRuleInfo] */
const val MSG_ADDR_GET_PROXY_RULE_INFO = "dfxyz.portal#getProxyRuleInfo"

/** [ProxyMode] -> [Unit] */
const val MSG_ADDR_SET_PROXY_MODE = "dfxyz.portal#setProxyMode"

/** [TestProxyRuleArg] -> [Boolean] */
const val MSG_ADDR_TEST_PROXY_RULE = "dfxyz.portal#testProxyRule"

/** [Unit] -> [ProxyRuleNumberInfo] */
const val MSG_ADDR_UPDATE_LOCAL_PROXY_RULES = "dfxyz.portal#updateLocalProxyRules"

/** [Unit] -> [ProxyRuleNumberInfo] */
const val MSG_ADDR_UPDATE_REMOTE_PROXY_RULES = "dfxyz.portal#updateRemoteProxyRules"

fun registerPortalMessageCodecs(vertx: Vertx) {
    listOf(
        StaticWebResource::class.java,
        ProxyRuleInfo::class.java,
        ProxyMode::class.java,
        TestProxyRuleArg::class.java,
        ProxyRuleNumberInfo::class.java
    ).forEach {
        PortalMessageCodec(vertx.eventBus(), it)
    }
}

/** Use databind object mapper to encode/decode message, or pass the original object locally */
private class PortalMessageCodec<T>(eventBus: EventBus, private val cls: Class<T>) : MessageCodec<T, T> {
    init {
        eventBus.registerDefaultCodec(cls, this)
    }

    override fun name(): String {
        return "portal_message(${cls.name})"
    }

    override fun systemCodecID(): Byte {
        return (-1).toByte()
    }

    override fun transform(s: T): T {
        return s
    }

    override fun encodeToWire(buffer: Buffer, s: T) {
        val b = Json.encodeToBuffer(s)
        buffer.appendInt(b.length())
        buffer.appendBuffer(b)
    }

    override fun decodeFromWire(pos: Int, buffer: Buffer): T {
        val length = buffer.getInt(pos)
        val newPos = pos + 4
        return Json.decodeValue(buffer.slice(newPos, newPos + length), cls)
    }
}

