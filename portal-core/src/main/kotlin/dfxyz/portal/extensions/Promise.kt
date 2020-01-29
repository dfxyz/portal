@file:Suppress("PackageDirectoryMismatch")

package dfxyz.portal.extensions.promise

import io.vertx.core.AsyncResult
import io.vertx.core.Promise

fun <T> Promise<T>.handle(ar: AsyncResult<*>, succeededValue: T? = null) {
    if (ar.succeeded()) {
        this.complete(succeededValue)
    } else {
        this.fail(ar.cause())
    }
}