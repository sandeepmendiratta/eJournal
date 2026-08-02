package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.ClosedPosition
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnderlyingStatsTest {

    private fun pos(symbol: String, pnl: Double) = ClosedPosition(
        symbol = symbol,
        entryDatetime = LocalDateTime.parse("2026-03-01T09:00"),
        exitDatetime = LocalDateTime.parse("2026-03-01T15:00"),
        averageEntryPrice = 1.0, averageExitPrice = 1.0,
        shares = 1.0, fees = 0.0, profitLoss = pnl,
    )

    @Test
    fun emptyPositionsYieldNoStats() {
        assertTrue(underlyingStats(emptyList()).isEmpty())
    }

    @Test
    fun equitySymbolGroupsByItself() {
        val stats = underlyingStats(listOf(pos("TNA", 50.0)))
        assertEquals(UnderlyingStat("TNA", 50.0, 1), stats.single())
    }

    @Test
    fun optionLegRollsUpUnderStockRoot() {
        val stats = underlyingStats(listOf(pos("TNA260731P63", 30.0), pos("TNA", 20.0)))
        val tna = stats.single { it.root == "TNA" }
        assertEquals(50.0, tna.netPnl)
        assertEquals(2, tna.tradeCount)
    }

    @Test
    fun multipleOptionLegsSameRootAggregate() {
        val stats = underlyingStats(listOf(pos("TNA260731P63", 10.0), pos("TNA260814P62", 15.0)))
        val tna = stats.single { it.root == "TNA" }
        assertEquals(25.0, tna.netPnl)
        assertEquals(2, tna.tradeCount)
    }

    @Test
    fun sortedByNetPnlDescending() {
        val stats = underlyingStats(listOf(pos("AAPL", 10.0), pos("MSFT", 50.0), pos("TSLA", -5.0)))
        assertEquals(listOf("MSFT", "AAPL", "TSLA"), stats.map { it.root })
    }
}
