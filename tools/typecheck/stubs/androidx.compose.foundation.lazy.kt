@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE")

package androidx.compose.foundation.lazy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

interface LazyItemScope {
    fun androidx.compose.ui.Modifier.fillParentMaxWidth(fraction: Float = 1f): Modifier = this
}
interface LazyListScope {
    fun item(key: Any? = null, contentType: Any? = null, content: @Composable LazyItemScope.() -> Unit)
    fun items(count: Int, key: ((index: Int) -> Any)? = null, contentType: (index: Int) -> Any? = { null }, itemContent: @Composable LazyItemScope.(index: Int) -> Unit)
}
inline fun <T> LazyListScope.items(items: List<T>, noinline key: ((item: T) -> Any)? = null, crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit) {}
class LazyListState
@Composable fun rememberLazyListState(initialFirstVisibleItemIndex: Int = 0, initialFirstVisibleItemScrollOffset: Int = 0): LazyListState = TODO()
@Composable fun LazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(androidx.compose.ui.unit.Dp(0f)),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {}
