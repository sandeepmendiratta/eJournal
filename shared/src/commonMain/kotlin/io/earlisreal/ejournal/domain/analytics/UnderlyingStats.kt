package io.earlisreal.ejournal.domain.analytics

import io.earlisreal.ejournal.domain.model.ClosedPosition
import io.earlisreal.ejournal.domain.model.parseOccSymbol

data class UnderlyingStat(val root: String, val netPnl: Double, val tradeCount: Int)

/**
 * Groups closed positions (stocks and options together) by underlying root -- an option leg like
 * "TNA260731P63" rolls up under "TNA" via [parseOccSymbol], the same key the stock "TNA" itself uses.
 * Ordered by net P&L descending.
 */
fun underlyingStats(positions: List<ClosedPosition>): List<UnderlyingStat> =
    positions.groupBy { parseOccSymbol(it.symbol)?.root ?: it.symbol }
        .map { (root, ps) -> UnderlyingStat(root, ps.sumOf { it.profitLoss }, ps.size) }
        .sortedByDescending { it.netPnl }
