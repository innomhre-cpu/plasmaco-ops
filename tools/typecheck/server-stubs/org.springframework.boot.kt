@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.boot

fun interface ApplicationRunner { fun run(args: ApplicationArguments) }
interface ApplicationArguments
inline fun <reified T : Any> runApplication(vararg args: String): Any = TODO()
