package com.attribution.admin.service;

import com.attribution.admin.dto.DashboardDTO;
import com.attribution.common.constant.EventConstants;
import com.attribution.common.enums.CallbackStatus;
import com.attribution.common.entity.AttributionRecord;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private static final String DASHBOARD_CACHE_KEY = "attribution:dashboard:cache";
    private static final long DASHBOARD_CACHE_TTL_SECONDS = 300; // 5 minutes

    private final AttributionRecordRepository attributionRepo;
    private final ClickRecordRepository clickRepo;
    private final GameConfigRepository gameConfigRepo;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public AnalyticsService(AttributionRecordRepository attributionRepo,
                            ClickRecordRepository clickRepo,
                            GameConfigRepository gameConfigRepo,
                            StringRedisTemplate stringRedisTemplate,
                            ObjectMapper objectMapper) {
        this.attributionRepo = attributionRepo;
        this.clickRepo = clickRepo;
        this.gameConfigRepo = gameConfigRepo;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns dashboard data with Redis caching.
     * Cache TTL is 5 minutes to balance freshness and DB load.
     */
    public DashboardDTO getDashboard() {
        try {
            String cached = stringRedisTemplate.opsForValue().get(DASHBOARD_CACHE_KEY);
            if (cached != null && !cached.isEmpty()) {
                return objectMapper.readValue(cached, DashboardDTO.class);
            }
        } catch (Exception e) {
            log.debug("Dashboard 缓存读取失败，回退到数据库查询", e);
        }

        DashboardDTO dto = buildDashboardFromDb();

        try {
            String json = objectMapper.writeValueAsString(dto);
            stringRedisTemplate.opsForValue()
                    .set(DASHBOARD_CACHE_KEY, json, Duration.ofSeconds(DASHBOARD_CACHE_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("Dashboard 缓存写入失败", e);
        }

        return dto;
    }

    private DashboardDTO buildDashboardFromDb() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);

        DashboardDTO dto = new DashboardDTO();
        dto.setTodayClicks(clickRepo.countByCreatedAtBetween(todayStart, todayEnd));
        dto.setTodayActivates(attributionRepo.countByEventAndSuccess(EventConstants.ACTIVATE, todayStart, todayEnd));
        dto.setTodayPurchases(attributionRepo.countByEventAndSuccess(EventConstants.PURCHASE, todayStart, todayEnd));
        Double revenue = attributionRepo.sumRevenueByDate(todayStart, todayEnd);
        dto.setTodayRevenue(revenue != null ? revenue : 0.0);
        dto.setTotalGames(gameConfigRepo.countByStatusTrue());

        long totalCallbacks = attributionRepo.countCallbackAttemptsByDate(CallbackStatus.ATTEMPT_STATUSES, todayStart, todayEnd);
        long successCallbacks = attributionRepo.countCallbackAttemptsByDate(Set.of(CallbackStatus.SUCCESS.getCode()), todayStart, todayEnd);
        dto.setCallbackSuccessRate(totalCallbacks > 0 ? (double) successCallbacks / totalCallbacks : 0.0);
        return dto;
    }

    public Page<AttributionRecord> queryAttributions(String gameId, String oaid,
                                                      String eventType, String callbackStatus,
                                                      int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Specification<AttributionRecord> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (gameId != null && !gameId.isEmpty()) {
                predicates.add(cb.equal(root.get("gameId"), gameId));
            }
            if (oaid != null && !oaid.isEmpty()) {
                predicates.add(cb.equal(root.get("oaid"), oaid));
            }
            if (eventType != null && !eventType.isEmpty()) {
                predicates.add(cb.equal(root.get("eventType"), eventType));
            }
            if (callbackStatus != null && !callbackStatus.isEmpty()) {
                predicates.add(cb.equal(root.get("callbackStatus"), callbackStatus));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return attributionRepo.findAll(spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public List<AttributionRecord> latestByGame(String gameId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return attributionRepo.findLatestByGameId(gameId, PageRequest.of(0, safeLimit));
    }

    public Map<String, Object> getStats(String gameId, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        long activates = attributionRepo.countByGameAndEventAndSuccess(gameId, EventConstants.ACTIVATE, start, end);
        long purchases = attributionRepo.countByGameAndEventAndSuccess(gameId, EventConstants.PURCHASE, start, end);
        Double revenue = attributionRepo.sumRevenueByGameIdAndDate(gameId, start, end);
        long registers = attributionRepo.countByGameAndEventAndSuccess(gameId, EventConstants.REGISTER, start, end);

        return Map.of(
                "activates", activates,
                "purchases", purchases,
                "revenue", revenue != null ? revenue : 0.0,
                "registers", registers,
                "startDate", startDate.toString(),
                "endDate", endDate.toString()
        );
    }
}
