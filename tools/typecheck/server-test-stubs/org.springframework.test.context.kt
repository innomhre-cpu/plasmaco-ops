@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.test.context

@Target(AnnotationTarget.FUNCTION) annotation class DynamicPropertySource
interface DynamicPropertyRegistry { fun add(name: String, valueSupplier: java.util.function.Supplier<Any>) }
