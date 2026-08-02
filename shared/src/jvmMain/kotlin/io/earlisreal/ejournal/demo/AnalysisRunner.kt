package io.earlisreal.ejournal.demo

import io.earlisreal.ejournal.data.SqlDelightPortfolioRepository
import io.earlisreal.ejournal.data.SqlDelightTransactionRepository
import io.earlisreal.ejournal.data.database.JvmDatabaseFactory
import io.earlisreal.ejournal.domain.FifoMatcher
import io.earlisreal.ejournal.domain.analytics.closedOptionsLedger
import io.earlisreal.ejournal.domain.analytics.monthlySummaries
import io.earlisreal.ejournal.domain.analytics.underlyingStats
import io.earlisreal.ejournal.domain.model.AssetClass
import io.earlisreal.ejournal.domain.model.assetClassOf
import kotlinx.coroutines.runBlocking

/**
 * Headless read-only analysis dump: `generate-csv analyze <portfolio name> [year]`. Reuses the app's
 * own FIFO matching + analytics functions (same ones Reports uses) rather than re-deriving P&L, so the
 * numbers always match what's on screen. Prints a plain-text summary to stdout for ad hoc review.
 */
fun runAnalysis(args: Array<String>) {
    val portfolioName = args.getOrNull(0) ?: "fidelity"
    val year = args.getOrNull(1)?.toIntOrNull()

    val db = JvmDatabaseFactory.create()
    val portfolioRepository = SqlDelightPortfolioRepository(db)
    val transactionRepository = SqlDelightTransactionRepository(db)

    runBlocking {
        val portfolio = portfolioRepository.getAll().find { it.name.equals(portfolioName, ignoreCase = true) }
        if (portfolio == null) {
            println("No portfolio named \"$portfolioName\".")
            return@runBlocking
        }

        val txs = transactionRepository.getByPortfolio(portfolio.id)
        val allClosed = FifoMatcher.computeClosedPositions(txs)
        val closed = if (year != null) allClosed.filter { it.exitDatetime.date.year == year } else allClosed

        if (closed.isEmpty()) {
            println("No closed positions${if (year != null) " in $year" else ""}.")
            return@runBlocking
        }

        val (options, equity) = closed.partition { assetClassOf(it.symbol) == AssetClass.OPTION }

        println("=== Overview${if (year != null) " ($year)" else ""} ===")
        printBucketStats("All closed positions", closed)
        printBucketStats("Options", options)
        printBucketStats("Equity/ETF", equity)

        val ledger = closedOptionsLedger(closed)
        println("\n=== Options by right ===")
        for ((right, group) in ledger.groupBy { it.right }) {
            printBucketStats(right.name, group.map { it.position })
            val avgDays = group.map { it.daysInTrade }.average()
            val avgPerDay = group.map { it.profitPerDay }.average()
            println("  avg days in trade: %.1f, avg P/L per day: $%.2f".format(avgDays, avgPerDay))
        }

        println("\n=== Options by direction (short = premium sold, long = premium paid) ===")
        for ((dir, group) in ledger.groupBy { it.position.direction }) {
            printBucketStats(dir.name, group.map { it.position })
        }

        println("\n=== Monthly P/L ===")
        for (m in monthlySummaries(closed).sortedBy { it.month }) {
            println("  ${m.month.year}-%02d: $%.2f (%d trades)".format(m.month.month, m.netPnl, m.tradeCount))
        }

        println("\n=== Top underlyings by net P/L ===")
        val byUnderlying = underlyingStats(closed)
        println("  Winners:")
        byUnderlying.filter { it.netPnl > 0 }.take(10).forEach {
            println("    ${it.root}: $%.2f (%d trades)".format(it.netPnl, it.tradeCount))
        }
        println("  Losers:")
        byUnderlying.filter { it.netPnl < 0 }.sortedBy { it.netPnl }.take(10).forEach {
            println("    ${it.root}: $%.2f (%d trades)".format(it.netPnl, it.tradeCount))
        }

        println("\n=== Days-in-trade distribution (options) ===")
        val daysBuckets = ledger.groupBy {
            when {
                it.daysInTrade == 0 -> "0DTE/same-day"
                it.daysInTrade <= 7 -> "1-7 days"
                it.daysInTrade <= 30 -> "8-30 days"
                it.daysInTrade <= 90 -> "31-90 days"
                else -> "90+ days"
            }
        }
        for ((bucket, group) in daysBuckets) {
            val net = group.sumOf { it.position.profitLoss }
            println("  $bucket: %d trades, $%.2f net".format(group.size, net))
        }
    }
}

private fun printBucketStats(label: String, positions: List<io.earlisreal.ejournal.domain.model.ClosedPosition>) {
    if (positions.isEmpty()) {
        println("$label: no trades")
        return
    }
    val wins = positions.filter { it.profitLoss > 0 }
    val losses = positions.filter { it.profitLoss < 0 }
    val netPnl = positions.sumOf { it.profitLoss }
    val winRate = wins.size.toDouble() / positions.size * 100
    val avgWin = if (wins.isNotEmpty()) wins.sumOf { it.profitLoss } / wins.size else 0.0
    val avgLoss = if (losses.isNotEmpty()) losses.sumOf { it.profitLoss } / losses.size else 0.0
    val grossWin = wins.sumOf { it.profitLoss }
    val grossLoss = -losses.sumOf { it.profitLoss }
    val profitFactor = if (grossLoss > 0) grossWin / grossLoss else Double.POSITIVE_INFINITY
    println(
        "%s: %d trades, net $%.2f, win rate %.1f%% (%d/%d), avg win $%.2f, avg loss $%.2f, profit factor %.2f".format(
            label, positions.size, netPnl, winRate, wins.size, positions.size, avgWin, avgLoss, profitFactor,
        )
    )
}
