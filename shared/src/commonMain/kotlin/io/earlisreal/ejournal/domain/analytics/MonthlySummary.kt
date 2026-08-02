package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.ClosedPosition

/** A calendar month key -- avoids java.time.YearMonth, which isn't available in commonMain. */
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    override fun compareTo(other: YearMonth) = compareValuesBy(this, other, YearMonth::year, YearMonth::month)
}

data class MonthlySummary(val month: YearMonth, val netPnl: Double, val tradeCount: Int)

/**
 * Groups closed positions (stocks and options together) by the calendar month of their exit date.
 * Most-recent month first.
 */
fun monthlySummaries(positions: List<ClosedPosition>): List<MonthlySummary> =
    positions.groupBy { YearMonth(it.exitDatetime.date.year, it.exitDatetime.date.monthNumber) }
        .map { (month, ps) -> MonthlySummary(month, ps.sumOf { it.profitLoss }, ps.size) }
        .sortedByDescending { it.month }
