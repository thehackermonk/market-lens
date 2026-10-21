package com.hacker.stock_history_tracker.service;

import com.hacker.stock_history_tracker.dto.StockMetrics;
import com.hacker.stock_history_tracker.dto.TrackerRequest;

import java.util.List;

public interface ScreenerService {

    List<StockMetrics> getScreenedStocks(TrackerRequest trackerRequest);
}
