package io.earlisreal.ejournal.domain.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetClassTest {

    @Test
    fun classifiesPlainTickerAsEquity() {
        assertEquals(AssetClass.EQUITY, assetClassOf("AAPL"))
    }

    @Test
    fun classifiesOccShapeAsOption() {
        assertEquals(AssetClass.OPTION, assetClassOf("TNA260731P63"))
    }

    @Test
    fun parsesPutFields() {
        val occ = parseOccSymbol("TNA260731P63")
        assertEquals(OccOption("TNA", LocalDate(2026, 7, 31), OptionRight.PUT, 63.0), occ)
    }

    @Test
    fun parsesCallWithMultiLetterRootAndFractionalStrike() {
        val occ = parseOccSymbol("UMAC260501C13.5")
        assertEquals(OccOption("UMAC", LocalDate(2026, 5, 1), OptionRight.CALL, 13.5), occ)
    }

    @Test
    fun rejectsPlainTicker() {
        assertNull(parseOccSymbol("AAPL"))
    }

    @Test
    fun rejectsInvalidCalendarDate() {
        assertNull(parseOccSymbol("AAPL261332C10")) // month 13, day 32
    }
}
