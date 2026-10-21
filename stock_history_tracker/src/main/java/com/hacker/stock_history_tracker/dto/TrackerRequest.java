package com.hacker.stock_history_tracker.dto;

import java.time.LocalDate;

public record TrackerRequest(
        LocalDate requestDate,
        Double minPrice,
        Double maxPrice,
        Boolean priceAbove50Sma,
        Boolean priceAbove200Sma,
        Boolean sma50AboveSma200,
        Double relativeReturn1M,
        Double relativeReturn6M,
        Double high52weekPercentage,
        Double minBeta,
        Integer topResults,
        String sortBy,
        String sortOrder
) {
}
