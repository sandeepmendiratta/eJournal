package io.earlisreal.ejournal.domain

import io.earlisreal.ejournal.data.repository.PortfolioRepository
import io.earlisreal.ejournal.data.repository.TransactionRepository
import io.earlisreal.ejournal.domain.model.Market
import io.earlisreal.ejournal.domain.model.OpenPosition
import io.earlisreal.ejournal.domain.model.Transaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Computes a portfolio's still-open positions, memoized the same way as [ClosedPositionService]. */
class OpenPositionService(
    private val transactionRepository: TransactionRepository,
    private val portfolioRepository: PortfolioRepository,
    private val compute: (List<Transaction>) -> List<OpenPosition> = FifoMatcher::computeOpenPositions,
) {
    private data class Entry(val signature: Int, val positions: List<OpenPosition>)

    private val mutex = Mutex()
    private val cache = mutableMapOf<Long, Entry>()

    suspend fun forPortfolio(portfolioId: Long): List<OpenPosition> {
        val txs = transactionRepository.getByPortfolio(portfolioId)
        val signature = txs.hashCode()

        mutex.withLock { cache[portfolioId] }
            ?.takeIf { it.signature == signature }
            ?.let { return it.positions }

        val market = portfolioRepository.getById(portfolioId)?.market ?: Market.US_STOCKS
        val positions = compute(txs).map { it.copy(market = market) }
        mutex.withLock {
            val current = cache[portfolioId]
            if (current == null || current.signature == signature) {
                cache[portfolioId] = Entry(signature, positions)
            }
        }
        return positions
    }
}
