package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.ClosedPosition
import io.earlisreal.ejournal.domain.model.OpenPosition
import io.earlisreal.ejournal.domain.model.OptionRight
import io.earlisreal.ejournal.domain.model.TradeDirection
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RollChainsTest {

    private val today = LocalDate.parse("2026-08-01")

    private fun closed(
        symbol: String, entry: String, exit: String, pnl: Double = 0.0,
        direction: TradeDirection = TradeDirection.SHORT,
    ) = ClosedPosition(
        symbol = symbol,
        entryDatetime = LocalDateTime.parse(entry), exitDatetime = LocalDateTime.parse(exit),
        averageEntryPrice = 1.0, averageExitPrice = 0.5, shares = 100.0, fees = 0.0,
        profitLoss = pnl, direction = direction,
    )

    private fun open(symbol: String, entry: String, direction: TradeDirection = TradeDirection.SHORT) = OpenPosition(
        symbol = symbol, direction = direction, shares = 100.0, averagePrice = 1.0,
        openDatetime = LocalDateTime.parse(entry),
    )

    @Test
    fun singleLegSequenceIsNotAChain() {
        val chains = detectRollChains(
            closed = listOf(closed("TNA260731P63", "2026-07-01T09:30", "2026-07-15T09:30")),
            open = emptyList(), today = today,
        )
        assertTrue(chains.isEmpty())
    }

    @Test
    fun closeFollowedByNearDayOpenFormsATwoLegChain() {
        val chains = detectRollChains(
            closed = listOf(
                closed("TNA260731P63", "2026-07-01T09:30", "2026-07-15T09:30", pnl = 50.0),
                closed("TNA260814P60", "2026-07-16T09:30", "2026-07-25T09:30", pnl = 30.0),
            ),
            open = emptyList(), today = today,
        )
        val chain = chains.single()
        assertEquals(2, chain.legs.size)
        assertEquals("TNA", chain.root)
        assertEquals(OptionRight.PUT, chain.right)
        assertEquals(80.0, chain.realizedPnl)
        assertEquals(false, chain.isOpen)
    }

    @Test
    fun gapBeyondRollWindowStartsANewChain() {
        val chains = detectRollChains(
            closed = listOf(
                closed("TNA260731P63", "2026-07-01T09:30", "2026-07-15T09:30"),
                closed("TNA260814P60", "2026-07-25T09:30", "2026-08-01T09:30"), // 10 days after prior exit
            ),
            open = emptyList(), today = today, rollWindowDays = 3,
        )
        assertTrue(chains.isEmpty()) // both legs are now standalone (single-leg) sequences
    }

    @Test
    fun differentRightsDoNotChainTogether() {
        val chains = detectRollChains(
            closed = listOf(
                closed("TNA260731P63", "2026-07-01T09:30", "2026-07-15T09:30"),
                closed("TNA260814C60", "2026-07-16T09:30", "2026-07-25T09:30"), // CALL, not PUT
            ),
            open = emptyList(), today = today,
        )
        assertTrue(chains.isEmpty())
    }

    @Test
    fun differentDirectionsDoNotChainTogether() {
        val chains = detectRollChains(
            closed = listOf(
                closed("TNA260731P63", "2026-07-01T09:30", "2026-07-15T09:30", direction = TradeDirection.SHORT),
                closed("TNA260814P60", "2026-07-16T09:30", "2026-07-25T09:30", direction = TradeDirection.LONG),
            ),
            open = emptyList(), today = today,
        )
        assertTrue(chains.isEmpty())
    }

    @Test
    fun openFinalLegMarksChainOpenAndExcludesItFromRealizedPnl() {
        val chains = detectRollChains(
            closed = listOf(closed("TNA260731P63", "2026-07-01T09:30", "2026-07-15T09:30", pnl = 50.0)),
            open = listOf(open("TNA260814P60", "2026-07-16T09:30")),
            today = today,
        )
        val chain = chains.single()
        assertEquals(true, chain.isOpen)
        assertEquals(50.0, chain.realizedPnl) // only the closed leg counts
        assertEquals(null, chain.legs.last().exitDatetime)
    }

    @Test
    fun threeLegChainAccumulatesAllLegs() {
        val chains = detectRollChains(
            closed = listOf(
                closed("TNA260731P63", "2026-07-01T09:30", "2026-07-15T09:30", pnl = 10.0),
                closed("TNA260814P60", "2026-07-16T09:30", "2026-07-30T09:30", pnl = 20.0),
                closed("TNA260828P57", "2026-07-31T09:30", "2026-08-10T09:30", pnl = 30.0),
            ),
            open = emptyList(), today = today,
        )
        val chain = chains.single()
        assertEquals(3, chain.legs.size)
        assertEquals(60.0, chain.realizedPnl)
        assertEquals("$63.00 → $60.00 → $57.00", chain.strikePath)
    }

    @Test
    fun chainIdsAreSequentialPerRootAndRight() {
        val chains = detectRollChains(
            closed = listOf(
                closed("TNA260731P63", "2026-01-01T09:30", "2026-01-05T09:30"),
                closed("TNA260814P60", "2026-01-06T09:30", "2026-01-10T09:30"),
                closed("TNA260828P57", "2026-03-01T09:30", "2026-03-05T09:30"),
                closed("TNA260901P55", "2026-03-06T09:30", "2026-03-10T09:30"),
            ),
            open = emptyList(), today = today,
        )
        assertEquals(2, chains.size)
        assertEquals(setOf("TNA-P01", "TNA-P02"), chains.map { it.id }.toSet())
    }

    @Test
    fun resultsSortedByMostRecentActivityFirst() {
        val chains = detectRollChains(
            closed = listOf(
                closed("TNA260731P63", "2026-01-01T09:30", "2026-01-05T09:30"),
                closed("TNA260814P60", "2026-01-06T09:30", "2026-01-10T09:30"),
                closed("SMCI260724C31.5", "2026-06-01T09:30", "2026-06-05T09:30"),
                closed("SMCI260828C28", "2026-06-06T09:30", "2026-06-10T09:30"),
            ),
            open = emptyList(), today = today,
        )
        assertEquals(listOf("SMCI", "TNA"), chains.map { it.root })
    }
}
