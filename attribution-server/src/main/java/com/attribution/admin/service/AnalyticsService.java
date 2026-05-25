package com.attribution.admin.service;

import com.attribution.admin.dto.DashboardDTO;
import com.attribution.common.entity.AttributionRecord;
import com.attribution.common.repository.AttributionRecordRepository;
import com.attribution.common.repository.ClickRecordRepository;
import com.attribution.common.repository.GameConfigRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    private final AttributionRecordRepository attributionRepo;
    private final ClickRecordRepository clickRepo;
    private final GameConfigRepository gameConfigRepo;

    public AnalyticsService(AttributionRecordRepository attributionRepo,
                            ClickRecordRepository clickRepo,
                            GameConfigRepository gameConfigRepo) {
        this.attributionRepo = attributionRepo;
        this.clickRepo = clickRepo;
        this.gameConfigRepo = gameConfigRepo;
    }

    public DashboardDTO getDashboard() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);

        DashboardDTO dto = new DashboardDTO();
        dto.setTodayClicks(clickRepo.countByCreatedAtBetween(todayStart, todayEnd));
        dto.setTodayActivates(attributionRepo.countByEventAndSuccess("activate", todayStart, todayEnd));
        dto.setTodayPurchases(attributionRepo.countByEventAndSuccess("purchase", todayStart, todayEnd));
        Double revenue = attributionRepo.sumRevenueByDate(todayStart, todayEnd);
        dto.setTodayRevenue(revenue != null ? revenue : 0.0);
        dto.setTotalGames(gameConfigRepo.count());

        long totalCallbacks = attributionRepo.countByCreatedAtBetween(todayStart, todayEnd);
        long successCallbacks = attributionRepo.countByCallbackStatusAndDate("success", todayStart, todayEnd);
        dto.setCallbackSuccessRate(totalCallbacks > 0 ? (double) successCallbacks / totalCallbacks : 0.0);
        return dto;
    }

    public Page<AttributionRecord> queryAttributions(String gameId, String oaid,
                                                      String eventType, String callbackStatus,
                                                      int page, int size) {
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
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public List<AttributionRecord> latestByGame(String gameId, int limit) {
        return attributionRepo.findTop50ByGameIdOrderByCreatedAtDesc(gameId);
    }

    public Map<String, Object> getStats(String gameId, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        long activates = attributionRepo.countByGameAndEventAndSuccess(gameId, "activate", start, end);
        long purchases = attributionRepo.countByGameAndEventAndSuccess(gameId, "purchase", start, end);
        Double revenue = attributionRepo.sumRevenueByGameIdAndDate(gameId, start, end);
        long registers = attributionRepo.countByGameAndEventAndSuccess(gameId, "register", start, end);

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
