@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
@Composable fun <T> StateFlow<T>.collectAsStateWithLifecycle(): State<T> = TODO()
@Composable fun <T> Flow<T>.collectAsStateWithLifecycle(initialValue: T): State<T> = TODO()
