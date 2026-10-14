@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.boot.context.properties

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class ConfigurationProperties(val prefix: String = "", val value: String = "")
@Target(AnnotationTarget.CLASS) annotation class EnableConfigurationProperties(vararg val value: kotlin.reflect.KClass<*>)
