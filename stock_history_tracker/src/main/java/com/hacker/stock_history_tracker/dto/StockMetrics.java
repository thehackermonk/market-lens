package com.hacker.stock_history_tracker.dto;

import java.math.BigDecimal;

public record StockMetrics(
        String stock,
        BigDecimal close,
        BigDecimal sma50,
        BigDecimal sma200,
        BigDecimal high52week,
        BigDecimal beta,
        BigDecimal return1MVsNifty,
        BigDecimal return6MVsNifty
) {
}
