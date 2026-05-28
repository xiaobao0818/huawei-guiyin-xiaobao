package com.attribution;

import com.attribution.common.entity.EventDefinition;
import com.attribution.common.repository.EventDefinitionRepository;
import com.attribution.core.event.EventRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class EventRouterTest {

    @Autowired private EventRouter eventRouter;
    @Autowired private EventDefinitionRepository eventDefRepo;

    @BeforeEach
    void setUp() {
        eventDefRepo.deleteAll();
        eventRouter.clearGame("t1");

        // Game-specific event
        EventDefinition ed = new EventDefinition();
        ed.setGameId("t1");
        ed.setEventName("level_up");
        ed.setConversionType("custom");
        ed.setEnabled(true);
        eventDefRepo.save(ed);

        // Wildcard preset
        EventDefinition preset = new EventDefinition();
        preset.setGameId("*");
        preset.setEventName("activate");
        preset.setConversionType("activate");
        preset.setEnabled(true);
        preset.setIsPreset(true);
        eventDefRepo.save(preset);
    }

    @Test
    void lookup_matchesGameSpecificEvent() {
        Optional<EventDefinition> result = eventRouter.lookup("t1", "level_up");
        assertTrue(result.isPresent());
        assertEquals("level_up", result.get().getEventName());
        assertEquals("custom", result.get().getConversionType());
    }

    @Test
    void lookup_fallsBackToWildcard() {
        Optional<EventDefinition> result = eventRouter.lookup("t1", "activate");
        assertTrue(result.isPresent());
        assertEquals("activate", result.get().getConversionType());
    }

    @Test
    void lookup_returnsCacheHitOnSecondCall() {
        // First call fills cache
        eventRouter.lookup("t1", "level_up");
        // Second call should hit cache
        Optional<EventDefinition> result = eventRouter.lookup("t1", "level_up");
        assertTrue(result.isPresent());
        assertEquals("level_up", result.get().getEventName());
    }

    @Test
    void shouldCallback_trueWhenConversionTypePresent() {
        assertTrue(eventRouter.shouldCallback("t1", "activate"));
    }

    @Test
    void shouldCallback_falseWhenNoConversionType() {
        EventDefinition noCb = new EventDefinition();
        noCb.setGameId("t1");
        noCb.setEventName("internal_event");
        noCb.setConversionType(null);
        noCb.setEnabled(true);
        eventDefRepo.save(noCb);

        assertFalse(eventRouter.shouldCallback("t1", "internal_event"));
    }

    @Test
    void clearCache_evictsEntry() {
        eventRouter.lookup("t1", "level_up");
        eventRouter.clearCache("t1", "level_up");
        // Should reload from DB (not throw)
        assertTrue(eventRouter.lookup("t1", "level_up").isPresent());
    }
}
