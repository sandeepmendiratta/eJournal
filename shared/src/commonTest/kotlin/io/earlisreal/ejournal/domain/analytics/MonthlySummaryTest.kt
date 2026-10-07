package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.ClosedPosition
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonthlySummaryTest {

    private fun pos(symbol: String, exit: String, pnl: Double) = ClosedPosition(
        symbol = symbol,
        entryDatetime = LocalDateTime.parse(exit),
        exitDatetime = LocalDateTime.parse(exit),
        averageEntryPrice = 1.0, averageExitPrice = 1.0,
        shares = 1.0, fees = 0.0, profitLoss = pnl,
    )

    @Test
    fun emptyInputYieldsEmptyList() {
        assertTrue(monthlySummaries(emptyList()).isEmpty())
    }

    @Test
    fun multipleTradesSameMonthSumAndCount() {
        val summaries = monthlySummaries(
            listOf(
                pos("AAPL", "2026-03-01T09:00", 100.0),
                pos("AAPL", "2026-03-15T09:00", -20.0),
            ),
        )
        val march = summaries.single()
        assertEquals(YearMonth(2026, 3), march.month)
        assertEquals(80.0, march.netPnl)
        assertEquals(2, march.tradeCount)
    }

    @Test
    fun distinctMonthsAreSeparateEntries() {
        val summaries = monthlySummaries(
            listOf(
                pos("AAPL", "2026-03-01T09:00", 100.0),
                pos("AAPL", "2026-04-01T09:00", 50.0),
            ),
        )
        assertEquals(2, summaries.size)
    }

    @Test
    fun sortedMostRecentMonthFirst() {
        val summaries = monthlySummaries(
            listOf(
                pos("AAPL", "2026-02-01T09:00", 1.0),
                pos("AAPL", "2026-05-01T09:00", 1.0),
                pos("AAPL", "2026-03-01T09:00", 1.0),
            ),
        )
        assertEquals(listOf(YearMonth(2026, 5), YearMonth(2026, 3), YearMonth(2026, 2)), summaries.map { it.month })
    }

    @Test
    fun respectsYearBoundaryNotJustMonthNumber() {
        val summaries = monthlySummaries(
            listOf(
                pos("AAPL", "2025-12-01T09:00", 1.0),
                pos("AAPL", "2026-01-01T09:00", 1.0),
            ),
        )
        // Jan 2026 is more recent than Dec 2025, even though month-number 1 < 12.
        assertEquals(listOf(YearMonth(2026, 1), YearMonth(2025, 12)), summaries.map { it.month })
    }

    @Test
    fun mixesEquityAndOptionSymbolsInTheSameMonth() {
        val summaries = monthlySummaries(
            listOf(
                pos("AAPL", "2026-03-01T09:00", 10.0),
                pos("TNA260731P63", "2026-03-05T09:00", 20.0),
            ),
        )
        val march = summaries.single()
        assertEquals(2, march.tradeCount)
        assertEquals(30.0, march.netPnl)
        assertEquals(10.0, march.equityPnl)
        assertEquals(20.0, march.optionsPnl)
    }

    @Test
    fun equityAndOptionsPnlSplitIndependentlyOfSign() {
        val summaries = monthlySummaries(
            listOf(
                pos("AAPL", "2026-03-01T09:00", -15.0),
                pos("MSFT", "2026-03-02T09:00", 5.0),
                pos("TNA260731P63", "2026-03-05T09:00", 40.0),
                pos("TNA260814C60", "2026-03-06T09:00", -10.0),
            ),
        )
        val march = summaries.single()
        assertEquals(-10.0, march.equityPnl)
        assertEquals(30.0, march.optionsPnl)
        assertEquals(20.0, march.netPnl)
    }
}
