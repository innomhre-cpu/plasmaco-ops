@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.boot.test.web.client

import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
class TestRestTemplate {
    fun <T> exchange(url: String, method: HttpMethod, requestEntity: HttpEntity<*>?, responseType: Class<T>, vararg uriVariables: Any?): ResponseEntity<T> = TODO()
}
