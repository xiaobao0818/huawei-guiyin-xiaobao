package com.attribution;

import com.attribution.common.entity.EventDefinition;
import com.attribution.common.entity.GameConfig;
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
