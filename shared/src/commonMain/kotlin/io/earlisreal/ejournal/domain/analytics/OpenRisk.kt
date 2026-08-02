package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.OpenPosition
import io.earlisreal.ejournal.domain.model.TradeDirection
import io.earlisreal.ejournal.domain.model.parseOccSymbol
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus

/**
 * One open position's contribution to forward-looking risk, reduced to a single dollar figure whose
 * meaning depends on structure -- a blanket "shares * price" notional would either wildly understate a
 * short put's real commitment or wildly overstate a long option's real downside:
 *  - Equity/ETF: mark-to-market value (shares * current price, falling back to cost basis if no live
 *    quote) -- the capital actually sitting in the position.
 *  - Short option (covered call or cash-secured/naked put): strike-based notional (contracts * 100 *
 *    strike) -- the capital or shares you're on the hook for if assigned, which is the real commitment
 *    size, not today's mark.
 *  - Long option: premium paid (contracts * 100 * average price) -- the actual maximum loss, since a
 *    long option can never lose more than what it cost, unlike its much larger notional "control" size.
 *
 * An option position whose expiry has already passed is excluded -- same reasoning as roll chains:
 * Fidelity never records a closing transaction for a leg that expired worthless or was assigned/
 * exercised, so a still-unmatched position past its own expiry isn't real forward risk anymore.
 */
data class OpenRisk(
    val symbol: String,
    val root: String,
    val riskAmount: Double,
    /** null for equity/ETF -- only options carry an expiry-driven risk window. */
    val expiry: LocalDate?,
)

data class UnderlyingExposure(val root: String, val riskAmount: Double, val positionCount: Int)

data class WeeklyExposure(val weekStart: LocalDate, val riskAmount: Double, val positionCount: Int)

fun openRisk(positions: List<OpenPosition>, currentPrices: Map<String, Double>, today: LocalDate): List<OpenRisk> =
    positions.mapNotNull { pos ->
        val occ = parseOccSymbol(pos.symbol)
        if (occ == null) {
            val price = currentPrices[pos.symbol] ?: pos.averagePrice
            OpenRisk(pos.symbol, pos.symbol, pos.shares * price, expiry = null)
        } else {
            if (occ.expiry < today) return@mapNotNull null
            val contracts = pos.shares / 100
            val amount = if (pos.direction == TradeDirection.SHORT) {
                contracts * 100 * occ.strike
            } else {
                contracts * 100 * pos.averagePrice
            }
            OpenRisk(pos.symbol, occ.root, amount, occ.expiry)
        }
    }

/** Grouped by underlying root (an option leg rolls up under its stock, same convention as [underlyingStats]), sorted by risk descending. */
fun exposureByUnderlying(risks: List<OpenRisk>): List<UnderlyingExposure> =
    risks.groupBy { it.root }
        .map { (root, group) -> UnderlyingExposure(root, group.sumOf { it.riskAmount }, group.size) }
        .sortedByDescending { it.riskAmount }

/** Options only, grouped by the Monday of their expiry's calendar week, soonest first. */
fun exposureByExpiryWeek(risks: List<OpenRisk>): List<WeeklyExposure> =
    risks.filter { it.expiry != null }
        .groupBy { mondayOf(it.expiry!!) }
        .map { (weekStart, group) -> WeeklyExposure(weekStart, group.sumOf { it.riskAmount }, group.size) }
        .sortedBy { it.weekStart }

private fun mondayOf(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
