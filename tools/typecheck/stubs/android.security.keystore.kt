@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package android.security.keystore

class KeyGenParameterSpec : java.security.spec.AlgorithmParameterSpec {
    class Builder(keystoreAlias: String, purposes: Int) {
        fun setBlockModes(vararg blockModes: String): Builder = this
        fun setEncryptionPaddings(vararg paddings: String): Builder = this
        fun setKeySize(keySize: Int): Builder = this
        fun build(): KeyGenParameterSpec = TODO()
    }
}
object KeyProperties {
    const val KEY_ALGORITHM_AES = "AES"
    const val PURPOSE_ENCRYPT = 1
    const val PURPOSE_DECRYPT = 2
    const val BLOCK_MODE_GCM = "GCM"
    const val ENCRYPTION_PADDING_NONE = "NoPadding"
}
