package com.attribution;

import com.attribution.common.entity.EventDefinition;
import com.attribution.core.engine.AttributionEngine;
import com.attribution.core.engine.CallbackRuleEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CallbackRuleEvaluatorTest {

    @Test
    void shouldCallback_timeWindowDoesNotClaimRedisLock() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        CallbackRuleEvaluator evaluator = new CallbackRuleEvaluator(new ObjectMapper(), redisTemplate);

        assertTrue(evaluator.shouldCallback(eventDef(), request()));
        verify(redisTemplate).hasKey(anyString());
        verify(ops, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void claimCallbackWindow_setsRedisLockOnlyWhenMatchedFlowCallsClaim() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), eq("1"), eq(15L), eq(TimeUnit.MINUTES))).thenReturn(true);

        CallbackRuleEvaluator evaluator = new CallbackRuleEvaluator(new ObjectMapper(), redisTemplate);

        assertTrue(evaluator.claimCallbackWindow(eventDef(), request()));
        verify(ops).setIfAbsent(anyString(), eq("1"), eq(15L), eq(TimeUnit.MINUTES));
    }

    private EventDefinition eventDef() {
        EventDefinition def = new EventDefinition();
        def.setGameId("test_game");
        def.setEventName("purchase");
        def.setConversionType("paid");
        def.setEnabled(true);
        def.setCallbackRule("""
                {"type":"time_window","windowMinutes":15,"scope":"game:oaid"}
                """);
        return def;
    }

    private AttributionEngine.ReportRequest request() {
        AttributionEngine.ReportRequest req = new AttributionEngine.ReportRequest();
        req.setGameId("test_game");
        req.setEvent("purchase");
        AttributionEngine.DeviceInfo device = new AttributionEngine.DeviceInfo();
        device.setOaid("oaid-1");
        req.setDevice(device);
        return req;
    }
}
