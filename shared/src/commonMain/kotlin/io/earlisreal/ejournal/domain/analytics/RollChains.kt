package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.ClosedPosition
import io.earlisreal.ejournal.domain.model.OpenPosition
import io.earlisreal.ejournal.domain.model.OptionRight
import io.earlisreal.ejournal.domain.model.TradeDirection
import io.earlisreal.ejournal.domain.model.parseOccSymbol
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil

/** One leg of a [RollChain]: a completed round trip, or (only ever the last leg) the still-open one. */
data class RollLeg(
    val symbol: String,
    val strike: Double,
    val expiry: LocalDate,
    val entryDatetime: LocalDateTime,
    /** null if this leg hasn't closed yet -- only possible for the chain's last leg. */
    val exitDatetime: LocalDateTime?,
    /** null (not yet realized) if this leg hasn't closed yet. */
    val profitLoss: Double?,
)

data class RollChain(
    val id: String,
    val root: String,
    val right: OptionRight,
    val direction: TradeDirection,
    val legs: List<RollLeg>,
    val isOpen: Boolean,
    /** Sum of profitLoss across closed legs; the still-open leg (if any) contributes nothing yet. */
    val realizedPnl: Double,
    val daysRunning: Int,
) {
    val strikePath: String get() = legs.joinToString(" → ") { "$${"%.2f".format(it.strike)}" }
    val firstOpen: LocalDateTime get() = legs.first().entryDatetime
    val latestExpiry: LocalDate get() = legs.last().expiry
}

private data class RollCandidate(
    val symbol: String, val strike: Double, val expiry: LocalDate,
    val entry: LocalDateTime, val exit: LocalDateTime?, val pnl: Double?,
    val root: String, val right: OptionRight, val direction: TradeDirection,
)

/**
 * Detects "roll chains": sequences of 2+ option legs on the same underlying + right + direction where
 * each leg's close is followed, within [rollWindowDays], by the next leg's open -- e.g. rolling a short
 * put down and out to a new strike/expiry rather than just closing it outright.
 *
 * This is a heuristic. Fidelity's raw "OPENING TRANSACTION"/"CLOSING TRANSACTION" action text isn't
 * captured on [io.earlisreal.ejournal.domain.model.Transaction] today, so a chain is inferred purely from
 * timing + matching underlying/right/direction -- there's no stronger signal available in current data.
 * A still-open leg can only be a chain's *last* leg (nothing can chain after an unknown close date); if a
 * later candidate appears anyway (e.g. a second, unrelated concurrent position), it starts a fresh chain
 * rather than linking through the open leg. Single-leg sequences are ordinary trades, not rolls, and are
 * excluded here -- they're already visible via [closedOptionsLedger].
 */
fun detectRollChains(
    closed: List<ClosedPosition>,
    open: List<OpenPosition>,
    today: LocalDate,
    rollWindowDays: Int = 3,
): List<RollChain> {
    val candidates = closed.mapNotNull { p ->
        val occ = parseOccSymbol(p.symbol) ?: return@mapNotNull null
        RollCandidate(p.symbol, occ.strike, occ.expiry, p.entryDatetime, p.exitDatetime, p.profitLoss, occ.root, occ.right, p.direction)
    } + open.mapNotNull { p ->
        val occ = parseOccSymbol(p.symbol) ?: return@mapNotNull null
        RollCandidate(p.symbol, occ.strike, occ.expiry, p.openDatetime, null, null, occ.root, occ.right, p.direction)
    }

    val chains = mutableListOf<RollChain>()
    val chainCounters = mutableMapOf<String, Int>()

    for ((key, group) in candidates.groupBy { Triple(it.root, it.right, it.direction) }) {
        val (root, right, direction) = key
        var accumulator = mutableListOf<RollCandidate>()

        fun flush() {
            if (accumulator.size >= 2) {
                val idPrefix = "$root-${right.name.first()}"
                val ordinal = (chainCounters[idPrefix] ?: 0) + 1
                chainCounters[idPrefix] = ordinal
                val legs = accumulator.map { RollLeg(it.symbol, it.strike, it.expiry, it.entry, it.exit, it.pnl) }
                val lastLeg = legs.last()
                // No closing transaction doesn't mean still open -- Fidelity never records one for an
                // expired-worthless or assigned/exercised leg (matches how closedOptionsLedger already
                // treats those). Once expiry has passed there's nothing left to roll, so it's done
                // either way; daysRunning freezes at expiry instead of ticking up forever unclosed.
                val stillOpen = lastLeg.exitDatetime == null && lastLeg.expiry >= today
                val lastActivity = lastLeg.exitDatetime?.date ?: minOf(lastLeg.expiry, today)
                chains += RollChain(
                    id = "$idPrefix${ordinal.toString().padStart(2, '0')}",
                    root = root, right = right, direction = direction, legs = legs,
                    isOpen = stillOpen,
                    realizedPnl = legs.mapNotNull { it.profitLoss }.sum(),
                    daysRunning = legs.first().entryDatetime.date.daysUntil(lastActivity),
                )
            }
            accumulator = mutableListOf()
        }

        for (candidate in group.sortedBy { it.entry }) {
            val lastExit = accumulator.lastOrNull()?.exit
            val continuesChain = accumulator.isNotEmpty() && lastExit != null &&
                lastExit.date.daysUntil(candidate.entry.date) in 0..rollWindowDays
            if (accumulator.isNotEmpty() && !continuesChain) flush()
            accumulator.add(candidate)
        }
        flush()
    }

    return chains.sortedByDescending { it.legs.last().entryDatetime }
}
