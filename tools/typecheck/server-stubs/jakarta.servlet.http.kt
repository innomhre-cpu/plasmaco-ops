@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package jakarta.servlet.http

interface HttpServletRequest : jakarta.servlet.ServletRequest {
    val requestURI: String
    val contextPath: String
    val method: String
    val contentLengthLong: Long
    fun getHeader(name: String): String?
    fun setAttribute(name: String, o: Any?)
    fun getAttribute(name: String): Any?
}
interface HttpServletResponse : jakarta.servlet.ServletResponse {
    var status: Int
    var contentType: String?
    val writer: java.io.PrintWriter
    fun setHeader(name: String, value: String?)
}
