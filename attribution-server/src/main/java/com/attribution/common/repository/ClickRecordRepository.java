package com.attribution.common.repository;

import com.attribution.common.entity.ClickRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClickRecordRepository extends JpaRepository<ClickRecord, Long> {

    Optional<ClickRecord> findFirstByGameIdAndOaidAndMatchedFalseOrderByClickTimeDesc(String gameId, String oaid);

    Optional<ClickRecord> findFirstByGameIdAndGaidAndMatchedFalseOrderByClickTimeDesc(String gameId, String gaid);

    Optional<ClickRecord> findFirstByGameIdAndIdfaAndMatchedFalseOrderByClickTimeDesc(String gameId, String idfa);

    Optional<ClickRecord> findFirstByGameIdAndOaidOrderByClickTimeDesc(String gameId, String oaid);

    Optional<ClickRecord> findFirstByGameIdAndGaidOrderByClickTimeDesc(String gameId, String gaid);

    Optional<ClickRecord> findFirstByGameIdAndIdfaOrderByClickTimeDesc(String gameId, String idfa);

    long countByGameIdAndCreatedAtBetween(String gameId, java.time.LocalDateTime start, java.time.LocalDateTime end);

    @Query("SELECT c FROM ClickRecord c WHERE c.gameId = :gameId AND c.matched = false AND c.clickTime > :since ORDER BY c.clickTime DESC")
    List<ClickRecord> findUnmatchedByGameAndTime(@Param("gameId") String gameId, @Param("since") Long since);

    @Query("SELECT c FROM ClickRecord c WHERE c.gameId = :gameId AND c.matched = false AND c.clickTime > :since ORDER BY c.clickTime DESC")
    List<ClickRecord> findUnmatchedByGameAndTime(@Param("gameId") String gameId,
                                                   @Param("since") Long since,
                                                   org.springframework.data.domain.Pageable pageable);

    @Query("SELECT c FROM ClickRecord c WHERE c.gameId = :gameId AND c.clickTime > :since ORDER BY c.clickTime DESC")
    List<ClickRecord> findRecentByGameAndTime(@Param("gameId") String gameId,
                                               @Param("since") Long since,
                                               org.springframework.data.domain.Pageable pageable);

    @Query("SELECT c FROM ClickRecord c WHERE c.gameId = :gameId AND c.matched = false AND c.clickTime > :since AND c.ip LIKE CONCAT(:ipPrefix, '.%') ORDER BY c.clickTime DESC")
    List<ClickRecord> findUnmatchedByGameTimeAndIpPrefix(@Param("gameId") String gameId,
                                                          @Param("since") Long since,
                                                          @Param("ipPrefix") String ipPrefix,
                                                          org.springframework.data.domain.Pageable pageable);

    @Query("SELECT c FROM ClickRecord c WHERE c.gameId = :gameId AND c.clickTime > :since AND c.ip LIKE CONCAT(:ipPrefix, '.%') ORDER BY c.clickTime DESC")
    List<ClickRecord> findRecentByGameTimeAndIpPrefix(@Param("gameId") String gameId,
                                                       @Param("since") Long since,
                                                       @Param("ipPrefix") String ipPrefix,
                                                       org.springframework.data.domain.Pageable pageable);

    @Query("SELECT COUNT(c) FROM ClickRecord c WHERE c.createdAt BETWEEN :start AND :end")
    long countByCreatedAtBetween(@Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);

    @Modifying
    @Query(value = "DELETE FROM click_record WHERE created_at < :cutoff LIMIT :limit", nativeQuery = true)
    int deleteByCreatedAtBefore(@Param("cutoff") java.time.LocalDateTime cutoff, @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM click_record WHERE created_at < :cutoff", nativeQuery = true)
    long countByCreatedAtBefore(@Param("cutoff") java.time.LocalDateTime cutoff);
}
