package io.earlisreal.ejournal.domain

import io.earlisreal.ejournal.data.repository.PortfolioRepository
import io.earlisreal.ejournal.data.repository.TagRepository
import io.earlisreal.ejournal.data.repository.TransactionRepository
import io.earlisreal.ejournal.domain.model.Action
import io.earlisreal.ejournal.domain.model.ClosedPosition
import io.earlisreal.ejournal.domain.model.Market
import io.earlisreal.ejournal.domain.model.Portfolio
import io.earlisreal.ejournal.domain.model.Tag
import io.earlisreal.ejournal.domain.model.Transaction
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssetClassTaggingServiceTest {

    private fun txn(id: Long, symbol: String) = Transaction(
        id = id, portfolioId = 1, symbol = symbol,
        datetime = LocalDateTime(2026, 6, 1, 9, 30), action = Action.BUY,
        price = 100.0, shares = 10.0, fees = 1.0,
    )

    private fun positionOpenedBy(txId: Long, symbol: String, tags: List<Tag> = emptyList()) = ClosedPosition(
        symbol = symbol,
        entryDatetime = LocalDateTime(2026, 6, 1, 9, 30),
        exitDatetime = LocalDateTime(2026, 6, 1, 10, 30),
        averageEntryPrice = 100.0, averageExitPrice = 110.0,
        shares = 10.0, fees = 1.0, profitLoss = 90.0,
        transactions = listOf(txn(txId, symbol)),
        tags = tags,
    )

    private class StubTx(val txs: List<Transaction>) : TransactionRepository {
        override suspend fun getByPortfolio(portfolioId: Long) = txs
        override suspend fun getByPortfolioAndDateRange(portfolioId: Long, from: LocalDateTime, to: LocalDateTime) = emptyList<Transaction>()
        override suspend fun insert(transaction: Transaction): Long? = null
        override suspend fun delete(id: Long) {}
        override suspend fun countByPortfolio(portfolioId: Long) = txs.size.toLong()
        override suspend fun deleteByPortfolio(portfolioId: Long) {}
    }

    private class StubPortfolio : PortfolioRepository {
        override suspend fun getAll() = emptyList<Portfolio>()
        override suspend fun getById(id: Long): Portfolio? = null
        override suspend fun insert(name: String, market: Market) = 0L
        override suspend fun update(id: Long, name: String, market: Market) {}
        override suspend fun delete(id: Long) {}
    }

    /** A real-enough fake: `create` actually stores the tag so a subsequent `getAll`/find-or-create sees it. */
    private class InMemoryTagRepo : TagRepository {
        private val tags = mutableMapOf<Long, Tag>()
        private var nextId = 1L
        val assignments = mutableMapOf<Long, MutableList<Long>>() // openingTxId -> tagIds
        var createCalls = 0

        override suspend fun getAll() = tags.values.sortedBy { it.name.lowercase() }
        override suspend fun create(name: String, color: String): Long {
            createCalls++
            if (tags.values.any { it.name.equals(name, ignoreCase = true) }) {
                throw IllegalStateException("duplicate tag name")
            }
            val id = nextId++
            tags[id] = Tag(id, name, color)
            return id
        }
        override suspend fun update(id: Long, name: String, color: String) { tags[id] = Tag(id, name, color) }
        override suspend fun delete(id: Long) { tags.remove(id) }
        override suspend fun getTagsForOpeningTxIds(openingTxIds: List<Long>): Map<Long, List<Tag>> =
            openingTxIds.mapNotNull { id -> assignments[id]?.mapNotNull { tags[it] }?.let { id to it } }.toMap()
        override suspend fun addTag(openingTxId: Long, tagId: Long) {
            assignments.getOrPut(openingTxId) { mutableListOf() }.add(tagId)
        }
        override suspend fun removeTag(openingTxId: Long, tagId: Long) {
            assignments[openingTxId]?.remove(tagId)
        }
    }

    @Test
    fun tagsEquityAndOptionPositionsBySymbolShape() = runTest {
        val positions = listOf(positionOpenedBy(1L, "AAPL"), positionOpenedBy(2L, "TNA260731P63"))
        val closed = ClosedPositionService(StubTx(emptyList()), StubPortfolio()) { positions }
        val tagRepo = InMemoryTagRepo()
        val service = AssetClassTaggingService(PositionTagService(closed, tagRepo), tagRepo)

        service.ensureTagged(1L)

        val equityTagId = tagRepo.getAll().single { it.name == AssetClassTaggingService.EQUITY_TAG }.id
        val optionsTagId = tagRepo.getAll().single { it.name == AssetClassTaggingService.OPTIONS_TAG }.id
        assertEquals(listOf(equityTagId), tagRepo.assignments[1L].orEmpty())
        assertEquals(listOf(optionsTagId), tagRepo.assignments[2L].orEmpty())
    }

    @Test
    fun doesNotRetagAPositionThatAlreadyHasEitherTag() = runTest {
        val existingEquity = Tag(9L, AssetClassTaggingService.EQUITY_TAG, "#000000")
        val positions = listOf(positionOpenedBy(1L, "AAPL", tags = listOf(existingEquity)))
        val closed = ClosedPositionService(StubTx(emptyList()), StubPortfolio()) { positions }
        val tagRepo = InMemoryTagRepo()
        val service = AssetClassTaggingService(PositionTagService(closed, tagRepo), tagRepo)

        service.ensureTagged(1L)

        assertTrue(tagRepo.assignments[1L].isNullOrEmpty()) // untouched -- no new assignment recorded
    }

    @Test
    fun isIdempotentAcrossRepeatedCalls() = runTest {
        val positions = listOf(positionOpenedBy(1L, "AAPL"))
        val closed = ClosedPositionService(StubTx(emptyList()), StubPortfolio()) { positions }
        val tagRepo = InMemoryTagRepo()
        val service = AssetClassTaggingService(PositionTagService(closed, tagRepo), tagRepo)

        service.ensureTagged(1L)
        service.ensureTagged(1L) // second call must not create a duplicate tag or duplicate assignment

        assertEquals(1, tagRepo.createCalls) // only "Equity" was ever created (no option positions here)
        assertEquals(1, tagRepo.assignments[1L]?.size)
    }
}
