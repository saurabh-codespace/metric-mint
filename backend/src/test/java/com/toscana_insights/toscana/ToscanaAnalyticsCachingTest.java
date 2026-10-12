package com.toscana_insights.toscana;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import com.toscana_insights.config.CacheConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = {CacheConfig.class, ToscanaAnalyticsService.class, ToscanaAnalyticsCachingTest.TestConfig.class})
class ToscanaAnalyticsCachingTest {
    @Autowired ToscanaAnalyticsService service;
    @Autowired CountingJdbcTemplate jdbc;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void reset() {
        jdbc.queries = 0;
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void cachesOverviewAndRefreshEvictsBeforeReload() {
        service.overview();
        service.overview();
        assertThat(jdbc.queries).isEqualTo(4);

        service.refreshCaches();
        service.overview();
        assertThat(jdbc.queries).isEqualTo(8);
    }

    @Test
    void cacheIsBoundedAndExpiresAfterFiveMinutes() {
        CaffeineCache springCache = (CaffeineCache) cacheManager.getCache(ToscanaAnalyticsService.OVERVIEW_CACHE);
        Cache<?, ?> nativeCache = springCache.getNativeCache();
        assertThat(nativeCache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(100);
        assertThat(nativeCache.policy().expireAfterWrite().orElseThrow().getExpiresAfter()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void returnsCachedWorkbookReportTemplatesWithoutFabricatingMroData() {
        List<Map<String, Object>> reports = service.reports();
        int queriesAfterFirstLoad = jdbc.queries;
        service.reports();

        assertThat(reports).hasSizeGreaterThanOrEqualTo(7).allSatisfy(report -> assertThat(report).containsKeys(
                "id", "title", "description", "category", "chartType", "status", "sourceType", "recordCount", "generatedAt", "data"));
        assertThat(reports).anySatisfy(report -> {
            assertThat(report.get("title")).isEqualTo("MRO - Purchase Order Detail");
            assertThat(report.get("status")).isEqualTo("SOURCE_REQUIRED");
            assertThat(report.get("recordCount")).isEqualTo(0L);
        });
        assertThat(reports).extracting(report -> report.get("chartType"))
                .contains("bar", "donut", "line", "area", "stacked-bar", "table", "kpi");
        assertThat(jdbc.queries).isEqualTo(queriesAfterFirstLoad);
    }

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        CountingJdbcTemplate jdbcTemplate() {
            return new CountingJdbcTemplate();
        }
    }

    static class CountingJdbcTemplate extends JdbcTemplate {
        int queries;

        @Override
        public void afterPropertiesSet() {
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            queries++;
            return List.of();
        }
    }
}
