@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.web.bind.annotation

@Target(AnnotationTarget.CLASS) annotation class RestController
@Target(AnnotationTarget.CLASS) annotation class RestControllerAdvice
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class RequestMapping(vararg val value: String = [])
@Target(AnnotationTarget.FUNCTION) annotation class GetMapping(vararg val value: String = [])
@Target(AnnotationTarget.FUNCTION) annotation class PostMapping(vararg val value: String = [])
@Target(AnnotationTarget.VALUE_PARAMETER) annotation class RequestBody(val required: Boolean = true)
@Target(AnnotationTarget.VALUE_PARAMETER) annotation class RequestParam(val value: String = "", val name: String = "", val required: Boolean = true, val defaultValue: String = "\n\t\t\n\t\t\n\uE000\uE001\uE002\n\t\t\t\t\n")
@Target(AnnotationTarget.VALUE_PARAMETER) annotation class RequestAttribute(val value: String = "", val name: String = "", val required: Boolean = true)
@Target(AnnotationTarget.VALUE_PARAMETER) annotation class RequestHeader(val value: String = "", val name: String = "", val required: Boolean = true)
@Target(AnnotationTarget.FUNCTION) annotation class ExceptionHandler(vararg val value: kotlin.reflect.KClass<out Throwable> = [])
