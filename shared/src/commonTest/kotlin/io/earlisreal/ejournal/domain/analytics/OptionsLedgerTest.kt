package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.ClosedPosition
import io.earlisreal.ejournal.domain.model.OptionRight
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptionsLedgerTest {

    private fun pos(
        symbol: String,
        entry: String,
        exit: String,
        pnl: Double = 0.0,
        shares: Double = 100.0,
        entryPrice: Double = 1.0,
        exitPrice: Double = 0.0,
    ) = ClosedPosition(
        symbol = symbol,
        entryDatetime = LocalDateTime.parse(entry),
        exitDatetime = LocalDateTime.parse(exit),
        averageEntryPrice = entryPrice, averageExitPrice = exitPrice,
        shares = shares, fees = 0.0, profitLoss = pnl,
    )

    @Test
    fun emptyInputYieldsEmptyLedger() {
        assertTrue(closedOptionsLedger(emptyList()).isEmpty())
    }

    @Test
    fun equitySymbolsAreExcluded() {
        val positions = listOf(pos("AAPL", "2026-01-01T09:00", "2026-01-05T15:00"))
        assertTrue(closedOptionsLedger(positions).isEmpty())
    }

    @Test
    fun decodesRootRightStrikeExpiryFromOccSymbol() {
        val ledger = closedOptionsLedger(listOf(pos("TNA260731P63", "2026-07-01T09:00", "2026-07-15T15:00")))
        val o = ledger.single()
        assertEquals("TNA", o.root)
        assertEquals(OptionRight.PUT, o.right)
        assertEquals(63.0, o.strike)
        assertEquals(kotlinx.datetime.LocalDate(2026, 7, 31), o.expiry)
    }

    @Test
    fun sameDayTradeHasZeroDaysInTradeAndProfitPerDayEqualsFullPnl() {
        val ledger = closedOptionsLedger(
            listOf(pos("TNA260731P63", "2026-07-15T09:30", "2026-07-15T15:00", pnl = 42.0)),
        )
        val o = ledger.single()
        assertEquals(0, o.daysInTrade)
        assertEquals(42.0, o.profitPerDay)
    }

    @Test
    fun multiDayTradeComputesProfitPerDay() {
        val ledger = closedOptionsLedger(
            listOf(pos("TNA260731P63", "2026-07-01T09:30", "2026-07-05T15:00", pnl = 100.0)),
        )
        val o = ledger.single()
        assertEquals(4, o.daysInTrade)
        assertEquals(25.0, o.profitPerDay)
    }

    @Test
    fun contractsDerivedFromSharesDividedBy100() {
        val ledger = closedOptionsLedger(
            listOf(pos("TNA260731P63", "2026-07-01T09:30", "2026-07-05T15:00", shares = 200.0)),
        )
        assertEquals(2.0, ledger.single().contracts)
    }

    @Test
    fun orderedByExitDatetimeDescending() {
        val ledger = closedOptionsLedger(
            listOf(
                pos("TNA260731P63", "2026-07-01T09:30", "2026-07-05T15:00"),
                pos("SMCI260724C31.5", "2026-07-01T09:30", "2026-07-20T15:00"),
                pos("UMAC260501P13.5", "2026-04-01T09:30", "2026-04-10T15:00"),
            ),
        )
        assertEquals(listOf("SMCI260724C31.5", "TNA260731P63", "UMAC260501P13.5"), ledger.map { it.position.symbol })
    }
}
