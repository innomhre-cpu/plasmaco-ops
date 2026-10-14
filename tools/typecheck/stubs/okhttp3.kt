@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package okhttp3

import java.util.concurrent.TimeUnit
class MediaType { companion object { fun String.toMediaType(): MediaType = TODO() } }
class Request { fun newBuilder(): Builder = TODO(); class Builder { fun header(name: String, value: String): Builder = this; fun build(): Request = TODO() } }
class Response { val code: Int get() = TODO() }
abstract class ResponseBody : java.io.Closeable { fun string(): String = TODO(); override fun close() {} }
fun interface Interceptor {
    fun intercept(chain: Chain): Response
    interface Chain { fun request(): Request; fun proceed(request: Request): Response }
}
class OkHttpClient {
    class Builder {
        fun connectTimeout(timeout: Long, unit: TimeUnit): Builder = this
        fun readTimeout(timeout: Long, unit: TimeUnit): Builder = this
        fun writeTimeout(timeout: Long, unit: TimeUnit): Builder = this
        fun addInterceptor(interceptor: Interceptor): Builder = this
        fun build(): OkHttpClient = TODO()
    }
}
