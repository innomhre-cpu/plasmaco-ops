@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package javax.inject

@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Inject
annotation class Singleton
annotation class Qualifier
