package io.earlisreal.ejournal.domain.model

import kotlinx.datetime.LocalDate

enum class AssetClass { EQUITY, OPTION }

enum class OptionRight { CALL, PUT }

/** The decoded parts of an OCC-like option symbol (see [OCC_SYMBOL]). */
data class OccOption(val root: String, val expiry: LocalDate, val right: OptionRight, val strike: Double)

// root letters + YYMMDD + C/P + strike, e.g. "TNA260731P63" or "UMAC260501P13.5" -- the OCC-like
// symbol shape FidelityCsvParser stores for option legs (dash prefix already stripped).
private val OCC_SYMBOL = Regex("""([A-Z]{1,6})(\d{2})(\d{2})(\d{2})([CP])(\d+(?:\.\d+)?)""")

/** Classifies a [Transaction.symbol]/[ClosedPosition.symbol] by shape: OCC-like -> option, else equity. */
fun assetClassOf(symbol: String): AssetClass =
    if (parseOccSymbol(symbol) != null) AssetClass.OPTION else AssetClass.EQUITY

/** Decodes an OCC-like option symbol into its parts, or null if [symbol] doesn't match the shape. */
fun parseOccSymbol(symbol: String): OccOption? {
    val match = OCC_SYMBOL.matchEntire(symbol) ?: return null
    val (root, yy, mm, dd, rightCode, strike) = match.destructured
    val expiry = try {
        LocalDate(2000 + yy.toInt(), mm.toInt(), dd.toInt())
    } catch (e: IllegalArgumentException) {
        return null
    }
    val right = if (rightCode == "C") OptionRight.CALL else OptionRight.PUT
    return OccOption(root, expiry, right, strike.toDouble())
}
