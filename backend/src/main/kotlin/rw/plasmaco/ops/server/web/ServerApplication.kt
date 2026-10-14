package rw.plasmaco.ops.server.web

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import rw.plasmaco.ops.api.WireJson
import rw.plasmaco.ops.domain.common.SystemTimeSource
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.server.core.AuthService
import rw.plasmaco.ops.server.core.JwtCodec
import rw.plasmaco.ops.server.core.ServerStore
import rw.plasmaco.ops.server.core.SyncService
import java.time.Duration
import java.time.ZoneId
import java.util.Base64

@SpringBootApplication
@EnableConfigurationProperties(PlasmacoProperties::class)
class PlasmacoServerApplication

fun main(args: Array<String>) {
    runApplication<PlasmacoServerApplication>(*args)
}

/**
 * Server settings (application.yml, overridable by environment variables). There are no default secrets: the
 * token secret must be supplied (PLASMACO_JWT_SECRET, base64 of at least 32 random bytes) or the server will not start.
 */
@ConfigurationProperties(prefix = "plasmaco")
data class PlasmacoProperties(
    /** Base64-encoded HMAC key for API tokens. */
    val jwtSecret: String = "",
    val tokenTtlHours: Long = 12,
    /** Negative-stock exception: off unless management decides otherwise; every override is audited. */
    val allowNegativeOverride: Boolean = false,
    val maxLoginFailures: Int = 5,
    val lockMinutes: Long = 15,
    val timeZone: String = "Africa/Kigali",
    /** Used only when the database has no user yet; remove from the environment after first start. */
    val bootstrapAdminUsername: String = "",
    val bootstrapAdminName: String = "Administrator",
    val bootstrapAdminPassword: String = "",
)

@Configuration
class ServerBeans {
    @Bean
    fun timeSource(props: PlasmacoProperties): TimeSource = SystemTimeSource(ZoneId.of(props.timeZone))

    @Bean
    fun serverStore(jdbc: JdbcTemplate, txManager: PlatformTransactionManager): ServerStore = JdbcServerStore(jdbc, TransactionTemplate(txManager))

    @Bean
    fun jwtCodec(props: PlasmacoProperties, time: TimeSource): JwtCodec {
        val secret = try {
            Base64.getDecoder().decode(props.jwtSecret.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("plasmaco.jwt-secret must be base64 (e.g. `openssl rand -base64 48`)")
        }
        check(secret.size >= 32) { "plasmaco.jwt-secret is missing or shorter than 32 bytes; set PLASMACO_JWT_SECRET" }
        return JwtCodec(secret) { time.now() }
    }

    @Bean
    fun authService(store: ServerStore, jwt: JwtCodec, time: TimeSource, props: PlasmacoProperties): AuthService =
        AuthService(store, jwt, time, AuthService.Policy(props.maxLoginFailures, Duration.ofMinutes(props.lockMinutes), Duration.ofHours(props.tokenTtlHours)))

    @Bean
    fun syncService(store: ServerStore, time: TimeSource, props: PlasmacoProperties): SyncService =
        SyncService(store, time, SyncService.Rules(allowNegativeOverride = props.allowNegativeOverride))

    /** JSON in and out uses the shared wire settings (core/api), so the server reads exactly what the app writes. */
    @Bean
    fun wireJsonConfigurer(): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters.add(0, KotlinSerializationJsonHttpMessageConverter(WireJson))
        }
    }

    @Bean
    fun bootstrapAdministrator(auth: AuthService, props: PlasmacoProperties): ApplicationRunner = ApplicationRunner { _: ApplicationArguments ->
        val log = LoggerFactory.getLogger("rw.plasmaco.ops.server.Bootstrap")
        if (props.bootstrapAdminUsername.isNotBlank() && props.bootstrapAdminPassword.isNotBlank()) {
            if (auth.bootstrapAdministrator(props.bootstrapAdminUsername, props.bootstrapAdminName, props.bootstrapAdminPassword)) {
                log.info("Bootstrap administrator '{}' created; remove PLASMACO_BOOTSTRAP_ADMIN_PASSWORD from the environment", props.bootstrapAdminUsername)
            }
        }
    }
}
