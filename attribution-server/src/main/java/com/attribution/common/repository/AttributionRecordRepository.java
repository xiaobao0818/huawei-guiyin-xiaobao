package com.attribution.common.repository;

import com.attribution.common.entity.AttributionRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttributionRecordRepository extends JpaRepository<AttributionRecord, Long>,
        JpaSpecificationExecutor<AttributionRecord> {

    Optional<AttributionRecord> findFirstByGameIdAndOaidAndEventType(String gameId, String oaid, String eventType);

    Optional<AttributionRecord> findFirstByGameIdAndOaidAndEventTypeOrderByCreatedAtDesc(String gameId, String oaid, String eventType);

    boolean existsByGameIdAndOaidAndEventType(String gameId, String oaid, String eventType);

    List<AttributionRecord> findByGameIdAndEventTypeAndCallbackStatusAndCreatedAtAfter(String gameId, String eventType, String callbackStatus, java.time.LocalDateTime after);

    boolean existsByDedupeKey(String dedupeKey);

    @Query("SELECT a FROM AttributionRecord a WHERE a.gameId = :gameId ORDER BY a.createdAt DESC")
    List<AttributionRecord> findByGameId(@Param("gameId") String gameId);

    List<AttributionRecord> findTop50ByGameIdOrderByCreatedAtDesc(String gameId);

    @Query("SELECT a FROM AttributionRecord a WHERE a.gameId = :gameId ORDER BY a.createdAt DESC")
    List<AttributionRecord> findLatestByGameId(@Param("gameId") String gameId, Pageable pageable);

    @Query("SELECT COUNT(a) FROM AttributionRecord a WHERE a.gameId = :gameId AND a.eventType = :eventType AND a.callbackStatus = 'success' AND a.createdAt BETWEEN :start AND :end")
    long countByGameAndEventAndSuccess(@Param("gameId") String gameId,
                                        @Param("eventType") String eventType,
                                        @Param("start") java.time.LocalDateTime start,
                                        @Param("end") java.time.LocalDateTime end);

    @Query("SELECT COALESCE(SUM(a.revenue), 0) FROM AttributionRecord a WHERE a.gameId = :gameId AND a.callbackStatus = 'success' AND a.createdAt BETWEEN :start AND :end")
    Double sumRevenueByGameIdAndDate(@Param("gameId") String gameId,
                                      @Param("start") java.time.LocalDateTime start,
                                      @Param("end") java.time.LocalDateTime end);

    @Query("SELECT COUNT(a) FROM AttributionRecord a WHERE a.eventType = :eventType AND a.callbackStatus = 'success' AND a.createdAt BETWEEN :start AND :end")
    long countByEventAndSuccess(@Param("eventType") String eventType,
                                @Param("start") java.time.LocalDateTime start,
                                @Param("end") java.time.LocalDateTime end);

    @Query("SELECT COALESCE(SUM(a.revenue), 0) FROM AttributionRecord a WHERE a.callbackStatus = 'success' AND a.createdAt BETWEEN :start AND :end")
    Double sumRevenueByDate(@Param("start") java.time.LocalDateTime start,
                            @Param("end") java.time.LocalDateTime end);

    @Query("SELECT COUNT(a) FROM AttributionRecord a WHERE a.createdAt BETWEEN :start AND :end")
    long countByCreatedAtBetween(@Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);

    @Modifying
    @Query(value = "DELETE FROM attribution_record WHERE created_at < :cutoff LIMIT :limit", nativeQuery = true)
    int deleteByCreatedAtBefore(@Param("cutoff") java.time.LocalDateTime cutoff, @Param("limit") int limit);

    @Query("SELECT COUNT(a) FROM AttributionRecord a WHERE a.conversionType IS NOT NULL AND a.conversionType <> '' AND a.callbackStatus IN :statuses AND a.createdAt BETWEEN :start AND :end")
    long countCallbackAttemptsByDate(@Param("statuses") Collection<String> statuses,
                                     @Param("start") java.time.LocalDateTime start,
                                     @Param("end") java.time.LocalDateTime end);
}
