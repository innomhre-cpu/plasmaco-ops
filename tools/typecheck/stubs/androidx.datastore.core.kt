@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.datastore.core

import kotlinx.coroutines.flow.Flow
interface DataStore<T> { val data: Flow<T>; suspend fun updateData(transform: suspend (t: T) -> T): T }
