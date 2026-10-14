@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package retrofit2.converter.kotlinx.serialization

import kotlinx.serialization.StringFormat
import okhttp3.MediaType
import retrofit2.Converter
fun StringFormat.asConverterFactory(contentType: MediaType): Converter.Factory = TODO()
