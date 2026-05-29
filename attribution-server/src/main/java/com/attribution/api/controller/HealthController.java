package com.attribution.api.controller;

import com.attribution.common.dto.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final StringRedisTemplate stringRedisTemplate;

    public HealthController(DataSource dataSource,
                            StringRedisTemplate stringRedisTemplate) {
        this.dataSource = dataSource;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @GetMapping("/api/v1/health")
    public ResponseEntity<R<Map<String, Object>>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        boolean databaseUp = checkDatabase();
        boolean redisUp = checkRedis();
        boolean ready = databaseUp && redisUp;

        info.put("status", ready ? "UP" : "DOWN");
        info.put("service", "attribution-server");
        info.put("time", LocalDateTime.now().toString());

        info.put("database", databaseUp ? "UP" : "DOWN");
        info.put("redis", redisUp ? "UP" : "DOWN");

        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(R.ok(info));
    }

    @GetMapping("/api/v1/health/live")
    public R<Map<String, Object>> liveness() {
        return R.ok(Map.of("status", "UP"));
    }

    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(3);
        } catch (Exception e) {
            log.warn("健康检查 - 数据库连接失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkRedis() {
        try {
            String pong = stringRedisTemplate.getConnectionFactory()
                    .getConnection().ping();
            return "PONG".equals(pong);
        } catch (Exception e) {
            log.warn("健康检查 - Redis连接失败: {}", e.getMessage());
            return false;
        }
    }
}
