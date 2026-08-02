package io.earlisreal.ejournal.domain.marketdata

import io.earlisreal.ejournal.data.repository.CredentialsRepository
import io.earlisreal.ejournal.data.repository.MarketDataRepository
import io.earlisreal.ejournal.data.repository.PortfolioRepository
import io.earlisreal.ejournal.domain.ClosedPositionService
import io.earlisreal.ejournal.domain.OpenPositionService
import io.earlisreal.ejournal.domain.model.Market
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Duration.Companion.milliseconds

data class SyncResult(
    val fetchedSymbols: Int,
    val failedSymbols: List<String>,
    val keysRejected: Boolean,
    /** Crypto 1-min bars were needed but no Alpaca keys are configured (stock 1-min falls back to Yahoo). */
    val needsKeys: Boolean,
)

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Syncing(val completed: Int, val total: Int) : SyncStatus()
    data class Finished(val result: SyncResult) : SyncStatus()
}

/**
 * Derive-and-reconcile sync: what's needed is recomputed from transactions (like closed
 * positions, never persisted), what's stored is the coverage check — so any pass heals
 * all gaps and there is no retry bookkeeping. Triggers (post-import, startup, manual
 * retry/sync) all land on [requestSync]/[sync].
 */
class MarketDataService(
    private val portfolioRepository: PortfolioRepository,
    private val closedPositions: ClosedPositionService,
    private val openPositions: OpenPositionService,
    private val marketDataRepository: MarketDataRepository,
    private val yahooProvider: MarketDataProvider,
    private val yahooCryptoProvider: MarketDataProvider,
    private val alpacaProvider: MarketDataProvider,
    private val cryptoProvider: MarketDataProvider,
    private val credentialsRepository: CredentialsRepository,
    private val scope: CoroutineScope? = null,
    private val todayProvider: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
    private val yahooSemaphore = Semaphore(MAX_YAHOO_CONCURRENT)
    private val alpacaSemaphore = Semaphore(MAX_ALPACA_CONCURRENT)
    private val cryptoSemaphore = Semaphore(MAX_ALPACA_CONCURRENT)

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Fire-and-forget for UI triggers; no-op while a sync is already running. */
    fun requestSync() {
        val scope = scope ?: return
        if (_status.value is SyncStatus.Syncing) return
        scope.launch { sync() }
    }

    suspend fun sync(): SyncResult {
        val today = todayProvider()
        val hasKeys = credentialsRepository.getAlpacaCredentials() != null

        val portfolios = portfolioRepository.getAll().filter { it.market == Market.US_STOCKS || it.market == Market.CRYPTO }
        val positions = portfolios.flatMap { closedPositions.forPortfolio(it.id) }
        val openPositionsAll = portfolios.flatMap { openPositions.forPortfolio(it.id) }

        val work = (requiredRanges(positions, today) + requiredUnderlyingRanges(openPositionsAll, today))
            .flatMap { range -> subtractCoverage(range, marketDataRepository.getCoverage(range.symbol, range.timeframe, range.market)) }
            .flatMap { range -> route(range, hasKeys) }
            .groupBy { it.range.symbol }

        _status.value = SyncStatus.Syncing(0, work.size)

        val progressMutex = Mutex()
        var completed = 0

        val symbolResults: List<SymbolFetchResult> = coroutineScope {
            work.entries.map { (symbol, routedRanges) ->
                async {
                    val result = fetchSymbol(symbol, routedRanges)
                    progressMutex.withLock {
                        completed++
                        _status.value = SyncStatus.Syncing(completed, work.size)
                    }
                    result
                }
            }.awaitAll()
        }

        val result = SyncResult(
            fetchedSymbols = symbolResults.count { it.fetched },
            failedSymbols = symbolResults.filter { it.failed }.map { it.symbol },
            keysRejected = symbolResults.any { it.keysRejected },
            needsKeys = symbolResults.any { it.needsKeys },
        )
        _status.value = SyncStatus.Finished(result)
        return result
    }

    /**
     * Best-effort current price for [symbol]: a live Alpaca quote if keys are configured (see
     * [MarketDataProvider.getLatestPrice] -- only [AlpacaProvider] implements it, every other provider's
     * default returns null so this call is always safe regardless of source), else the latest stored
     * daily close (populated by [sync] via [requiredUnderlyingRanges] for open positions). Null if
     * neither is available (no keys yet and nothing synced).
     */
    suspend fun currentPrice(symbol: String, market: Market): Double? {
        alpacaProvider.getLatestPrice(symbol)?.let { return it }
        val coverage = marketDataRepository.getCoverage(symbol, Timeframe.DAILY, market) ?: return null
        return marketDataRepository.getBars(symbol, Timeframe.DAILY, market, coverage.last, coverage.last)
            .lastOrNull()?.close
    }

    private suspend fun fetchSymbol(symbol: String, routedRanges: List<RoutedRange>): SymbolFetchResult {
        val outcomes = coroutineScope {
            routedRanges.map { routed -> async { fetchRange(routed) } }.awaitAll()
        }

        var fetched = false
        var failed = false
        var keysRejected = false
        var needsKeys = false

        for (outcome in outcomes) {
            when (outcome) {
                RangeOutcome.Success -> fetched = true
                RangeOutcome.NeedsKeys -> needsKeys = true
                RangeOutcome.KeysRejected -> keysRejected = true
                RangeOutcome.SymbolNotFound -> return SymbolFetchResult(symbol, false, false, false, false)
                RangeOutcome.Failed -> failed = true
            }
        }

        return SymbolFetchResult(symbol, fetched, failed, keysRejected, needsKeys)
    }

    private suspend fun fetchRange(routed: RoutedRange): RangeOutcome {
        val (provider, semaphore) = when (routed.source) {
            BarSource.YAHOO -> yahooProvider to yahooSemaphore
            BarSource.YAHOO_CRYPTO -> yahooCryptoProvider to yahooSemaphore
            BarSource.ALPACA -> alpacaProvider to alpacaSemaphore
            BarSource.ALPACA_CRYPTO -> cryptoProvider to cryptoSemaphore
            BarSource.UNAVAILABLE -> return RangeOutcome.NeedsKeys
        }
        val r = routed.range
        return try {
            val bars = semaphore.withPermit { fetchWithRetry(provider, r) }
            marketDataRepository.upsertBars(r.market, bars)
            RangeOutcome.Success
        } catch (e: InvalidKeysException) {
            RangeOutcome.KeysRejected
        } catch (e: SymbolNotFoundException) {
            println("[sync] symbol not found: ${r.symbol}")
            RangeOutcome.SymbolNotFound
        } catch (e: TransientFetchException) {
            println("[sync] FAILED ${r.symbol} ${r.timeframe} ${r.from}..${r.to}: ${e.message}")
            RangeOutcome.Failed
        } catch (e: Exception) {
            println("[sync] UNEXPECTED ${r.symbol} ${r.timeframe} ${r.from}..${r.to}: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            RangeOutcome.Failed
        }
    }

    private enum class RangeOutcome { Success, NeedsKeys, KeysRejected, SymbolNotFound, Failed }

    private suspend fun fetchWithRetry(provider: MarketDataProvider, range: BarRange): List<Bar> =
        try {
            provider.getBars(range.symbol, range.timeframe, range.from, range.to)
        } catch (e: TransientFetchException) {
            delay(RETRY_DELAY_MS.milliseconds)
            provider.getBars(range.symbol, range.timeframe, range.from, range.to)
        }

    private data class SymbolFetchResult(
        val symbol: String,
        val fetched: Boolean,
        val failed: Boolean,
        val keysRejected: Boolean,
        val needsKeys: Boolean,
    )

    companion object {
        private const val RETRY_DELAY_MS = 2_000L
        private const val MAX_YAHOO_CONCURRENT = 20
        private const val MAX_ALPACA_CONCURRENT = 30
    }
}
