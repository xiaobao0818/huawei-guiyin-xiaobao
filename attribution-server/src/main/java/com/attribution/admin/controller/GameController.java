package com.attribution.admin.controller;

import com.attribution.admin.dto.GameConfigDTO;
import com.attribution.admin.service.GameService;
import com.attribution.common.dto.R;
import com.attribution.common.entity.GameConfig;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping
    public R<List<GameConfig>> list() {
        return R.ok(gameService.listAll());
    }

    @GetMapping("/{id}")
    public R<GameConfig> get(@PathVariable Long id) {
        GameConfig config = gameService.getById(id);
        return config != null ? R.ok(config) : R.fail(404, "游戏不存在");
    }

    @PostMapping
    public R<GameConfig> create(@Valid @RequestBody GameConfigDTO dto) {
        try {
            return R.ok(gameService.create(dto));
        } catch (Exception e) {
            return R.fail(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public R<GameConfig> update(@PathVariable Long id, @Valid @RequestBody GameConfigDTO dto) {
        try {
            return R.ok(gameService.update(id, dto));
        } catch (Exception e) {
            return R.fail(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        gameService.delete(id);
        return R.ok();
    }
}
