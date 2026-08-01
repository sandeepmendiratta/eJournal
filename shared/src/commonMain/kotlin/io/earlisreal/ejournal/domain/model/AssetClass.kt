package io.earlisreal.ejournal.domain.model

enum class AssetClass { EQUITY, OPTION }

// root letters + YYMMDD + C/P + strike, e.g. "TNA260731P63" or "UMAC260501P13.5" -- the OCC-like
// symbol shape FidelityCsvParser stores for option legs (dash prefix already stripped).
private val OCC_SYMBOL = Regex("""[A-Z]{1,6}\d{6}[CP]\d+(\.\d+)?""")

/** Classifies a [Transaction.symbol]/[ClosedPosition.symbol] by shape: OCC-like -> option, else equity. */
fun assetClassOf(symbol: String): AssetClass =
    if (OCC_SYMBOL.matches(symbol)) AssetClass.OPTION else AssetClass.EQUITY
