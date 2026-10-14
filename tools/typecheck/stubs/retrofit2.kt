@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package retrofit2

class HttpException : RuntimeException() {
    fun code(): Int = TODO()
    fun response(): Response<*>? = TODO()
}
class Response<T> { fun errorBody(): okhttp3.ResponseBody? = TODO(); fun code(): Int = TODO() }
interface Converter { abstract class Factory }
class Retrofit {
    fun <T> create(service: Class<T>): T = TODO()
    class Builder {
        fun baseUrl(baseUrl: String): Builder = this
        fun client(client: okhttp3.OkHttpClient): Builder = this
        fun addConverterFactory(factory: Converter.Factory): Builder = this
        fun build(): Retrofit = TODO()
    }
}
