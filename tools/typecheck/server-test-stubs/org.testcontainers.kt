@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.testcontainers

class DockerClientFactory private constructor() {
    val isDockerAvailable: Boolean get() = TODO()
    companion object { @JvmStatic fun instance(): DockerClientFactory = TODO() }
}
