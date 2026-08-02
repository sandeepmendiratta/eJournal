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
import androidx.compose.runtime.remember
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
import io.earlisreal.ejournal.domain.analytics.UnderlyingStat
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
                        Text(
                            "Roll chains",
                            color = AppTheme.colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Sequences of 2+ option legs on the same underlying/side where a close was followed by a near-day reopen -- inferred from timing, not broker-labeled, so double-check anything surprising.",
                            color = AppTheme.colors.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (state.rollChains.isEmpty()) {
                            Text(
                                "No roll chains detected.",
                                color = AppTheme.colors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            RollChainsTable(
                                state.rollChains, state.underlyingPrices, symbol,
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

@Composable
private fun RollChainsTable(
    chains: List<RollChain>,
    underlyingPrices: Map<String, Double>,
    symbol: String,
    modifier: Modifier = Modifier,
) {
    DataTable(
        columns = listOf(
            "Chain", "Underlying", "Type", "Side", "Legs", "First Open", "Latest Exp",
            "Status", "Current Price", "Strike Path", "Realized P/L", "Days Running",
        ),
        rows = chains,
        cells = { c ->
            listOf(
                c.id,
                c.root,
                if (c.right == OptionRight.CALL) "Call" else "Put",
                if (c.direction == TradeDirection.LONG) "Long" else "Short",
                c.legs.size.toString(),
                shortDate(c.firstOpen.date),
                shortDate(c.latestExpiry),
                if (c.isOpen) "Open" else "Closed",
                underlyingPrices[c.root]?.let { "$%.2f".format(it) } ?: "—",
                c.strikePath,
                signedMoney(c.realizedPnl, symbol),
                c.daysRunning.toString(),
            )
        },
        weights = remember {
            listOf(0.9f, 0.9f, 0.7f, 0.7f, 0.6f, 0.9f, 0.9f, 0.8f, 0.9f, 1.6f, 0.9f, 0.8f)
        },
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
    DataTable(
        columns = listOf("Month", "Net P/L", "Trades"),
        rows = summaries,
        cells = { m ->
            listOf(
                "${monthName(m.month.month)} ${m.month.year}",
                signedMoney(m.netPnl, symbol),
                m.tradeCount.toString(),
            )
        },
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
