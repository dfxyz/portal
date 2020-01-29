@file:Suppress("PackageDirectoryMismatch")

package dfxyz.portal.extensions.string

import java.nio.charset.Charset
import java.util.*

fun String.toBase64EncodedString(): String {
    return Base64.getEncoder().encode(this.toByteArray()).toString(Charset.defaultCharset())
}
