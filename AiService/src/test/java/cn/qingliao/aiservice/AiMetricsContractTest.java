package cn.qingliao.aiservice;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.qingliao.aiservice.Monitor.AiModelMetricsCollector;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

class AiMetricsContractTest {
    @Test
    void exposesMetricsAndHistogramUsedByOriginalDashboard() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var collector = new AiModelMetricsCollector();
            ReflectionTestUtils.setField(collector, "meterRegistry", registry);
            collector.recordRequest("1001", "99002", "test-model", "started");
            collector.recordRequest("1001", "99002", "test-model", "success");
            collector.recordError("1001", "99002", "test-model", "test-error");
            collector.recordTokenUsage("1001", "99002", "test-model", "total", 12);
            collector.recordResponseTime("1001", "99002", "test-model", Duration.ofMillis(25));
            String metrics = registry.scrape();
            for (String required :
                    new String[] {
                        "ai_model_requests_total",
                        "ai_model_errors_total",
                        "ai_model_tokens_total",
                        "ai_model_response_duration_seconds_sum",
                        "ai_model_response_duration_seconds_count",
                        "ai_model_response_duration_seconds_bucket"
                    }) assertTrue(metrics.contains(required), required);
        } finally {
            registry.close();
        }
    }
}
