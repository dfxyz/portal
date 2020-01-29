package dfxyz.portal

import dfxyz.portal.extensions.httpServerRequest.toLogString
import io.vertx.core.http.HttpServerRequest
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

private lateinit var normalLogger: Logger
private lateinit var accessLogger: Logger

fun initLogger() {
    System.setProperty("log4j.configurationFile", "portal.log4j2.xml")
    normalLogger = LogManager.getLogger("dfxyz.portal:normal")
    accessLogger = LogManager.getLogger("dfxyz.portal:access")
}

fun logInfo(message: Any) = normalLogger.info(message)

fun logWarn(message: Any, throwable: Throwable? = null) = if (throwable == null) {
    normalLogger.warn(message)
} else {
    normalLogger.warn(message, throwable)
}

fun logError(message: Any, throwable: Throwable? = null) = if (throwable == null) {
    normalLogger.error(message)
} else {
    normalLogger.error(message, throwable)
}

fun logFatal(message: Any, throwable: Throwable? = null) = if (throwable == null) {
    normalLogger.fatal(message)
} else {
    normalLogger.fatal(message, throwable)
}

fun logDirectAccess(request: HttpServerRequest) = accessLogger.info("DIRECT - ${request.toLogString()}")
fun logRelayedAccess(request: HttpServerRequest) = accessLogger.info("RELAYED - ${request.toLogString()}")
fun logAcceptedAccess(request: HttpServerRequest) = accessLogger.info("ACCEPTED - ${request.toLogString()}")
fun logDeniedAccess(request: HttpServerRequest) = accessLogger.info("DENIED - ${request.toLogString()}")
fun logFailedAccess(request: HttpServerRequest) = accessLogger.info("FAILED - ${request.toLogString()}")
