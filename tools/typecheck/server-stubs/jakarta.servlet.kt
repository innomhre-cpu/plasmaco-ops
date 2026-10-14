@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package jakarta.servlet

interface ServletRequest
interface ServletResponse
interface FilterChain { fun doFilter(request: ServletRequest, response: ServletResponse) }
