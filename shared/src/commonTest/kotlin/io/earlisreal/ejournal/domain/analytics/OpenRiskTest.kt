package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.OpenPosition
import io.earlisreal.ejournal.domain.model.TradeDirection
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenRiskTest {

    private val today = LocalDate.parse("2026-08-01")

    private fun equity(symbol: String, shares: Double, avgPrice: Double, direction: TradeDirection = TradeDirection.LONG) =
        OpenPosition(symbol, direction, shares, avgPrice, LocalDateTime.parse("2026-07-01T09:30"))

    private fun option(symbol: String, contracts: Double, avgPrice: Double, direction: TradeDirection) =
        OpenPosition(symbol, direction, contracts * 100, avgPrice, LocalDateTime.parse("2026-07-01T09:30"))

    @Test
    fun emptyPositionsYieldNoRisk() {
        assertTrue(openRisk(emptyList(), emptyMap(), today).isEmpty())
    }

    @Test
    fun equityRiskIsMarkToMarketAtCurrentPrice() {
        val risk = openRisk(listOf(equity("AAPL", 100.0, 150.0)), mapOf("AAPL" to 200.0), today).single()
        assertEquals("AAPL", risk.root)
        assertEquals(20000.0, risk.riskAmount)
        assertEquals(null, risk.expiry)
    }

    @Test
    fun equityFallsBackToCostBasisWithoutALivePrice() {
        val risk = openRisk(listOf(equity("AAPL", 100.0, 150.0)), emptyMap(), today).single()
        assertEquals(15000.0, risk.riskAmount)
    }

    @Test
    fun shortOptionRiskIsStrikeBasedNotional() {
        // TNA260814P63 -> strike 63, 1 contract, expires after "today"
        val risk = openRisk(listOf(option("TNA260814P63", 1.0, 2.0, TradeDirection.SHORT)), emptyMap(), today).single()
        assertEquals("TNA", risk.root)
        assertEquals(6300.0, risk.riskAmount) // 100 * 63, not the current/average price
    }

    @Test
    fun longOptionRiskIsPremiumPaid() {
        val risk = openRisk(listOf(option("TNA260814P63", 2.0, 3.5, TradeDirection.LONG)), emptyMap(), today).single()
        assertEquals(700.0, risk.riskAmount) // 2 contracts * 100 * 3.5 premium, not strike-based
    }

    @Test
    fun optionPastExpiryIsExcluded() {
        // TNA260731P63 expires 2026-07-31, before "today" (2026-08-01) -- expired/assigned, not real risk.
        val risks = openRisk(listOf(option("TNA260731P63", 1.0, 2.0, TradeDirection.SHORT)), emptyMap(), today)
        assertTrue(risks.isEmpty())
    }

    @Test
    fun optionExpiringTodayIsStillIncluded() {
        val onExpiry = LocalDate.parse("2026-07-31")
        val risks = openRisk(listOf(option("TNA260731P63", 1.0, 2.0, TradeDirection.SHORT)), emptyMap(), onExpiry)
        assertEquals(1, risks.size)
    }

    @Test
    fun exposureByUnderlyingCombinesEquityAndOptionsOnTheSameRootSortedDescending() {
        val risks = openRisk(
            listOf(
                equity("TNA", 100.0, 50.0), // 5000
                option("TNA260814P63", 1.0, 2.0, TradeDirection.SHORT), // 6300
                equity("AAPL", 10.0, 200.0), // 2000
            ),
            emptyMap(), today,
        )
        val exposure = exposureByUnderlying(risks)
        assertEquals(listOf("TNA", "AAPL"), exposure.map { it.root })
        assertEquals(11300.0, exposure.first().riskAmount)
        assertEquals(2, exposure.first().positionCount)
    }

    @Test
    fun exposureByExpiryWeekGroupsOptionsOnlyByMondayOfWeekAscending() {
        val risks = openRisk(
            listOf(
                equity("AAPL", 10.0, 200.0), // no expiry, excluded from weekly grouping
                option("TNA260814P63", 1.0, 2.0, TradeDirection.SHORT), // Fri 2026-08-14 -> week of Mon 2026-08-10
                option("TNA260817P63", 1.0, 2.0, TradeDirection.SHORT), // Mon 2026-08-17 -> week of Mon 2026-08-17
            ),
            emptyMap(), today,
        )
        val weeks = exposureByExpiryWeek(risks)
        assertEquals(listOf(LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-17")), weeks.map { it.weekStart })
        assertEquals(1, weeks[0].positionCount)
    }
}
