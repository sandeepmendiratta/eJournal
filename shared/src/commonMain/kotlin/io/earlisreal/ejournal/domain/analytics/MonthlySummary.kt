package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.AssetClass
import io.earlisreal.ejournal.domain.model.ClosedPosition
import io.earlisreal.ejournal.domain.model.assetClassOf

/** A calendar month key -- avoids java.time.YearMonth, which isn't available in commonMain. */
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    override fun compareTo(other: YearMonth) = compareValuesBy(this, other, YearMonth::year, YearMonth::month)
}

data class MonthlySummary(
    val month: YearMonth,
    val netPnl: Double,
    val tradeCount: Int,
    val equityPnl: Double,
    val optionsPnl: Double,
)

/**
 * Groups closed positions (stocks and options together) by the calendar month of their exit date, also
 * splitting each month's net P&L by asset class so it's visible whether a month's profit came from
 * equity trades, options, or both. Most-recent month first.
 */
fun monthlySummaries(positions: List<ClosedPosition>): List<MonthlySummary> =
    positions.groupBy { YearMonth(it.exitDatetime.date.year, it.exitDatetime.date.monthNumber) }
        .map { (month, ps) ->
            val (options, equity) = ps.partition { assetClassOf(it.symbol) == AssetClass.OPTION }
            MonthlySummary(
                month = month,
                netPnl = ps.sumOf { it.profitLoss },
                tradeCount = ps.size,
                equityPnl = equity.sumOf { it.profitLoss },
                optionsPnl = options.sumOf { it.profitLoss },
            )
        }
        .sortedByDescending { it.month }
