package com.hacker.stock_history_tracker.controller;

import com.hacker.stock_history_tracker.dto.StockMetrics;
import com.hacker.stock_history_tracker.dto.TrackerRequest;
import com.hacker.stock_history_tracker.service.ScreenerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/track")
@RequiredArgsConstructor
public class ScreenerController {

    private final ScreenerService screenerService;

    @PostMapping
    public ResponseEntity<List<StockMetrics>> getScreener(@RequestBody TrackerRequest trackerRequest) {

        List<StockMetrics> screenedStocks = screenerService.getScreenedStocks(trackerRequest);
        return ResponseEntity.ok(screenedStocks);
    }
}
