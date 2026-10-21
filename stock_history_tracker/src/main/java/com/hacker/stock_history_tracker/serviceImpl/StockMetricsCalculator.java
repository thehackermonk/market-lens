package com.hacker.stock_history_tracker.serviceImpl;

import am.ik.yfinance4j.chart.HistoryRecord;
import com.hacker.stock_history_tracker.dto.StockMetrics;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class StockMetricsCalculator {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int SMA_SHORT_PERIOD = 50;
    private static final int SMA_LONG_PERIOD = 200;
    private static final int RETURN_SCALE = 4;

    StockMetrics calculate(String stock, List<HistoryRecord> history, List<HistoryRecord> benchmarkHistory) {
        BigDecimal close = history.getLast().close();

        return new StockMetrics(
                stock,
                close.setScale(2,RoundingMode.HALF_UP),
                simpleMovingAverage(history, SMA_SHORT_PERIOD).setScale(2,RoundingMode.HALF_UP),
                simpleMovingAverage(history, SMA_LONG_PERIOD).setScale(2,RoundingMode.HALF_UP),
                fiftyTwoWeekHigh(history).setScale(2,RoundingMode.HALF_UP),
                beta(history, benchmarkHistory).setScale(2,RoundingMode.HALF_UP),
                relativeReturn(history, benchmarkHistory, 1).setScale(2,RoundingMode.HALF_UP),
                relativeReturn(history, benchmarkHistory, 6).setScale(2,RoundingMode.HALF_UP)
        );
    }

    private BigDecimal simpleMovingAverage(List<HistoryRecord> history, int period) {
        List<HistoryRecord> window = history.subList(history.size() - period, history.size());

        BigDecimal sum = window.stream()
                .map(HistoryRecord::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(period), RoundingMode.HALF_UP);
    }

    private BigDecimal fiftyTwoWeekHigh(List<HistoryRecord> history) {
        LocalDate lastDate = dateOf(history.getLast());
        LocalDate cutoff = lastDate.minusWeeks(52);

        return history.stream()
                .filter(record -> !dateOf(record).isBefore(cutoff))
                .map(HistoryRecord::high)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal relativeReturn(List<HistoryRecord> history, List<HistoryRecord> benchmarkHistory, int months) {
        Map<LocalDate, HistoryRecord> stockByDate = byDate(history);
        Map<LocalDate, HistoryRecord> benchmarkByDate = byDate(benchmarkHistory);

        LocalDate lastDate = dateOf(history.getLast());
        LocalDate pastDate = nearestTradingDate(lastDate.minusMonths(months), stockByDate, benchmarkByDate);

        BigDecimal stockReturn = percentageChange(stockByDate.get(pastDate), stockByDate.get(lastDate));
        BigDecimal benchmarkReturn = percentageChange(benchmarkByDate.get(pastDate), benchmarkByDate.get(lastDate));

        return stockReturn.subtract(benchmarkReturn);
    }

    private LocalDate nearestTradingDate(LocalDate target, Map<LocalDate, HistoryRecord> stockByDate, Map<LocalDate, HistoryRecord> benchmarkByDate) {
        LocalDate candidate = target;
        for (int i = 0; i < 10 && (!stockByDate.containsKey(candidate) || !benchmarkByDate.containsKey(candidate)); i++) {
            candidate = candidate.minusDays(1);
        }

        return candidate;
    }

    private BigDecimal percentageChange(HistoryRecord from, HistoryRecord to) {
        BigDecimal fromClose = from.close();
        BigDecimal toClose = to.close();

        return toClose.subtract(fromClose)
                .divide(fromClose, RETURN_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal beta(List<HistoryRecord> history, List<HistoryRecord> benchmarkHistory) {
        Map<LocalDate, HistoryRecord> benchmarkByDate = byDate(benchmarkHistory);

        List<BigDecimal> stockReturns = new ArrayList<>();
        List<BigDecimal> benchmarkReturns = new ArrayList<>();

        for (int i = 1; i < history.size(); i++) {
            HistoryRecord today = history.get(i);
            HistoryRecord yesterday = history.get(i - 1);

            HistoryRecord benchmarkToday = benchmarkByDate.get(dateOf(today));
            HistoryRecord benchmarkYesterday = benchmarkByDate.get(dateOf(yesterday));

            if (benchmarkToday == null || benchmarkYesterday == null) {
                continue;
            }

            stockReturns.add(dailyReturn(yesterday, today));
            benchmarkReturns.add(dailyReturn(benchmarkYesterday, benchmarkToday));
        }

        if (benchmarkReturns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal avgStockReturn = average(stockReturns);
        BigDecimal avgBenchmarkReturn = average(benchmarkReturns);

        BigDecimal covariance = BigDecimal.ZERO;
        BigDecimal variance = BigDecimal.ZERO;

        for (int i = 0; i < stockReturns.size(); i++) {
            BigDecimal stockDelta = stockReturns.get(i).subtract(avgStockReturn);
            BigDecimal benchmarkDelta = benchmarkReturns.get(i).subtract(avgBenchmarkReturn);

            covariance = covariance.add(stockDelta.multiply(benchmarkDelta));
            variance = variance.add(benchmarkDelta.multiply(benchmarkDelta));
        }

        return variance.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : covariance.divide(variance, RETURN_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal dailyReturn(HistoryRecord yesterday, HistoryRecord today) {
        return today.close().subtract(yesterday.close())
                .divide(yesterday.close(), RETURN_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal average(List<BigDecimal> values) {
        BigDecimal sum = values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), RETURN_SCALE, RoundingMode.HALF_UP);
    }

    private Map<LocalDate, HistoryRecord> byDate(List<HistoryRecord> historyRecords) {
        return historyRecords.stream()
                .collect(Collectors.toMap(this::dateOf, record -> record));
    }

    private LocalDate dateOf(HistoryRecord record) {
        return LocalDate.ofInstant(record.timestamp(), MARKET_ZONE);
    }
}
