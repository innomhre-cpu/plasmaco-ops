@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore
abstract class Preferences {
    class Key<T>(val name: String)
    abstract operator fun <T> get(key: Key<T>): T?
    abstract fun <T> contains(key: Key<T>): Boolean
}
abstract class MutablePreferences : Preferences() {
    abstract operator fun <T> set(key: Key<T>, value: T)
    abstract fun <T> remove(key: Key<T>): T
    abstract fun clear()
}
fun stringPreferencesKey(name: String): Preferences.Key<String> = TODO()
fun intPreferencesKey(name: String): Preferences.Key<Int> = TODO()
fun longPreferencesKey(name: String): Preferences.Key<Long> = TODO()
fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = TODO()
suspend fun DataStore<Preferences>.edit(transform: suspend (MutablePreferences) -> Unit): Preferences = TODO()
