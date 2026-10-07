package io.earlisreal.ejournal.domain.parser

import io.earlisreal.ejournal.domain.model.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TruthifiJsonParserTest {

    private val parser = TruthifiJsonParser()
    private val portfolioId = 10L

    private fun json(vararg rows: String) = "[${rows.joinToString(",")}]".encodeToByteArray()

    private val equityBuy = """
        {"date":"2026-07-31","transactionType":"buy","quantity":100,"price":41.521,"fees":0.09,
         "security":{"securityType":"etf","handle":{"symbol":"AMZU"}}}
    """.trimIndent()

    private val equitySell = """
        {"date":"2026-07-31","transactionType":"sell","quantity":400,"price":1.681,"fees":0.02,
         "security":{"securityType":"equity","handle":{"symbol":"OKYO"}}}
    """.trimIndent()

    private val optionSellToOpen = """
        {"date":"2026-07-31","transactionType":"sell_to_open","quantity":1,"price":6.72,"fees":0.68,
         "security":{"securityType":"option","handle":{"symbol":"RDDT  270115P00095000"}}}
    """.trimIndent()

    private val optionBuyToCloseFractionalStrike = """
        {"date":"2026-07-31","transactionType":"buy_to_close","quantity":1,"price":2.97,"fees":0.66,
         "security":{"securityType":"option","handle":{"symbol":"TQQQ  260814P00062500"}}}
    """.trimIndent()

    private val dividend = """
        {"date":"2026-07-31","transactionType":"dividend_payment","fees":null,
         "security":{"securityType":"equity","handle":{"symbol":"DELL"}}}
    """.trimIndent()

    private val pendingMemo = """
        {"date":"2026-07-31","transactionType":"memo","price":2.32,"fees":0.66}
    """.trimIndent()

    private val cashSweepBuy = """
        {"date":"2026-07-31","transactionType":"buy","quantity":14579.56,"price":1,
         "security":{"securityType":"sweep_account","handle":{"symbol":"FCASH"}}}
    """.trimIndent()

    private val cashSweepReinvestment = """
        {"date":"2026-07-31","transactionType":"dividend_reinvestment","quantity":87.68,"price":1,
         "security":{"securityType":"money_market_fund","handle":{"symbol":"SPAXX"}}}
    """.trimIndent()

    private val cashSweepSell = """
        {"date":"2026-07-27","transactionType":"sell","quantity":1600.38,"price":1,
         "security":{"securityType":"money_market_fund","handle":{"symbol":"SPAXX"}}}
    """.trimIndent()

    @Test
    fun detectsWellFormedJsonArray() {
        assertTrue(parser.detect(json(equityBuy)))
    }

    @Test
    fun rejectsNonJsonOrWrongShape() {
        assertFalse(parser.detect("not json".encodeToByteArray()))
        assertFalse(parser.detect("{\"not\":\"an array\"}".encodeToByteArray()))
    }

    @Test
    fun parsesEquityBuyAndSell() {
        val r = parser.parse(json(equityBuy, equitySell), portfolioId)
        assertEquals(2, r.transactions.size)
        val buy = r.transactions.single { it.action == Action.BUY }
        assertEquals("AMZU", buy.symbol)
        assertEquals(100.0, buy.shares)
        assertEquals(41.521, buy.price)
        assertEquals(0.09, buy.fees)
        val sell = r.transactions.single { it.action == Action.SELL }
        assertEquals("OKYO", sell.symbol)
        assertEquals(400.0, sell.shares)
    }

    @Test
    fun convertsPaddedOccSymbolToCompactForm() {
        val tx = parser.parse(json(optionSellToOpen), portfolioId).transactions.single()
        assertEquals("RDDT270115P95", tx.symbol)
        assertEquals(Action.SELL, tx.action)
    }

    @Test
    fun convertsFractionalStrikeCorrectly() {
        val tx = parser.parse(json(optionBuyToCloseFractionalStrike), portfolioId).transactions.single()
        assertEquals("TQQQ260814P62.5", tx.symbol)
        assertEquals(Action.BUY, tx.action)
    }

    @Test
    fun optionQuantityIsContractsScaledToSharesLikeEveryOtherParser() {
        // Truthifi's quantity for an option row is a contract count, not shares -- FifoMatcher,
        // closedOptionsLedger (contracts = shares / 100), open risk, and roll chains all assume
        // shares == contracts * 100, the same convention FidelityCsvParser already uses.
        val tx = parser.parse(json(optionSellToOpen), portfolioId).transactions.single()
        assertEquals(100.0, tx.shares) // 1 contract, not the raw quantity:1
    }

    @Test
    fun multiContractOptionQuantityScalesCorrectly() {
        val threeContracts = """
            {"date":"2026-07-31","transactionType":"sell_to_open","quantity":3,"price":6.72,"fees":2.04,
             "security":{"securityType":"option","handle":{"symbol":"RDDT  270115P00095000"}}}
        """.trimIndent()
        val tx = parser.parse(json(threeContracts), portfolioId).transactions.single()
        assertEquals(300.0, tx.shares)
    }

    @Test
    fun nonTradeTransactionTypesAreSkipped() {
        val r = parser.parse(json(equityBuy, dividend), portfolioId)
        assertEquals(1, r.transactions.size)
        assertEquals(1, r.skipped.nonTrade)
    }

    @Test
    fun pendingMemoRowsWithNoSecurityAreSkippedAsNonTrade() {
        // A closing trade not yet settled by Truthifi -- no security object, generic "memo" type.
        // A later sync picks it up correctly once it resolves into a real buy_to_close/sell_to_close.
        val r = parser.parse(json(pendingMemo), portfolioId)
        assertEquals(0, r.transactions.size)
        assertEquals(1, r.skipped.nonTrade)
    }

    @Test
    fun coreAccountCashSweepActivityIsSkippedAsNonTrade() {
        // FCASH/SPAXX core-position sweeps (auto-purchases, redemptions, reinvestments) aren't real
        // trades -- Fidelity's own CSV export excludes them too, just via a different signal.
        val r = parser.parse(json(cashSweepBuy, cashSweepReinvestment, cashSweepSell, equityBuy), portfolioId)
        assertEquals(1, r.transactions.size)
        assertEquals("AMZU", r.transactions.single().symbol)
        assertEquals(3, r.skipped.nonTrade)
    }

    @Test
    fun buildsExternalId() {
        // Prefix is "fidelity", not "truthifi": Truthifi is just an alternate ingestion path for the
        // same Fidelity account FidelityCsvParser reads via CSV. See dedupesAgainstFidelityCsvImport
        // below for why this specific prefix choice matters.
        val tx = parser.parse(json(equityBuy), portfolioId).transactions.single()
        assertEquals("fidelity:AMZU:2026-07-31T00:00:00:BUY:100.0#0", tx.externalId)
    }

    @Test
    fun dedupesAgainstFidelityCsvImport() {
        // Regression test for a real incident: syncing Truthifi and later importing an overlapping
        // Fidelity CSV (or vice versa) must produce the same externalId for the same trade, so the
        // DB's uniqueness constraint rejects the re-import as a duplicate instead of double-counting it.
        val truthifiTx = parser.parse(json(equityBuy), portfolioId).transactions.single()

        val fidelityCsv = (
            "﻿\n\n" +
                "Run Date,Action,Symbol,Description,Type,Quantity,Price (\$),Commission (\$),Fees (\$)," +
                "Accrued Interest (\$),Amount (\$),Cash Balance (\$),Settlement Date\n" +
                "07/31/2026,\"YOU BOUGHT AMAZON.COM INC (AMZU)\",AMZU,\"AMAZON.COM INC\",Cash,100,41.521," +
                "0.09,,,-" + "4161.19,--,07/31/2026"
            ).encodeToByteArray()
        val fidelityTx = FidelityCsvParser().parse(fidelityCsv, portfolioId).transactions.single()

        assertEquals(fidelityTx.externalId, truthifiTx.externalId)
    }

    @Test
    fun reimportingTheSameRowsProducesTheSameExternalIds() {
        // Idempotency check: re-running a sync over an overlapping date range must dedupe cleanly.
        val first = parser.parse(json(optionSellToOpen), portfolioId).transactions.single().externalId
        val second = TruthifiJsonParser().parse(json(optionSellToOpen), portfolioId).transactions.single().externalId
        assertEquals(first, second)
    }
}
