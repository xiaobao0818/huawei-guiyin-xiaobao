package com.attribution.admin.controller;

import com.attribution.admin.dto.EventConfigDTO;
import com.attribution.admin.service.EventConfigService;
import com.attribution.common.dto.R;
import com.attribution.common.entity.EventDefinition;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/api")
public class EventConfigController {

    private final EventConfigService eventConfigService;

    public EventConfigController(EventConfigService eventConfigService) {
        this.eventConfigService = eventConfigService;
    }

    @GetMapping("/games/{gameId}/events")
    public R<List<EventDefinition>> listByGame(@PathVariable String gameId) {
        return R.ok(eventConfigService.listByGame(gameId));
    }

    @GetMapping("/events/{id}")
    public R<EventDefinition> get(@PathVariable Long id) {
        EventDefinition def = eventConfigService.getById(id);
        return def != null ? R.ok(def) : R.fail(404, "事件不存在");
    }

    @PostMapping("/events")
    public R<EventDefinition> create(@Valid @RequestBody EventConfigDTO dto) {
        try {
            return R.ok(eventConfigService.create(dto));
        } catch (Exception e) {
            return R.fail(e.getMessage());
        }
    }

    @PutMapping("/events/{id}")
    public R<EventDefinition> update(@PathVariable Long id, @Valid @RequestBody EventConfigDTO dto) {
        try {
            return R.ok(eventConfigService.update(id, dto));
        } catch (Exception e) {
            return R.fail(e.getMessage());
        }
    }

    @DeleteMapping("/events/{id}")
    public R<Void> delete(@PathVariable Long id) {
        try {
            eventConfigService.delete(id);
            return R.ok();
        } catch (Exception e) {
            return R.fail(e.getMessage());
        }
    }
}
