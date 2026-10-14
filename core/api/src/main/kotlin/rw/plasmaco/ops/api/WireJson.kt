package rw.plasmaco.ops.api

import kotlinx.serialization.json.Json

/**
 * JSON settings for the /api/v1 wire format, used by both the app and the server so they read each other exactly.
 * Unknown keys are ignored (a newer server can add fields), defaults are written, and absent optional fields are
 * omitted rather than sent as null.
 */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
