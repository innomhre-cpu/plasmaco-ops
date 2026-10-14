@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.runtime

import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineScope

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
annotation class Composable

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ReadOnlyComposable

@Target(AnnotationTarget.CLASS)
annotation class Immutable

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Stable

interface State<out T> { val value: T }
interface MutableState<T> : State<T> { override var value: T }
interface SnapshotMutationPolicy<T>

inline operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value
inline operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) { this.value = value }

fun <T> mutableStateOf(value: T, policy: SnapshotMutationPolicy<T>? = null): MutableState<T> = TODO()

class SnapshotStateMap<K, V> : MutableMap<K, V> by HashMap()
fun <K, V> mutableStateMapOf(): SnapshotStateMap<K, V> = TODO()

@Composable inline fun <T> remember(crossinline calculation: () -> T): T = calculation()
@Composable inline fun <T> remember(key1: Any?, crossinline calculation: () -> T): T = calculation()
@Composable inline fun <T> remember(key1: Any?, key2: Any?, crossinline calculation: () -> T): T = calculation()
@Composable inline fun <T> remember(key1: Any?, key2: Any?, key3: Any?, crossinline calculation: () -> T): T = calculation()
@Composable inline fun <T> remember(vararg keys: Any?, crossinline calculation: () -> T): T = calculation()

@Composable fun LaunchedEffect(key1: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(key1: Any?, key2: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {}

@Composable fun rememberCoroutineScope(): CoroutineScope = TODO()
@Composable fun <T> rememberUpdatedState(newValue: T): State<T> = TODO()

class ProvidedValue<T>
abstract class CompositionLocal<T> {
    val current: T @Composable @ReadOnlyComposable get() = TODO()
}
abstract class ProvidableCompositionLocal<T> : CompositionLocal<T>() {
    infix fun provides(value: T): ProvidedValue<T> = TODO()
}
fun <T> staticCompositionLocalOf(defaultFactory: () -> T): ProvidableCompositionLocal<T> = TODO()
fun <T> compositionLocalOf(defaultFactory: () -> T): ProvidableCompositionLocal<T> = TODO()

@Composable fun CompositionLocalProvider(vararg values: ProvidedValue<*>, content: @Composable () -> Unit) {}

@Composable inline fun <T> key(vararg keys: Any?, block: @Composable () -> T): T = block()

/** Read by the Compose compiler plugin to check the runtime version (1.7.x = 12000-range). */
object ComposeVersion { const val version: Int = 12000 }
