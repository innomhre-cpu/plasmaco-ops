@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.slf4j

interface Logger {
    fun info(format: String, vararg arguments: Any?)
    fun warn(format: String, vararg arguments: Any?)
    fun error(format: String, vararg arguments: Any?)
}
object LoggerFactory {
    @JvmStatic fun getLogger(name: String): Logger = TODO()
    @JvmStatic fun getLogger(clazz: Class<*>): Logger = TODO()
}
