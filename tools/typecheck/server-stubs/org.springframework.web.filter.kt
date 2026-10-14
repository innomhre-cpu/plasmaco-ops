@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.web.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
abstract class OncePerRequestFilter {
    protected abstract fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain)
}
