@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.beans.factory.annotation

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.VALUE_PARAMETER)
annotation class Autowired(val required: Boolean = true)
