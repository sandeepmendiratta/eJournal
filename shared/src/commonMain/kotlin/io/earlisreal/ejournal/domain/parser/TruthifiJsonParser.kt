package io.earlisreal.ejournal.domain.parser

import io.earlisreal.ejournal.domain.model.Action
import io.earlisreal.ejournal.domain.model.Transaction
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Parses a Truthifi MCP `get_transactions` export (an array of raw transaction objects, matching that
 * tool's own output schema). Truthifi is MCP-only -- no direct developer API -- so the app can never
 * call it itself; this exists so a Claude-pulled export goes through the same dedup/business-rule
 * pipeline as every CSV broker (`TransactionParser` + [NaturalKeyFactory]), rather than a bespoke
 * one-off script.
 *
 * `transactionType` gives explicit buy/sell/non-trade semantics no CSV broker exposes -- no
 * "YOU BOUGHT"/"YOU SOLD" text scraping needed. One real gap: a closing trade still pending settlement
 * arrives as a generic `"memo"` type with no `security` object (only free-text `description`); those
 * are counted as non-trade here rather than text-parsed, and a later sync picks them up correctly once
 * Truthifi resolves them into a real `buy_to_close`/`sell_to_close`.
 *
 * Option legs use Truthifi's standard fixed-width, space-padded OCC symbol (e.g.
 * `"NVDA  270115C00100000"` = root, YYMMDD, C/P, strike*1000), converted to this app's compact OCC form
 * (`"NVDA270115C100"`) so [io.earlisreal.ejournal.domain.model.assetClassOf]/[io.earlisreal.ejournal.domain.model.parseOccSymbol]
 * and every options report work identically regardless of source.
 */
class TruthifiJsonParser : TransactionParser {
    override val brokerName = "Truthifi"
    override val supportedExtensions = listOf("json")

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // Fidelity's own manual CSV export excludes core-account cash-sweep activity because its
        // free-text Action column ("PURCHASE INTO CORE ACCOUNT", "REDEMPTION FROM CORE ACCOUNT") never
        // matches the "YOU BOUGHT"/"YOU SOLD" filter real trades use. Truthifi's transactionType field
        // has no such distinction -- a sweep purchase and a real trade both just say "buy" -- so this
        // filters on securityType instead, keyed off real observed data (FCASH/SPAXX sweep buys, sells,
        // and dividend reinvestments all carry one of these two types).
        private val CASH_SWEEP_TYPES = setOf("sweep_account", "money_market_fund")
    }

    override fun detect(content: ByteArray): Boolean = decode(content) != null

    override fun parse(content: ByteArray, portfolioId: Long): ParseResult {
        val rows = decode(content) ?: return ParseResult(emptyList())
        val keys = NaturalKeyFactory("truthifi")
        val txns = mutableListOf<Transaction>()
        var nonTrade = 0
        var unparsed = 0

        for (row in rows) {
            val dir = actionOf(row.transactionType)
            if (dir == null) { nonTrade++; continue }
            val security = row.security
            if (security != null && security.securityType in CASH_SWEEP_TYPES) { nonTrade++; continue }
            val symbol = security?.let { occSymbol(it) }
            if (symbol == null) { unparsed++; continue }
            val tx = runCatching {
                val qty = row.quantity ?: return@runCatching null
                val price = row.price ?: return@runCatching null
                val datetime = LocalDateTime(LocalDate.parse(row.date), LocalTime(0, 0))
                val shares = abs(qty)
                Transaction(
                    id = 0L, portfolioId = portfolioId, symbol = symbol, datetime = datetime,
                    action = dir, price = price, shares = shares, fees = row.fees ?: 0.0,
                    externalId = keys.create(symbol, datetime, dir, shares),
                )
            }.getOrNull()
            if (tx != null) txns += tx else unparsed++
        }
        return ParseResult(txns, SkipSummary(nonTrade = nonTrade, unparsed = unparsed))
    }

    private fun decode(content: ByteArray): List<TruthifiTransaction>? =
        runCatching { json.decodeFromString<List<TruthifiTransaction>>(content.decodeToString()) }.getOrNull()

    private fun actionOf(type: String): Action? = when (type) {
        "buy", "buy_to_cover", "buy_to_close", "buy_to_open", "assign_buy_shares", "exercise_buy_shares",
        "dividend_reinvestment", "capital_gains_reinvestment", "interest_reinvestment",
        "long_term_capital_gains_reinvestment", "short_term_capital_gains_reinvestment",
        -> Action.BUY
        "sell", "sell_short", "sell_to_open", "sell_to_close", "assign_sell_shares", "exercise_sell_shares",
        -> Action.SELL
        else -> null
    }

    /** Equity/ETF symbols pass through as-is; option symbols convert to this app's compact OCC form. */
    private fun occSymbol(security: TruthifiSecurity): String? {
        val raw = security.handle.symbol
        if (security.securityType != "option") return raw.trim().takeIf { it.isNotEmpty() }
        if (raw.length < 21) return null
        val root = raw.substring(0, 6).trim()
        val date = raw.substring(6, 12)
        val right = raw.substring(12, 13)
        val strike = raw.substring(13, 21).toLongOrNull()?.let { it / 1000.0 } ?: return null
        if (root.isEmpty() || (right != "C" && right != "P")) return null
        val strikeStr = strike.toBigDecimal().stripTrailingZeros().toPlainString()
        return "$root$date$right$strikeStr"
    }

    @Serializable
    private data class TruthifiTransaction(
        val date: String,
        val transactionType: String,
        val quantity: Double? = null,
        val price: Double? = null,
        val fees: Double? = null,
        val security: TruthifiSecurity? = null,
    )

    @Serializable
    private data class TruthifiSecurity(val securityType: String? = null, val handle: TruthifiHandle)

    @Serializable
    private data class TruthifiHandle(val symbol: String)
}
