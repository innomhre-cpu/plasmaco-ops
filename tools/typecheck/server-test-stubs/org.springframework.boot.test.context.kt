@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.boot.test.context

@Target(AnnotationTarget.CLASS)
annotation class SpringBootTest(vararg val properties: String = [], val webEnvironment: WebEnvironment = WebEnvironment.MOCK) {
    enum class WebEnvironment { MOCK, RANDOM_PORT, DEFINED_PORT, NONE }
}
