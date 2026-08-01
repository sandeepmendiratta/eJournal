package io.earlisreal.ejournal.domain.parser

import io.earlisreal.ejournal.domain.model.Action
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FidelityCsvParserTest {

    private val parser = FidelityCsvParser()
    private val portfolioId = 10L

    private val header = "Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date"
    private val buy = "07/07/2025,\"YOU BOUGHT . EXCHANGE FROM FXAIX FIDELITY U.S. BOND INDEX FUND (FXNAX) (Cash)\",FXNAX,\"FIDELITY U.S. BOND INDEX FUND\",Cash,8301.158,10.36,,,,-86000,--,07/07/2025"
    private val sell = "07/07/2025,\"YOU SOLD EXCHANGE TO FXNAX FIDELITY 500 INDEX FUND (FXAIX) (Cash)\",FXAIX,\"FIDELITY 500 INDEX FUND\",Cash,-331.291,217.03,,,,71900,--,07/07/2025"
    private val dividend = "06/30/2025,\"DIVIDEND RECEIVED FIDELITY U.S. BOND INDEX FUND (FXNAX) (Cash)\",FXNAX,\"FIDELITY U.S. BOND INDEX FUND\",Cash,0.000,,,,,2389.28,--,"
    private val reinvest = "06/30/2025,\"REINVESTMENT FIDELITY U.S. BOND INDEX FUND (FXNAX) (Cash)\",FXNAX,\"FIDELITY U.S. BOND INDEX FUND\",Cash,228.858,10.44,,,,-2389.28,--,"
    private val option = "04/24/2026,\"YOU SOLD OPENING TRANSACTION PUT (UMAC) UNUSUAL MACHS INC MAY 01 26 \$13.5 (100 SHS) (Cash)\", -UMAC260501P13.5,\"PUT (UMAC)\",Cash,1,0.41,0.65,0.02,,40.33,10252.02,04/27/2026"
    private val expiredOption = "08/03/2026,\"EXPIRED PUT (TNA) DIREXION SHARES ETF JUL 31 26 \$63 as of 2026-07-31\", -TNA260731P63,\"PUT (TNA)\",Margin,0,\"\",,,,0.00,,\"\""
    private val footer = "\"The data and information in this spreadsheet is provided to you solely for your use and is not for distribution. The spreadsheet is provided for\""
    private val downloaded = "Date downloaded 07/11/2025 7:51 pm"

    // Real Fidelity export shape: UTF-8 BOM, two blank preamble lines, then the header.
    private fun csv(vararg rows: String): ByteArray =
        ("﻿\n\n" + header + "\n" + rows.joinToString("\n")).encodeToByteArray()

    @Test
    fun detectsPastBomAndPreamble() {
        assertTrue(parser.detect(csv(buy)))
    }

    @Test
    fun rejectsSchwabHeader() {
        val schwab = "\"Date\",\"Action\",\"Symbol\",\"Description\",\"Quantity\",\"Price\",\"Fees & Comm\",\"Amount\""
        assertFalse(parser.detect(schwab.encodeToByteArray()))
    }

    @Test
    fun parsesBuyAndSellReadingByName() {
        val r = parser.parse(csv(buy, sell), portfolioId)
        assertEquals(2, r.transactions.size)
        val b = r.transactions.first { it.action == Action.BUY }
        assertEquals("FXNAX", b.symbol)
        assertEquals(8301.158, b.shares)
        assertEquals(10.36, b.price)
        assertEquals(0.0, b.fees)
        assertEquals(LocalDateTime.parse("2025-07-07T00:00:00"), b.datetime)
        val s = r.transactions.first { it.action == Action.SELL }
        assertEquals("FXAIX", s.symbol)
        assertEquals(331.291, s.shares) // abs of -331.291
        assertEquals(217.03, s.price)
    }

    @Test
    fun skipsDividendAndReinvestButParsesOption() {
        val r = parser.parse(csv(buy, dividend, reinvest, option), portfolioId)
        assertEquals(2, r.transactions.size) // buy + option leg (options are now parsed, not skipped)
        assertEquals(2, r.skipped.nonTrade) // dividend + reinvestment
        assertEquals(0, r.skipped.unparsed)
    }

    @Test
    fun parsesOptionLegWithContractMultiplierAppliedToShares() {
        val tx = parser.parse(csv(option), portfolioId).transactions.single()
        assertEquals("UMAC260501P13.5", tx.symbol) // dash stripped, OCC-like code kept as-is
        assertEquals(Action.SELL, tx.action)
        assertEquals(0.41, tx.price) // per-share premium, unmultiplied
        assertEquals(100.0, tx.shares) // 1 contract * 100
        assertEquals(0.67, tx.fees, absoluteTolerance = 1e-9) // 0.65 + 0.02
    }

    @Test
    fun treatsMalformedOptionSymbolAsUnparsedRatherThanGuessing() {
        val badSymbol = "04/24/2026,\"YOU BOUGHT SOMETHING\", -NOTANOPTIONCODE,\"desc\",Cash,1,0.41,,,,-41,,04/27/2026"
        val r = parser.parse(csv(badSymbol), portfolioId)
        assertEquals(0, r.transactions.size)
        assertEquals(1, r.skipped.unparsed)
    }

    @Test
    fun skipsExpiredOptionsAsNonTrade() {
        // No BOUGHT/SOLD action text; matches Fidelity's own Realized Gain/Loss report, which doesn't
        // count premium from expired/assigned options as realized either.
        val r = parser.parse(csv(expiredOption), portfolioId)
        assertEquals(0, r.transactions.size)
        assertEquals(1, r.skipped.nonTrade)
    }

    @Test
    fun stopsAtFooterDisclaimer() {
        val r = parser.parse(csv(buy, "", "", footer, downloaded), portfolioId)
        assertEquals(1, r.transactions.size)
        assertEquals(0, r.skipped.nonTrade) // footer/disclaimer not counted
    }

    @Test
    fun handlesSwappedPriceQuantityColumnOrder() {
        val altHeader = "Run Date,Action,Symbol,Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),Amount ($),Cash Balance ($),Settlement Date"
        val altBuy = "07/07/2025,\"YOU BOUGHT FIDELITY U.S. BOND INDEX FUND (FXNAX) (Cash)\",FXNAX,\"x\",Cash,10.36,8301.158,,,,-86000,--,07/07/2025"
        val bytes = ("﻿\n\n" + altHeader + "\n" + altBuy).encodeToByteArray()
        val tx = parser.parse(bytes, portfolioId).transactions.single()
        assertEquals(8301.158, tx.shares) // read by name, not position
        assertEquals(10.36, tx.price)
    }

    @Test
    fun buildsExternalId() {
        val tx = parser.parse(csv(buy), portfolioId).transactions.single()
        assertEquals("fidelity:FXNAX:2025-07-07T00:00:00:BUY:8301.158#0", tx.externalId)
    }

    @Test
    fun detectsAndParsesMultiAccountExportWithUnsuffixedMoneyColumns() {
        // "Accounts_History" export: no "($)" suffix on money columns, plus Account/Account Number/Exchange* columns.
        val multiHeader = "Run Date,Account,Account Number,Action,Symbol,Description,Type,Exchange Quantity," +
            "Exchange Currency,Currency,Price,Quantity,Exchange Rate,Commission,Fees,Accrued Interest,Amount,Settlement Date"
        val multiBuy = "07/24/2026,Sandeep's,X15614742,YOU BOUGHT HP INC COM (HPQ) (Margin),HPQ,HP INC COM,Margin," +
            "0,\"\",USD,25.39,25,0,0.65,0.01,\"\",\"-634.75\",07/27/2026"
        val bytes = ("﻿\n\n" + multiHeader + "\n" + multiBuy).encodeToByteArray()

        assertTrue(parser.detect(bytes))
        val tx = parser.parse(bytes, portfolioId).transactions.single()
        assertEquals("HPQ", tx.symbol)
        assertEquals(25.0, tx.shares)
        assertEquals(25.39, tx.price)
        assertEquals(0.66, tx.fees, absoluteTolerance = 1e-9) // 0.65 + 0.01
    }

    @Test
    fun treatsRsuVestAsZeroCostBuyInsteadOfDroppingIt() {
        // Real Fidelity RSU vest row: blank Price, "RSU####" in the Action text.
        val rsuVest = "03/16/2026,\"YOU BOUGHT RSU#### AS OF 03-13-26 TARGET CORP (TGT) (Cash)\",TGT,\"TARGET CORP\",Cash,71,,,,,0.00,,03/17/2026"
        val r = parser.parse(csv(rsuVest), portfolioId)
        val tx = r.transactions.single()
        assertEquals(0, r.skipped.unparsed)
        assertEquals("TGT", tx.symbol)
        assertEquals(Action.BUY, tx.action)
        assertEquals(0.0, tx.price)
        assertEquals(71.0, tx.shares)
    }

    @Test
    fun stillTreatsOtherBlankPriceRowsAsUnparsed() {
        // Blank price with no "RSU" in the action text is a genuine parse failure, not guessed at.
        val badRow = "03/16/2026,\"YOU BOUGHT SOMETHING WEIRD\",TGT,\"TARGET CORP\",Cash,71,,,,,0.00,,03/17/2026"
        val r = parser.parse(csv(badRow), portfolioId)
        assertEquals(0, r.transactions.size)
        assertEquals(1, r.skipped.unparsed)
    }

    @Test
    fun sumsCommissionAndFeesIntoSingleFee() {
        // A kept trade with BOTH Commission ($) and Fees ($) non-zero — guards the fee-sum that feeds FIFO.
        val feeBuy = "06/02/2025,\"YOU BOUGHT APPLE INC (AAPL) (Cash)\",AAPL,\"APPLE INC\",Cash,10,150.00,4.95,0.03,,-1504.98,100.00,06/03/2025"
        val tx = parser.parse(csv(feeBuy), portfolioId).transactions.single()
        assertEquals(4.98, tx.fees, absoluteTolerance = 1e-9) // 4.95 + 0.03
        assertEquals(10.0, tx.shares)
        assertEquals(150.0, tx.price)
    }
}
