package io.earlisreal.ejournal.domain.model

import kotlinx.datetime.LocalDateTime

/** A symbol's still-open (not yet flat) FIFO position -- the counterpart to [ClosedPosition]. */
data class OpenPosition(
    val symbol: String,
    val direction: TradeDirection,
    val shares: Double,
    val averagePrice: Double,
    val openDatetime: LocalDateTime,
    val market: Market = Market.US_STOCKS,
)
