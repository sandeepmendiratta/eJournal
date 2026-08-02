package io.earlisreal.ejournal.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.earlisreal.ejournal.domain.OpenPositionService
import io.earlisreal.ejournal.domain.PositionTagService
import io.earlisreal.ejournal.domain.analytics.ClosedOption
import io.earlisreal.ejournal.domain.analytics.MonthlySummary
import io.earlisreal.ejournal.domain.analytics.RollChain
import io.earlisreal.ejournal.domain.analytics.UnderlyingExposure
import io.earlisreal.ejournal.domain.analytics.UnderlyingStat
import io.earlisreal.ejournal.domain.analytics.WeeklyExposure
import io.earlisreal.ejournal.domain.marketdata.MarketDataService
import io.earlisreal.ejournal.domain.model.Market
import io.earlisreal.ejournal.domain.model.OptionRight
import io.earlisreal.ejournal.domain.model.TradeDirection
import io.earlisreal.ejournal.ui.components.DataTable
import io.earlisreal.ejournal.ui.components.LoadingIndicator
import io.earlisreal.ejournal.ui.components.EmptyState
import io.earlisreal.ejournal.ui.components.ScreenScaffold
import io.earlisreal.ejournal.ui.components.TagStatsTable
import io.earlisreal.ejournal.ui.components.monthName
import io.earlisreal.ejournal.ui.components.shortDate
import io.earlisreal.ejournal.ui.components.shortDateWithYear
import io.earlisreal.ejournal.ui.components.signedMoney
import io.earlisreal.ejournal.ui.shell.FilterState
import io.earlisreal.ejournal.ui.theme.AppTheme
import io.earlisreal.ejournal.ui.theme.PillShape
import io.earlisreal.ejournal.ui.theme.Spacing
import io.earlisreal.ejournal.ui.viewmodel.ExpiringOption
import io.earlisreal.ejournal.ui.viewmodel.ExpiryWindow
import io.earlisreal.ejournal.ui.viewmodel.ReportsViewModel

private val TABLE_MAX_HEIGHT = 320.dp

@Composable
fun ReportsScreen(
    positionTags: PositionTagService,
    openPositions: OpenPositionService,
    marketDataService: MarketDataService,
    filter: FilterState,
    onSelectTag: (Long) -> Unit = {},
) {
    val vm = viewModel { ReportsViewModel(positionTags, openPositions, marketDataService) }
    val state by vm.state.collectAsState()

    LaunchedEffect(filter) {
        vm.load(filter.portfolio?.id, filter.dateRange, filter.segment, filter.portfolio?.market ?: Market.US_STOCKS)
    }

    ScreenScaffold(title = "Reports") {
        when {
            filter.portfolio == null -> EmptyState(
                title = "No portfolio selected",
                subtitle = "Import transactions to get started.",
            )
            state.loading -> LoadingIndicator()
            else -> {
                val symbol = filter.portfolio?.market?.symbol ?: "$"
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Upcoming option expirations",
                                color = AppTheme.colors.textPrimary,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            ExpiryWindowToggle(state.expiryWindow, vm::setExpiryWindow)
                        }
                        if (state.expiringOptions.isEmpty()) {
                            Text(
                                "No open option positions expiring within ${state.expiryWindow.days} days.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            ExpiringOptionsTable(
                                state.expiringOptions,
                                state.underlyingPrices,
                                modifier = Modifier.fillMaxWidth().heightIn(max = TABLE_MAX_HEIGHT),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            "Open risk by underlying",
                            color = AppTheme.colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Total open risk: ${plainMoney(state.totalOpenRisk, symbol)}. Equity is mark-to-market value; " +
                                "short options use strike-based notional (your real commitment if assigned); long options use " +
                                "premium paid (your real max loss). Rows over ${CONCENTRATION_WARNING_PCT.toInt()}% of total are flagged.",
                            color = AppTheme.colors.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (state.riskByUnderlying.isEmpty()) {
                            Text(
                                "No open positions.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            OpenRiskByUnderlyingTable(
                                state.riskByUnderlying, state.totalOpenRisk, symbol,
                                modifier = Modifier.fillMaxWidth().heightIn(max = TABLE_MAX_HEIGHT),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            "Open risk by expiry week",
                            color = AppTheme.colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Options only, grouped by the Monday of their expiry week -- flags weeks where assignment/gap risk is clustered.",
                            color = AppTheme.colors.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (state.riskByExpiryWeek.isEmpty()) {
                            Text(
                                "No open option positions.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            OpenRiskByExpiryWeekTable(
                                state.riskByExpiryWeek, state.totalOpenRisk, symbol,
                                modifier = Modifier.fillMaxWidth().heightIn(max = TABLE_MAX_HEIGHT),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            "Performance by tag",
                            color = AppTheme.colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.stats.isEmpty()) {
                            Text(
                                "No closed positions in this range. Adjust the date range or segment in the top bar.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            Text(
                                "A trade with several tags counts toward each, so per-tag P&L won't sum to your total. Click a tag to see its trades.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            TagStatsTable(
                                stats = state.stats,
                                symbol = symbol,
                                onSelectTag = onSelectTag,
                                modifier = Modifier.fillMaxWidth().heightIn(max = TABLE_MAX_HEIGHT),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            "Monthly realized P&L",
                            color = AppTheme.colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.monthlySummaries.isEmpty()) {
                            Text(
                                "No closed positions in this range.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            MonthlySummaryTable(state.monthlySummaries, symbol, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            "Top underlyings by realized P&L",
                            color = AppTheme.colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.topUnderlyings.isEmpty()) {
                            Text(
                                "No closed positions in this range.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            TopUnderlyingsTable(state.topUnderlyings, symbol, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        var chainFilter by remember { mutableStateOf(RollChainFilter.ALL) }
                        var sortColumn by remember { mutableStateOf<Int?>(ROLL_CHAIN_FIRST_OPEN_COLUMN) }
                        var sortAscending by remember { mutableStateOf(true) }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Roll chains",
                                color = AppTheme.colors.textPrimary,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            RollChainFilterToggle(chainFilter) { chainFilter = it }
                        }
                        Text(
                            "Sequences of 2+ option legs on the same underlying/side where a close was followed by a near-day reopen -- inferred from timing, not broker-labeled, so double-check anything surprising. Click a column to sort.",
                            color = AppTheme.colors.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        val filteredChains = remember(state.rollChains, chainFilter) {
                            when (chainFilter) {
                                RollChainFilter.ALL -> state.rollChains
                                RollChainFilter.OPEN -> state.rollChains.filter { it.isOpen }
                                RollChainFilter.CLOSED -> state.rollChains.filter { !it.isOpen }
                            }
                        }
                        if (filteredChains.isEmpty()) {
                            Text(
                                if (state.rollChains.isEmpty()) "No roll chains detected."
                                else "No ${chainFilter.label.lowercase()} roll chains.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            RollChainsTable(
                                chains = filteredChains,
                                underlyingPrices = state.underlyingPrices,
                                symbol = symbol,
                                sortColumn = sortColumn,
                                sortAscending = sortAscending,
                                onSort = { column ->
                                    if (sortColumn == column) sortAscending = !sortAscending
                                    else { sortColumn = column; sortAscending = true }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(max = TABLE_MAX_HEIGHT),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            "Closed options ledger",
                            color = AppTheme.colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.closedOptions.isEmpty()) {
                            Text(
                                "No closed option positions in this range.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            ClosedOptionsLedgerTable(state.closedOptions, symbol, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpiryWindowToggle(window: ExpiryWindow, onChange: (ExpiryWindow) -> Unit) {
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(AppTheme.colors.surfaceElevated),
    ) {
        ExpiryWindow.entries.forEach { option ->
            val active = option == window
            Text(
                text = option.label,
                color = if (active) AppTheme.colors.onAccent else AppTheme.colors.textMuted,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) AppTheme.colors.accent else Color.Transparent)
                    .clickable { onChange(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private const val CONCENTRATION_WARNING_PCT = 25.0

/** Unsigned money, e.g. "$6,300.00" -- risk amounts are magnitudes, not signed P&L, so no leading +/−. */
private fun plainMoney(value: Double, symbol: String): String = "$symbol%,.2f".format(value)

@Composable
private fun OpenRiskByUnderlyingTable(
    exposure: List<UnderlyingExposure>,
    totalRisk: Double,
    symbol: String,
    modifier: Modifier = Modifier,
) {
    val warnColor = AppTheme.colors.loss
    DataTable(
        columns = listOf("Underlying", "Risk", "% of Total", "Positions"),
        rows = exposure,
        cells = { e ->
            listOf(e.root, plainMoney(e.riskAmount, symbol), "%.1f%%".format(pctOf(e.riskAmount, totalRisk)), e.positionCount.toString())
        },
        cellColor = { e, i -> if (i <= 2 && pctOf(e.riskAmount, totalRisk) >= CONCENTRATION_WARNING_PCT) warnColor else null },
        weights = remember { listOf(1.2f, 1f, 1f, 0.8f) },
        modifier = modifier,
    )
}

@Composable
private fun OpenRiskByExpiryWeekTable(
    exposure: List<WeeklyExposure>,
    totalRisk: Double,
    symbol: String,
    modifier: Modifier = Modifier,
) {
    val warnColor = AppTheme.colors.loss
    DataTable(
        columns = listOf("Week Of", "Risk", "% of Total", "Positions"),
        rows = exposure,
        cells = { w ->
            listOf(
                shortDateWithYear(w.weekStart), plainMoney(w.riskAmount, symbol),
                "%.1f%%".format(pctOf(w.riskAmount, totalRisk)), w.positionCount.toString(),
            )
        },
        cellColor = { w, i -> if (i <= 2 && pctOf(w.riskAmount, totalRisk) >= CONCENTRATION_WARNING_PCT) warnColor else null },
        weights = remember { listOf(1.2f, 1f, 1f, 0.8f) },
        modifier = modifier,
    )
}

private fun pctOf(amount: Double, total: Double): Double = if (total > 0) amount / total * 100 else 0.0

/**
 * True if [option] is trending against whoever holds it: in-the-money for a short seller (assignment
 * risk -- they're on the hook at a worse price than the stock now trades at), or out-of-the-money for a
 * long holder (the option isn't earning its keep). Null [currentPrice] (not yet synced) never flags.
 */
private fun isUnderwater(option: ExpiringOption, currentPrice: Double?): Boolean {
    if (currentPrice == null) return false
    val inTheMoney = if (option.right == OptionRight.PUT) currentPrice < option.strike else currentPrice > option.strike
    return if (option.direction == TradeDirection.SHORT) inTheMoney else !inTheMoney
}

@Composable
private fun ExpiringOptionsTable(
    options: List<ExpiringOption>,
    underlyingPrices: Map<String, Double>,
    modifier: Modifier = Modifier,
) {
    val lossColor = AppTheme.colors.loss
    DataTable(
        columns = listOf("Underlying", "Type", "Strike", "Current Price", "Expires", "Days", "Side", "Contracts", "Avg Price"),
        rows = options,
        cells = { o ->
            listOf(
                o.root,
                if (o.right == OptionRight.CALL) "Call" else "Put",
                "%.2f".format(o.strike),
                underlyingPrices[o.root]?.let { "$%.2f".format(it) } ?: "—",
                shortDate(o.expiry),
                o.daysToExpiry.toString(),
                if (o.direction == TradeDirection.LONG) "Long" else "Short",
                "%.0f".format(o.contracts),
                "$%.2f".format(o.averagePrice),
            )
        },
        cellColor = { o, i ->
            if ((i == 2 || i == 3) && isUnderwater(o, underlyingPrices[o.root])) lossColor else null
        },
        modifier = modifier,
    )
}

private const val ROLL_CHAIN_FIRST_OPEN_COLUMN = 5

private enum class RollChainFilter(val label: String) { ALL("All"), OPEN("Open"), CLOSED("Closed") }

@Composable
private fun RollChainFilterToggle(filter: RollChainFilter, onChange: (RollChainFilter) -> Unit) {
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(AppTheme.colors.surfaceElevated),
    ) {
        RollChainFilter.entries.forEach { option ->
            val active = option == filter
            Text(
                text = option.label,
                color = if (active) AppTheme.colors.onAccent else AppTheme.colors.textMuted,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) AppTheme.colors.accent else Color.Transparent)
                    .clickable { onChange(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Column order must match [RollChainsTable]'s `columns` list -- indices below are what [onSort] receives. */
private fun sortedRollChains(
    chains: List<RollChain>,
    column: Int?,
    ascending: Boolean,
    underlyingPrices: Map<String, Double>,
): List<RollChain> {
    if (column == null) return chains
    val comparator: Comparator<RollChain> = when (column) {
        0 -> compareBy { it.id }
        1 -> compareBy { it.root }
        2 -> compareBy { it.right }
        3 -> compareBy { it.direction }
        4 -> compareBy { it.legs.size }
        5 -> compareBy { it.firstOpen }
        6 -> compareBy { it.latestExpiry }
        7 -> compareBy { it.isOpen }
        8 -> compareBy { underlyingPrices[it.root] ?: Double.NEGATIVE_INFINITY }
        9 -> compareBy { it.strikePath }
        10 -> compareBy { it.realizedPnl }
        11 -> compareBy { it.daysRunning }
        else -> return chains
    }
    val sorted = chains.sortedWith(comparator)
    return if (ascending) sorted else sorted.reversed()
}

@Composable
private fun RollChainsTable(
    chains: List<RollChain>,
    underlyingPrices: Map<String, Double>,
    symbol: String,
    sortColumn: Int?,
    sortAscending: Boolean,
    onSort: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(chains, sortColumn, sortAscending, underlyingPrices) {
        sortedRollChains(chains, sortColumn, sortAscending, underlyingPrices)
    }
    DataTable(
        columns = listOf(
            "Chain", "Underlying", "Type", "Side", "Legs", "First Open", "Latest Exp",
            "Status", "Current Price", "Strike Path", "Realized P/L", "Days Running",
        ),
        rows = sorted,
        cells = { c ->
            listOf(
                c.id,
                c.root,
                if (c.right == OptionRight.CALL) "Call" else "Put",
                if (c.direction == TradeDirection.LONG) "Long" else "Short",
                c.legs.size.toString(),
                shortDateWithYear(c.firstOpen.date),
                shortDateWithYear(c.latestExpiry),
                if (c.isOpen) "Open" else "Closed",
                underlyingPrices[c.root]?.let { "$%.2f".format(it) } ?: "—",
                c.strikePath,
                signedMoney(c.realizedPnl, symbol),
                c.daysRunning.toString(),
            )
        },
        weights = remember {
            listOf(0.9f, 0.9f, 0.7f, 0.7f, 0.6f, 1.0f, 1.0f, 0.8f, 0.9f, 1.4f, 0.9f, 0.8f)
        },
        sortedColumn = sortColumn,
        sortAscending = sortAscending,
        onHeaderClick = onSort,
        modifier = modifier,
    )
}

@Composable
private fun ClosedOptionsLedgerTable(options: List<ClosedOption>, symbol: String, modifier: Modifier = Modifier) {
    DataTable(
        columns = listOf(
            "Underlying", "Type", "Strike", "Expiry", "Entry", "Exit",
            "Side", "Contracts", "Entry Price", "Exit Price", "P/L", "Days", "P/L per Day",
        ),
        rows = options,
        cells = { o ->
            listOf(
                o.root,
                if (o.right == OptionRight.CALL) "Call" else "Put",
                "%.2f".format(o.strike),
                shortDate(o.expiry),
                shortDate(o.position.entryDatetime.date),
                shortDate(o.position.exitDatetime.date),
                if (o.position.direction == TradeDirection.LONG) "Long" else "Short",
                "%.0f".format(o.contracts),
                "$%.2f".format(o.position.averageEntryPrice),
                "$%.2f".format(o.position.averageExitPrice),
                signedMoney(o.position.profitLoss, symbol),
                o.daysInTrade.toString(),
                signedMoney(o.profitPerDay, symbol),
            )
        },
        weights = remember { listOf(1.1f, 0.7f, 0.8f, 0.9f, 0.9f, 0.9f, 0.7f, 0.8f, 0.9f, 0.9f, 1f, 0.6f, 1f) },
        modifier = modifier.heightIn(max = TABLE_MAX_HEIGHT),
    )
}

@Composable
private fun MonthlySummaryTable(summaries: List<MonthlySummary>, symbol: String, modifier: Modifier = Modifier) {
    val profitColor = AppTheme.colors.profit
    val lossColor = AppTheme.colors.loss
    DataTable(
        columns = listOf("Month", "Net P/L", "Equity P/L", "Options P/L", "Trades"),
        rows = summaries,
        cells = { m ->
            listOf(
                "${monthName(m.month.month)} ${m.month.year}",
                signedMoney(m.netPnl, symbol),
                signedMoney(m.equityPnl, symbol),
                signedMoney(m.optionsPnl, symbol),
                m.tradeCount.toString(),
            )
        },
        cellColor = { m, i ->
            val value = when (i) { 1 -> m.netPnl; 2 -> m.equityPnl; 3 -> m.optionsPnl; else -> null }
            value?.let { if (it >= 0) profitColor else lossColor }
        },
        weights = remember { listOf(1.2f, 1f, 1f, 1f, 0.7f) },
        modifier = modifier.heightIn(max = TABLE_MAX_HEIGHT),
    )
}

@Composable
private fun TopUnderlyingsTable(stats: List<UnderlyingStat>, symbol: String, modifier: Modifier = Modifier) {
    DataTable(
        columns = listOf("Underlying", "Net P/L", "Trades"),
        rows = stats,
        cells = { s -> listOf(s.root, signedMoney(s.netPnl, symbol), s.tradeCount.toString()) },
        modifier = modifier.heightIn(max = TABLE_MAX_HEIGHT),
    )
}
