package rw.plasmaco.ops.data.remote

import retrofit2.HttpException
import rw.plasmaco.ops.api.ApiError
import rw.plasmaco.ops.data.repository.AppJson

/** The server's own explanation from an error answer (e.g. an account is locked), never anything from the request. */
fun HttpException.serverMessage(): String? = runCatching {
    response()?.errorBody()?.string()?.let { AppJson.decodeFromString(ApiError.serializer(), it).message }
}.getOrNull()?.take(200)
