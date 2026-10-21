package com.hacker.stock_history_tracker.serviceImpl;

import am.ik.yfinance4j.Interval;
import am.ik.yfinance4j.Ticker;
import am.ik.yfinance4j.YFinance;
import am.ik.yfinance4j.chart.ChartRequest;
import am.ik.yfinance4j.chart.HistoryRecord;
import com.hacker.stock_history_tracker.dto.StockMetrics;
import com.hacker.stock_history_tracker.dto.TrackerRequest;
import com.hacker.stock_history_tracker.service.ScreenerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

@Service
@Slf4j
public class ScreenerServiceImpl implements ScreenerService {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String BENCHMARK_SYMBOL = "^NSEI";
    private static final int MIN_HISTORY_SIZE = 240;
    private static final int DEFAULT_LIMIT = 20;

    private final YFinance yFinance;
    private final ObjectMapper objectMapper;
    private final Resource resource;
    private final StockMetricsCalculator calculator;

    public ScreenerServiceImpl(
            YFinance yFinance,
            ObjectMapper objectMapper,
            @Value("classpath:stocks.json") Resource resource,
            StockMetricsCalculator calculator) {

        this.yFinance = yFinance;
        this.objectMapper = objectMapper;
        this.resource = resource;
        this.calculator = calculator;
    }

    @Override
    public List<StockMetrics> getScreenedStocks(TrackerRequest trackerRequest) {
        List<String> stocks = readStockUniverse();

        if (stocks.isEmpty()) {
            return List.of();
        }

        Instant endDate = trackerRequest.requestDate().atStartOfDay(MARKET_ZONE).toInstant();
        Instant startDate = trackerRequest.requestDate().minusYears(1).atStartOfDay(MARKET_ZONE).toInstant();

        List<HistoryRecord> benchmarkHistory = getHistory(yFinance.ticker(BENCHMARK_SYMBOL), startDate, endDate);
        List<StockMetrics> allMetrics = fetchMetricsInParallel(stocks, startDate, endDate, benchmarkHistory);

        Comparator<StockMetrics> comparator = resolveComparator(trackerRequest);
        int limit = trackerRequest.topResults() != null ? trackerRequest.topResults() : DEFAULT_LIMIT;

        if (isFilterEnabled(trackerRequest)) {
            return allMetrics.stream()
                    .filter(buildFilter(trackerRequest))
                    .sorted(comparator)
                    .limit(limit)
                    .toList();
        } else {
            return allMetrics.stream()
                    .sorted(comparator)
                    .limit(limit)
                    .toList();
        }
    }

    private List<StockMetrics> fetchMetricsInParallel(List<String> stocks, Instant startDate, Instant endDate, List<HistoryRecord> benchmarkHistory) {
        return stocks.parallelStream()
                .map(stock -> computeMetrics(stock, startDate, endDate, benchmarkHistory))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<StockMetrics> computeMetrics(String stock, Instant startDate, Instant endDate, List<HistoryRecord> benchmarkHistory) {
        String symbol = stock + ".NS";
        List<HistoryRecord> history = getHistory(yFinance.ticker(symbol), startDate, endDate);

        if (history.size() < MIN_HISTORY_SIZE) {
            log.warn("Skipping {}: insufficient historical data", symbol);
            return Optional.empty();
        }

        log.info("Stock history data fetched for {}", symbol);
        return Optional.of(calculator.calculate(stock, history, benchmarkHistory));
    }

    private Predicate<StockMetrics> buildFilter(TrackerRequest request) {
        Predicate<StockMetrics> predicate = metrics -> true;

        if (request.minPrice() != null && request.maxPrice() != null) {
            BigDecimal min = BigDecimal.valueOf(request.minPrice());
            BigDecimal max = BigDecimal.valueOf(request.maxPrice());
            predicate = predicate.and(m -> m.close().compareTo(min) > 0 && m.close().compareTo(max) <= 0);
        }

        if (request.priceAbove200Sma() != null && request.priceAbove200Sma()) {
            predicate = predicate.and(m -> m.close().compareTo(m.sma200()) > 0);
        }

        if (request.priceAbove50Sma() != null && request.priceAbove50Sma()) {
            predicate = predicate.and(m -> m.close().compareTo(m.sma50()) > 0);
        }

        if (request.sma50AboveSma200() != null && request.sma50AboveSma200()) {
            predicate = predicate.and(m -> m.sma50().compareTo(m.sma200()) > 0);
        }

        if (request.relativeReturn1M() != null && request.relativeReturn6M() != null) {
            BigDecimal minReturn1M = BigDecimal.valueOf(request.relativeReturn1M());
            BigDecimal minReturn6M = BigDecimal.valueOf(request.relativeReturn6M());

            predicate = predicate.and(m -> m.return1MVsNifty().compareTo(minReturn1M) > 0
                    && m.return6MVsNifty().compareTo(minReturn6M) > 0);
        }

        if (request.high52weekPercentage() != null) {
            BigDecimal factor = BigDecimal.valueOf((100 - request.high52weekPercentage()) / 100).round(MathContext.DECIMAL32);
            predicate = predicate.and(m -> m.close().compareTo(m.high52week().multiply(factor)) >= 0);
        }

        if (request.minBeta() != null) {
            BigDecimal minBeta = BigDecimal.valueOf(request.minBeta());
            predicate = predicate.and(m -> m.beta().compareTo(minBeta) > 0);
        }

        return predicate;
    }

    private Comparator<StockMetrics> resolveComparator(TrackerRequest request) {
        String sortBy = request.sortBy() == null ? "stock" : request.sortBy().toLowerCase();

        Comparator<StockMetrics> comparator = switch (sortBy) {
            case "close" -> Comparator.comparing(StockMetrics::close);
            case "sma50" -> Comparator.comparing(StockMetrics::sma50);
            case "sma200" -> Comparator.comparing(StockMetrics::sma200);
            case "high52week" -> Comparator.comparing(StockMetrics::high52week);
            case "beta" -> Comparator.comparing(StockMetrics::beta);
            case "return1mvsnifty" -> Comparator.comparing(StockMetrics::return1MVsNifty);
            case "return6mvsnifty" -> Comparator.comparing(StockMetrics::return6MVsNifty);
            default -> Comparator.comparing(StockMetrics::stock);
        };

        return "desc".equalsIgnoreCase(request.sortOrder())
                ? comparator.reversed()
                : comparator;
    }

    private List<HistoryRecord> getHistory(Ticker ticker, Instant startDate, Instant endDate) {
        try {
            List<HistoryRecord> history = ticker.history(
                    ChartRequest.builder()
                            .start(startDate)
                            .end(endDate)
                            .interval(Interval.ONE_DAY)
                            .build()
            );

            log.info("Retrieved {} historical records for {}", history.size(), ticker.symbol());
            return history;
        } catch (Exception e) {
            log.error("Failed to retrieve historical data for {}", ticker.symbol(), e);
            return List.of();
        }
    }

    private List<String> readStockUniverse() {
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<>() {
            });
        } catch (IOException e) {
            log.error("Failed to read stock universe from {}", resource, e);
            return List.of();
        }
    }

    private boolean isFilterEnabled(TrackerRequest request) {
        return request.minPrice() != null
                || request.maxPrice() != null
                || request.priceAbove50Sma() != null
                || request.priceAbove200Sma() != null
                || request.sma50AboveSma200() != null
                || request.relativeReturn1M() != null
                || request.relativeReturn6M() != null
                || request.high52weekPercentage() != null
                || request.minBeta() != null;
    }
}
