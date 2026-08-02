package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.ClosedPosition
import io.earlisreal.ejournal.domain.model.OptionRight
import io.earlisreal.ejournal.domain.model.parseOccSymbol
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/** One closed option position, decoded from its OCC-like symbol for display. */
data class ClosedOption(
    val position: ClosedPosition,
    val root: String,
    val right: OptionRight,
    val strike: Double,
    val expiry: LocalDate,
    val contracts: Double,
    /** entryDate.daysUntil(exitDate); 0 for a same-day (0DTE) trade. */
    val daysInTrade: Int,
    /** profitLoss / max(daysInTrade, 1) -- a same-day trade is treated as a 1-day hold to avoid /0. */
    val profitPerDay: Double,
)

/**
 * Filters [positions] to option legs (via [parseOccSymbol]) and decorates each with strike/expiry/
 * days-in-trade/profit-per-day. Non-option symbols are dropped -- this is a closed-options-only view;
 * equities show up in the monthly/underlying rollups instead. Most-recently-closed first.
 */
fun closedOptionsLedger(positions: List<ClosedPosition>): List<ClosedOption> =
    positions.mapNotNull { p ->
        val occ = parseOccSymbol(p.symbol) ?: return@mapNotNull null
        val days = p.entryDatetime.date.daysUntil(p.exitDatetime.date)
        ClosedOption(
            position = p, root = occ.root, right = occ.right, strike = occ.strike, expiry = occ.expiry,
            contracts = p.shares / 100,
            daysInTrade = days,
            profitPerDay = p.profitLoss / days.coerceAtLeast(1),
        )
    }.sortedByDescending { it.position.exitDatetime }
