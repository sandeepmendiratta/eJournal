package io.earlisreal.ejournal.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.earlisreal.ejournal.domain.OpenPositionService
import io.earlisreal.ejournal.domain.PositionTagService
import io.earlisreal.ejournal.domain.analytics.ClosedOption
import io.earlisreal.ejournal.domain.analytics.DateRange
import io.earlisreal.ejournal.domain.analytics.MonthlySummary
import io.earlisreal.ejournal.domain.analytics.RollChain
import io.earlisreal.ejournal.domain.analytics.Segment
import io.earlisreal.ejournal.domain.analytics.TagStat
import io.earlisreal.ejournal.domain.analytics.UnderlyingExposure
import io.earlisreal.ejournal.domain.analytics.UnderlyingStat
import io.earlisreal.ejournal.domain.analytics.WeeklyExposure
import io.earlisreal.ejournal.domain.analytics.closedOptionsLedger
import io.earlisreal.ejournal.domain.analytics.detectRollChains
import io.earlisreal.ejournal.domain.analytics.exposureByExpiryWeek
import io.earlisreal.ejournal.domain.analytics.exposureByUnderlying
import io.earlisreal.ejournal.domain.analytics.filterPositions
import io.earlisreal.ejournal.domain.analytics.monthlySummaries
import io.earlisreal.ejournal.domain.analytics.openRisk
import io.earlisreal.ejournal.domain.analytics.tagStats
import io.earlisreal.ejournal.domain.analytics.underlyingStats
import io.earlisreal.ejournal.domain.marketdata.MarketDataService
import io.earlisreal.ejournal.domain.model.AssetClass
import io.earlisreal.ejournal.domain.model.Market
import io.earlisreal.ejournal.domain.model.TradeDirection
import io.earlisreal.ejournal.domain.model.assetClassOf
import io.earlisreal.ejournal.domain.model.parseOccSymbol
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.todayIn

/** A quick day-window filter for the expiring-options report. */
enum class ExpiryWindow(val days: Int, val label: String) {
    TEN(10, "10d"), THIRTY(30, "30d"), FORTY_FIVE(45, "45d"), SIXTY(60, "60d"),
}

/** One still-open option position, decoded for display. */
data class ExpiringOption(
    val symbol: String,
    val root: String,
    val right: io.earlisreal.ejournal.domain.model.OptionRight,
    val strike: Double,
    val expiry: LocalDate,
    val daysToExpiry: Int,
    val direction: TradeDirection,
    val contracts: Double,
    /** Per-share premium paid (long) or received (short) -- [OpenPosition.averagePrice] as-is. */
    val averagePrice: Double,
)

data class ReportsState(
    val stats: List<TagStat> = emptyList(),
    val expiringOptions: List<ExpiringOption> = emptyList(),
    val expiryWindow: ExpiryWindow = ExpiryWindow.THIRTY,
    val closedOptions: List<ClosedOption> = emptyList(),
    val monthlySummaries: List<MonthlySummary> = emptyList(),
    val topUnderlyings: List<UnderlyingStat> = emptyList(),
    val rollChains: List<RollChain> = emptyList(),
    /** Best-effort current price per underlying root (see [MarketDataService.currentPrice]); missing entries are unavailable, not zero. */
    val underlyingPrices: Map<String, Double> = emptyMap(),
    val riskByUnderlying: List<UnderlyingExposure> = emptyList(),
    val riskByExpiryWeek: List<WeeklyExposure> = emptyList(),
    val totalOpenRisk: Double = 0.0,
    val loading: Boolean = false,
)

/**
 * Backs the Reports screen's independent sections: per-tag performance, monthly P&L, and top underlyings
 * (all respect the portfolio, date range, and segment -- tag performance deliberately ignores the global
 * *tag* filter, since the whole point of that section is to compare all tags side by side); upcoming
 * option expirations and roll chains (relative to today, so they ignore date range/segment -- an open
 * position isn't "in" any historical range, though roll chains do include already-closed legs of a chain
 * whose final leg is still open); and the closed options ledger (respects date range/segment).
 */
class ReportsViewModel(
    private val positionTags: PositionTagService,
    private val openPositions: OpenPositionService,
    private val marketDataService: MarketDataService,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {

    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var allExpiringOptions: List<ExpiringOption> = emptyList()

    fun load(portfolioId: Long?, range: DateRange, segment: Segment, market: Market) {
        loadJob?.cancel()
        if (portfolioId == null) {
            allExpiringOptions = emptyList()
            _state.value = ReportsState()
            return
        }
        _state.value = _state.value.copy(loading = true)
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            val positions = positionTags.forPortfolio(portfolioId)
            val filtered = filterPositions(positions, range, segment)
            val open = openPositions.forPortfolio(portfolioId)
            val todayDate = today()

            allExpiringOptions = open
                .filter { assetClassOf(it.symbol) == AssetClass.OPTION }
                .mapNotNull { pos ->
                    val occ = parseOccSymbol(pos.symbol) ?: return@mapNotNull null
                    val daysToExpiry = todayDate.daysUntil(occ.expiry)
                    if (daysToExpiry < 0) return@mapNotNull null
                    ExpiringOption(
                        symbol = pos.symbol, root = occ.root, right = occ.right, strike = occ.strike,
                        expiry = occ.expiry, daysToExpiry = daysToExpiry, direction = pos.direction,
                        contracts = pos.shares / 100, averagePrice = pos.averagePrice,
                    )
                }
                .sortedBy { it.daysToExpiry }

            // Roll chains use the *unfiltered* closed positions (a chain shouldn't disappear because one
            // of its legs falls outside the selected date range) plus all currently-open option positions.
            val rollChains = detectRollChains(positions, open, todayDate)

            // Open risk needs a price for every open position's root, not just the ones already covered
            // by expiring options/roll chains -- a pure equity holding with no option activity otherwise
            // wouldn't get a quote at all.
            val underlyingRoots = (
                allExpiringOptions.map { it.root } + rollChains.map { it.root } +
                    open.map { pos -> parseOccSymbol(pos.symbol)?.root ?: pos.symbol }
                ).toSet()
            val prices = coroutineScope {
                underlyingRoots.associateWith { root -> async { marketDataService.currentPrice(root, market) } }
                    .mapValues { it.value.await() }
            }.filterValues { it != null }.mapValues { it.value as Double }

            val risks = openRisk(open, prices, todayDate)

            _state.value = _state.value.copy(
                stats = tagStats(filtered),
                expiringOptions = windowed(_state.value.expiryWindow),
                closedOptions = closedOptionsLedger(filtered),
                monthlySummaries = monthlySummaries(filtered),
                topUnderlyings = underlyingStats(filtered).take(TOP_UNDERLYINGS_LIMIT),
                rollChains = rollChains,
                underlyingPrices = prices,
                riskByUnderlying = exposureByUnderlying(risks),
                riskByExpiryWeek = exposureByExpiryWeek(risks),
                totalOpenRisk = risks.sumOf { it.riskAmount },
                loading = false,
            )
        }
    }

    fun setExpiryWindow(window: ExpiryWindow) {
        _state.value = _state.value.copy(expiryWindow = window, expiringOptions = windowed(window))
    }

    private fun windowed(window: ExpiryWindow) = allExpiringOptions.filter { it.daysToExpiry <= window.days }

    companion object {
        private const val TOP_UNDERLYINGS_LIMIT = 10
    }
}
