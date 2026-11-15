package app.dcrux.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The rows of a [CruxList], gathered before they are laid out one way or the other. */
class CruxListScope {
    internal class Entry(val key: Any?, val full: Boolean, val content: @Composable () -> Unit)
    internal val entries = ArrayList<Entry>()

    /** One row. [full] rows (titles, switches, page numbers) span the whole width in the landscape layout. */
    fun item(key: Any? = null, full: Boolean = false, content: @Composable () -> Unit) { entries += Entry(key, full, content) }

    fun <T> items(list: List<T>, key: ((T) -> Any)? = null, full: Boolean = false, content: @Composable (T) -> Unit) {
        list.forEach { v -> entries += Entry(key?.invoke(v), full) { content(v) } }
    }
}

/**
 * A scrolling list that is one column on a phone and, where there is room (a wide window on a
 * computer), flows its cards into as many columns as fit, so the screen is used edge to edge.
 */
@Composable
fun CruxList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 12.dp,
    minColumn: Dp = 430.dp,
    content: CruxListScope.() -> Unit,
) {
    val entries = CruxListScope().apply(content).entries
    if (!LocalWide.current) {
        LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(spacing)) {
            items(entries.size, key = { entries[it].key ?: "row$it" }) { entries[it].content() }
        }
    } else {
        LazyVerticalStaggeredGrid(
            StaggeredGridCells.Adaptive(minColumn), modifier, contentPadding = contentPadding,
            verticalItemSpacing = spacing, horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(
                entries.size, key = { entries[it].key ?: "row$it" },
                span = { if (entries[it].full) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane },
            ) { entries[it].content() }
        }
    }
}
