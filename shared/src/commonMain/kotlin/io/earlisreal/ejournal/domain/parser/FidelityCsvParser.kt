package io.earlisreal.ejournal.domain.parser

import io.earlisreal.ejournal.domain.model.Action
import io.earlisreal.ejournal.domain.model.AssetClass
import io.earlisreal.ejournal.domain.model.Transaction
import io.earlisreal.ejournal.domain.model.assetClassOf
import kotlin.math.abs

/**
 * Parses Fidelity's brokerage transaction-history CSV (`History_for_Account_*.csv` / `Accounts_History.csv`).
 * The file has a UTF-8 BOM + two blank preamble lines before the header (handled by `locateHeader`), and a
 * multi-line disclaimer + "Date downloaded ..." footer. Column order varies between exports (Quantity and
 * Price can swap), and money columns are named with or without a "($)" suffix depending on export type
 * (single-account exports use "Price ($)"/"Commission ($)"/"Fees ($)"; the multi-account "Accounts_History"
 * export uses plain "Price"/"Commission"/"Fees") -- everything is read by name, trying the suffixed name
 * first. Trades are `Action` rows containing "YOU BOUGHT" / "YOU SOLD"; non-trade rows (dividends,
 * reinvestments, interest, transfers, contributions, tax, and option expirations/assignments, which have
 * no BOUGHT/SOLD action text) are skipped. Dates are date-only -> midnight.
 *
 * Option legs use Fidelity's leading-dash symbol convention (e.g. `-TNA260731P63` = root TNA, expiry
 * 2026-07-31, Put, strike 63). The app has no dedicated options model (no strike/expiry/right fields, no
 * open/close action), so a leg is stored as an ordinary [Transaction] whose `symbol` is that OCC-like code
 * with the dash stripped, validated via [assetClassOf] first (an unrecognized leading-dash symbol is
 * counted as unparsed rather than guessed at). Fidelity quotes option `Price` per share but each contract
 * covers 100 shares, so `shares` is contracts * 100 to keep FIFO P&L correct -- nothing downstream applies
 * a multiplier.
 *
 * RSU vest rows (`YOU BOUGHT RSU#### AS OF ...`) carry a blank `Price` cell -- Fidelity doesn't surface
 * fair-market-value-at-vest in this export -- so they'd otherwise be dropped as unparsed, silently erasing
 * the cost-basis lot a later sale needs for correct FIFO matching. Treated as a $0-cost buy instead; any
 * other blank-price row is still unparsed rather than guessed at.
 *
 * An option that expires or gets assigned has no BOUGHT/SOLD row of its own and is skipped as non-trade
 * (its opening premium is left unmatched rather than synthesizing a closing trade) -- deliberately, to
 * match Fidelity's own Realized Gain/Loss report, which doesn't count that premium as realized either.
 */
class FidelityCsvParser : TransactionParser {
    override val brokerName = "Fidelity"
    override val supportedExtensions = listOf("csv")

    private fun isHeader(line: String) = line.startsWith("Run Date,") && line.contains("Amount")

    override fun detect(content: ByteArray): Boolean = locateHeader(content, ::isHeader) != null

    override fun parse(content: ByteArray, portfolioId: Long): ParseResult {
        val loc = locateHeader(content, ::isHeader) ?: return ParseResult(emptyList())
        val keys = NaturalKeyFactory("fidelity")
        val txns = mutableListOf<Transaction>()
        var nonTrade = 0
        var unparsed = 0

        for (line in loc.dataLines) {
            if (line.isBlank()) continue
            val c = parseCsvLine(line)
            // Data rows always have a parseable Run Date; the disclaimer/"Date downloaded" footer does not.
            if (parseUsDate(c.field(loc.index, "Run Date").orEmpty()) == null) break
            val rawSymbol = c.field(loc.index, "Symbol").orEmpty()
            val isOption = rawSymbol.startsWith("-") // Fidelity option symbol " -ROOT..."
            val symbol = if (isOption) rawSymbol.removePrefix("-").trim() else rawSymbol
            val action = c.field(loc.index, "Action").orEmpty()
            val dir = when {
                action.contains("YOU BOUGHT", ignoreCase = true) -> Action.BUY
                action.contains("YOU SOLD", ignoreCase = true) -> Action.SELL
                else -> { nonTrade++; continue }
            }
            if (isOption && assetClassOf(symbol) != AssetClass.OPTION) { unparsed++; continue }
            val isRsuVest = dir == Action.BUY && action.contains("RSU", ignoreCase = true)
            val tx = runCatching {
                val contracts = cleanMoney(c.field(loc.index, "Quantity"))
                val price = cleanMoney(c.field(loc.index, "Price ($)") ?: c.field(loc.index, "Price"))
                    ?: if (isRsuVest) 0.0 else null
                val datetime = parseUsDateTime(c.field(loc.index, "Run Date").orEmpty())
                if (symbol.isEmpty() || contracts == null || price == null || datetime == null) return@runCatching null
                val qty = abs(contracts) * if (isOption) 100 else 1
                val fees = (cleanMoney(c.field(loc.index, "Commission ($)") ?: c.field(loc.index, "Commission")) ?: 0.0) +
                    (cleanMoney(c.field(loc.index, "Fees ($)") ?: c.field(loc.index, "Fees")) ?: 0.0)
                Transaction(
                    id = 0L, portfolioId = portfolioId, symbol = symbol, datetime = datetime,
                    action = dir, price = price, shares = qty, fees = fees,
                    externalId = keys.create(symbol, datetime, dir, qty),
                )
            }.getOrNull()
            if (tx != null) txns += tx else unparsed++
        }
        return ParseResult(txns, SkipSummary(nonTrade = nonTrade, unparsed = unparsed))
    }
}
