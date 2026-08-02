package io.earlisreal.ejournal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.earlisreal.ejournal.ui.theme.AppTheme
import io.earlisreal.ejournal.ui.theme.CardShape
import io.earlisreal.ejournal.ui.theme.Spacing

/**
 * Generic table. `columns` are header labels; `cells` provides each row's cell strings.
 * `weights` (optional) sets per-column flex; defaults to equal. `cellColor` (optional) overrides a
 * specific cell's text color (row, column index) -> color; return null for the default color.
 * `onHeaderClick` (optional) makes column headers clickable -- pass it to let a caller drive sorting;
 * `sortedColumn`/`sortAscending` (only meaningful together with `onHeaderClick`) show a ▲/▼ indicator
 * on the active column. Rows are rendered in the order given -- sorting itself is the caller's job.
 */
@Composable
fun <T> DataTable(
    columns: List<String>,
    rows: List<T>,
    cells: (T) -> List<String>,
    modifier: Modifier = Modifier,
    weights: List<Float> = columns.map { 1f },
    cellColor: (T, Int) -> Color? = { _, _ -> null },
    sortedColumn: Int? = null,
    sortAscending: Boolean = true,
    onHeaderClick: ((Int) -> Unit)? = null,
) {
    Column(
        modifier = modifier.border(1.dp, AppTheme.colors.border, CardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surfaceElevated)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            columns.forEachIndexed { i, col ->
                val label = if (i == sortedColumn) "$col ${if (sortAscending) "▲" else "▼"}" else col
                Text(
                    label,
                    modifier = Modifier
                        .weight(weights.getOrElse(i) { 1f })
                        .let { m -> if (onHeaderClick != null) m.clickable { onHeaderClick(i) } else m },
                    fontWeight = FontWeight.SemiBold,
                    color = if (i == sortedColumn) AppTheme.colors.textPrimary else AppTheme.colors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        HorizontalDivider(color = AppTheme.colors.border)
        LazyColumn {
            itemsIndexed(rows) { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                ) {
                    cells(row).forEachIndexed { i, cell ->
                        Text(
                            cell,
                            modifier = Modifier.weight(weights.getOrElse(i) { 1f }),
                            color = cellColor(row, i) ?: AppTheme.colors.textPrimary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (index < rows.lastIndex) {
                    HorizontalDivider(color = AppTheme.colors.border.copy(alpha = 0.5f))
                }
            }
        }
    }
}
