package com.attribution.common.repository;

import com.attribution.common.entity.CallbackLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CallbackLogRepository extends JpaRepository<CallbackLog, Long> {
    List<CallbackLog> findByGameIdOrderByCreatedAtDesc(String gameId);
    List<CallbackLog> findByAttributionIdOrderByCreatedAtDesc(Long attributionId);

    @Query("SELECT c FROM CallbackLog c WHERE c.gameId = :gameId ORDER BY c.createdAt DESC")
    org.springframework.data.domain.Page<CallbackLog> findByGameIdPaged(@Param("gameId") String gameId, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query(value = "DELETE FROM callback_log WHERE created_at < :cutoff LIMIT :limit", nativeQuery = true)
    int deleteByCreatedAtBefore(@Param("cutoff") java.time.LocalDateTime cutoff, @Param("limit") int limit);
}
