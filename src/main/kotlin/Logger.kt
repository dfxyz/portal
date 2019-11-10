package dfxyz.portal.logger

import dfxyz.portal.toLogString
import io.vertx.core.http.HttpServerRequest
import org.apache.logging.log4j.LogManager

private const val NORMAL_LOGGER_NAME = "dfxyz.portal:normal"
private const val ACCESS_LOGGER_NAME = "dfxyz.portal:access"

private val normalLogger = LogManager.getLogger(NORMAL_LOGGER_NAME)
private val accessLogger = LogManager.getLogger(ACCESS_LOGGER_NAME)

fun info(message: Any) = normalLogger.info(message)
fun error(message: Any) = normalLogger.error(message)
fun error(message: Any, throwable: Throwable) = normalLogger.error(message, throwable)

fun directAccess(request: HttpServerRequest) = accessLogger.info("DIRECT - ${request.toLogString()}")
fun relayedAccess(request: HttpServerRequest) = accessLogger.info("RELAYED - ${request.toLogString()}")
fun acceptedAccess(request: HttpServerRequest) = accessLogger.info("ACCEPTED - ${request.toLogString()}")
fun deniedAccess(request: HttpServerRequest) = accessLogger.info("DENIED - ${request.toLogString()}")
fun failedAccess(request: HttpServerRequest) = accessLogger.info("FAILED - ${request.toLogString()}")
