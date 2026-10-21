package com.hacker.stock_history_tracker.config;

import am.ik.yfinance4j.YFinance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class YFinanceConfig {

    @Bean
    public YFinance yFinance() {
        RestClient restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();

        return new YFinance(restClient);
    }
}
