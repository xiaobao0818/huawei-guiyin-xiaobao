package com.attribution.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Business metrics for the attribution pipeline.
 * <p>
 * Exported via {@code /actuator/prometheus} and consumable by Prometheus + Grafana.
 * <h3>Key metrics</h3>
 * <ul>
 *   <li>{@code attribution.events.total} — total events received by type and game</li>
 *   <li>{@code attribution.match} — match results by type (oaid/gaid/idfa/fingerprint/unmatched)</li>
 *   <li>{@code attribution.result} — process outcomes (matched/no_match/duplicate/...)</li>
 *   <li>{@code attribution.callback} — callback results (success/failure)</li>
 *   <li>{@code attribution.processing.time} — AttributionEngine process duration</li>
 * </ul>
 */
@Component
public class AttributionMetrics {

    private final MeterRegistry registry;

    public AttributionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Record an incoming report event. */
    public void recordEvent(String gameId, String eventType) {
        Counter.builder("attribution.events.total")
                .description("Total events received")
                .tag("game", gameId)
                .tag("event", eventType)
                .register(registry)
                .increment();
    }

    /** Record a successful OAID match. */
    public void recordMatchOaid(String gameId) {
        recordMatchDeviceId(gameId, "oaid");
    }

    /** Record a successful deterministic device-id match. */
    public void recordMatchDeviceId(String gameId, String idType) {
        Counter.builder("attribution.match")
                .description("Successful attribution matches by type")
                .tag("type", idType)
                .tag("game", gameId)
                .register(registry)
                .increment();
    }

    /** Record a fingerprint fallback match. */
    public void recordMatchFingerprint(String gameId) {
        Counter.builder("attribution.match")
                .description("Successful attribution matches by type")
                .tag("type", "fingerprint")
                .tag("game", gameId)
                .register(registry)
                .increment();
    }

    /** Record an unmatched event. */
    public void recordNoMatch(String gameId) {
        Counter.builder("attribution.match")
                .description("Successful attribution matches by type")
                .tag("type", "unmatched")
                .tag("game", gameId)
                .register(registry)
                .increment();
    }

    /** Record the final AttributionEngine outcome, including short-circuit paths. */
    public void recordProcessResult(String gameId, String result) {
        Counter.builder("attribution.result")
                .description("Attribution engine processing outcomes")
                .tag("result", result)
                .tag("game", gameId)
                .register(registry)
                .increment();
    }

    /** Record a successful callback to WhaleHongDong. */
    public void recordCallbackSuccess(String gameId) {
        Counter.builder("attribution.callback")
                .description("Callback results to WhaleHongDong")
                .tag("result", "success")
                .tag("game", gameId)
                .register(registry)
                .increment();
    }

    /** Record a failed callback. */
    public void recordCallbackFailure(String gameId) {
        Counter.builder("attribution.callback")
                .description("Callback results to WhaleHongDong")
                .tag("result", "failure")
                .tag("game", gameId)
                .register(registry)
                .increment();
    }

    /** Start a timer for the attribution processing pipeline. */
    public Timer.Sample startProcessingTimer() {
        return Timer.start(registry);
    }

    /** Stop the processing timer and record the duration. */
    public void stopProcessingTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("attribution.processing.time")
                .description("AttributionEngine processing duration")
                .register(registry));
    }

    /** Record click callback received. */
    public void recordClickReceived(String gameId) {
        Counter.builder("attribution.clicks.total")
                .description("Total click callbacks received")
                .tag("game", gameId)
                .register(registry)
                .increment();
    }

    /** Record callback task retry. */
    public void recordCallbackRetry(String gameId, int attempt) {
        Counter.builder("attribution.callback.retries")
                .description("Callback retry attempts by attempt number")
                .tag("game", gameId)
                .tag("attempt", String.valueOf(attempt))
                .register(registry)
                .increment();
    }

    /** Record funnel stage transition. */
    public void recordFunnel(String stage, String gameId) {
        Counter.builder("attribution.funnel")
                .description("Attribution funnel: received/matched/enqueued/sent/success")
                .tag("stage", stage)
                .tag("game", gameId)
                .register(registry)
                .increment();
    }

    /** Record callback latency. */
    public void recordCallbackLatency(String gameId, long durationMs) {
        Timer.builder("attribution.callback.latency")
                .description("Callback HTTP request latency")
                .tag("game", gameId)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** Record match operation latency. */
    public void recordMatchLatency(String gameId, long durationMs) {
        Timer.builder("attribution.match.latency")
                .description("Attribution match operation latency")
                .tag("game", gameId)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** Set the current callback task queue depth (pending + retry_pending). */
    public void setCallbackQueueDepth(long count) {
        registry.gauge("attribution.callback.queue_depth",
                io.micrometer.core.instrument.Tags.of("queue", "callback"),
                count);
    }
}
