@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package dagger

annotation class Module(val includes: Array<kotlin.reflect.KClass<*>> = [])
annotation class Provides
annotation class Binds
