package com.attribution;

import com.attribution.common.entity.EventDefinition;
import com.attribution.common.entity.GameConfig;
import com.attribution.common.entity.ClickRecord;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.EventDefinitionRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.attribution.common.util.AesUtil;
import com.attribution.common.util.RedisKeyUtil;
import com.attribution.core.engine.AttributionEngine;
import com.attribution.core.engine.CallbackRetryService;
import com.attribution.core.metrics.AttributionMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AttributionEngineTest {

    @Autowired private AttributionEngine engine;
    @Autowired private GameConfigRepository gameConfigRepo;
    @Autowired private ClickRecordRepository clickRecordRepo;
    @Autowired private AttributionRecordRepository attributionRecordRepo;
    @Autowired private EventDefinitionRepository eventDefRepo;

    @MockBean private RedisTemplate<String, Object> redisTemplate;
    @MockBean private ValueOperations<String, Object> valueOperations;
    @MockBean private CallbackRetryService retryService;
    @MockBean private AttributionMetrics metrics;

    @Value("${attribution.encryption-key}")
    private String encryptionKey;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(180L), eq(TimeUnit.DAYS)))
                .thenReturn(true);

        // Delete test data (cascade due to FK order)
        attributionRecordRepo.deleteAll();
        clickRecordRepo.deleteAll();
        eventDefRepo.deleteAll();
        gameConfigRepo.deleteAll();

        // Register a game
        GameConfig gc = new GameConfig();
        gc.setGameId("test_game");
        gc.setGameName("Test Game");
        gc.setSecretKey("****");
        gc.setAttributionWindowDays(30);
        gc.setStatus(true);
        gameConfigRepo.save(gc);

        // Register activate event
        EventDefinition ed = new EventDefinition();
        ed.setGameId("test_game");
        ed.setEventName("activate");
        ed.setConversionType("activate");
        ed.setEnabled(true);
        eventDefRepo.save(ed);
    }

    @Test
    void process_gameNotRegistered_returnsFail() {
        var req = newRequest("unknown_game", "activate", "oaid-123");
        var result = engine.process(req);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("未注册"));
    }

    @Test
    void process_eventNotConfigured_returnsFail() {
        var req = newRequest("test_game", "nonexistent_event", "oaid-123");
        var result = engine.process(req);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("事件未配置"));
    }

    @Test
    void process_activateWithNoMatch_stillSavesRecord() {
        // No click data in DB or Redis
        when(valueOperations.get(anyString())).thenReturn(null);

        var req = newRequest("test_game", "activate", "oaid-new");
        var result = engine.process(req);

        assertTrue(result.isSuccess());
        assertEquals("no_match", result.getStatus());

        // Verify record was saved
        assertFalse(attributionRecordRepo.findAll().isEmpty());
    }

    @Test
    void process_duplicateActivate_returnsAlreadyProcessed() {
        // First call - mock Redis lock succeeds
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(180L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        when(valueOperations.get(anyString())).thenReturn(null);

        var req = newRequest("test_game", "activate", "oaid-dup");
        var result1 = engine.process(req);
        assertTrue(result1.isSuccess());

        // Second call - Redis lock already held
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(180L), eq(TimeUnit.DAYS)))
                .thenReturn(false);

        var result2 = engine.process(req);
        assertTrue(result2.isSuccess());
        assertEquals("already_processed", result2.getStatus());
    }

    @Test
    void process_purchaseWithoutOrderId_savesRecord() {
        // Register purchase event
        EventDefinition ed = new EventDefinition();
        ed.setGameId("test_game");
        ed.setEventName("purchase");
        ed.setConversionType("paid");
        ed.setEnabled(true);
        eventDefRepo.save(ed);

        when(valueOperations.get(anyString())).thenReturn(null);

        var req = newRequest("test_game", "purchase", "oaid-buy");
        req.setEventParams(Map.of("revenue", 6.0, "currency", "CNY"));
        var result = engine.process(req);
        assertTrue(result.isSuccess());
        assertEquals("no_match", result.getStatus());
    }

    @Test
    void process_purchaseWithRevenue_savesRevenue() {
        EventDefinition ed = new EventDefinition();
        ed.setGameId("test_game");
        ed.setEventName("purchase");
        ed.setConversionType("paid");
        ed.setEnabled(true);
        eventDefRepo.save(ed);

        when(valueOperations.get(anyString())).thenReturn(null);

        var req = newRequest("test_game", "purchase", "oaid-rev");
        req.setEventParams(Map.of("revenue", 12.50, "currency", "CNY", "order_id", "ORD-001"));
        var result = engine.process(req);
        assertTrue(result.isSuccess());

        var records = attributionRecordRepo.findAll();
        assertEquals(1, records.size());
        assertEquals(12.50, records.get(0).getRevenue(), 0.01);
    }

    @Test
    void process_gaidMatchesClickRecord() {
        ClickRecord click = new ClickRecord();
        click.setGameId("test_game");
        click.setOaid("");
        click.setGaid("gaid-123");
        click.setCallback("callback-token");
        click.setClickTime(System.currentTimeMillis());
        click.setMatched(false);
        click = clickRecordRepo.save(click);
        Long clickId = click.getId();

        var req = newRequest("test_game", "activate", "");
        req.getDevice().setGaid("gaid-123");

        var result = engine.process(req);

        assertTrue(result.isSuccess());
        assertEquals("matched", result.getStatus());
        var records = attributionRecordRepo.findAll();
        assertEquals(1, records.size());
        assertEquals("gaid", records.get(0).getAttributionType());
        assertEquals("gaid:gaid-123", records.get(0).getOaid());
        assertEquals(clickId, records.get(0).getClickId());
    }

    @Test
    void process_purchaseAfterActivate_reusesMatchedClickRecord() {
        EventDefinition ed = new EventDefinition();
        ed.setGameId("test_game");
        ed.setEventName("purchase");
        ed.setConversionType("paid");
        ed.setEnabled(true);
        eventDefRepo.save(ed);

        ClickRecord click = new ClickRecord();
        click.setGameId("test_game");
        click.setOaid("oaid-multi");
        click.setCallback("callback-token");
        click.setClickTime(System.currentTimeMillis());
        click.setMatched(false);
        click = clickRecordRepo.save(click);
        Long clickId = click.getId();

        when(valueOperations.get(anyString())).thenReturn(null);

        var activate = newRequest("test_game", "activate", "oaid-multi");
        assertEquals("matched", engine.process(activate).getStatus());

        var purchase = newRequest("test_game", "purchase", "oaid-multi");
        purchase.setEventParams(Map.of("revenue", 9.9));
        var result = engine.process(purchase);

        assertTrue(result.isSuccess());
        assertEquals("matched", result.getStatus());
        assertEquals(2, attributionRecordRepo.findAll().size());
        assertTrue(attributionRecordRepo.findAll().stream()
                .anyMatch(record -> "purchase".equals(record.getEventType())
                        && clickId.equals(record.getClickId())));
    }

    @Test
    void process_activateAfterProtection_allowsReattribution() {
        var first = newRequest("test_game", "activate", "oaid-reattr");
        assertTrue(engine.process(first).isSuccess());

        var existing = attributionRecordRepo.findAll().get(0);
        existing.setCreatedAt(java.time.LocalDateTime.now().minusDays(10));
        attributionRecordRepo.save(existing);

        var second = newRequest("test_game", "activate", "oaid-reattr");
        var result = engine.process(second);

        assertTrue(result.isSuccess());
        assertNotEquals("already_processed", result.getStatus());
        assertEquals(2, attributionRecordRepo.findAll().size());
        assertTrue(attributionRecordRepo.findAll().stream()
                .anyMatch(record -> Boolean.TRUE.equals(record.getReattribution())));
    }

    @Test
    void process_activateBeforeConfiguredSilence_skipsReattribution() {
        GameConfig config = gameConfigRepo.findByGameId("test_game").orElseThrow();
        config.setWindowConfig("{\"protection_days\":7,\"silence_days\":3}");
        gameConfigRepo.save(config);

        var first = newRequest("test_game", "activate", "oaid-silence");
        assertTrue(engine.process(first).isSuccess());

        var existing = attributionRecordRepo.findAll().get(0);
        existing.setCreatedAt(java.time.LocalDateTime.now().minusDays(9));
        attributionRecordRepo.save(existing);

        var second = newRequest("test_game", "activate", "oaid-silence");
        var result = engine.process(second);

        assertTrue(result.isSuccess());
        assertEquals("already_processed", result.getStatus());
        assertEquals(1, attributionRecordRepo.findAll().size());
    }

    private AttributionEngine.ReportRequest newRequest(String gameId, String event, String oaid) {
        var req = new AttributionEngine.ReportRequest();
        req.setGameId(gameId);
        req.setPlatform("apk");
        req.setEvent(event);
        req.setTs(System.currentTimeMillis());

        var di = new AttributionEngine.DeviceInfo();
        di.setOaid(oaid);
        req.setDevice(di);

        return req;
    }
}
