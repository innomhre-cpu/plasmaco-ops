@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package retrofit2.http

@Target(AnnotationTarget.FUNCTION) annotation class GET(val value: String = "")
@Target(AnnotationTarget.FUNCTION) annotation class POST(val value: String = "")
@Target(AnnotationTarget.VALUE_PARAMETER) annotation class Body
@Target(AnnotationTarget.VALUE_PARAMETER) annotation class Query(val value: String, val encoded: Boolean = false)
@Target(AnnotationTarget.VALUE_PARAMETER) annotation class Path(val value: String, val encoded: Boolean = false)
