package rw.plasmaco.ops.data.remote

import rw.plasmaco.ops.api.LoginRequest
import rw.plasmaco.ops.api.LoginResponse
import rw.plasmaco.ops.api.PullResponse
import rw.plasmaco.ops.api.PushRequest
import rw.plasmaco.ops.api.PushResponse
import rw.plasmaco.ops.api.SaveUserRequest
import rw.plasmaco.ops.api.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** Versioned REST API (docs/server.md). Devices sign in, push and pull; administrators also manage accounts on the server. */
interface PlasmacoApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /** Applies queued device operations; each carries an idempotency key so retries are applied once. */
    @POST("api/v1/sync/push")
    suspend fun push(@Body body: PushRequest): PushResponse

    /** Server changes since [since] (opaque cursor), oldest first. */
    @GET("api/v1/sync/pull")
    suspend fun pull(@Query("since") since: String?, @Query("limit") limit: Int = 500): PullResponse

    /** Creates or edits an account (administrators only); the server keeps the password hash and syncs the account to devices. */
    @POST("api/v1/admin/users")
    suspend fun saveUser(@Body body: SaveUserRequest): UserDto
}
