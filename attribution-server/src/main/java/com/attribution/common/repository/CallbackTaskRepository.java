package com.attribution.common.repository;

import com.attribution.common.entity.CallbackTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface CallbackTaskRepository extends JpaRepository<CallbackTask, Long> {

    List<CallbackTask> findTop50ByStatusInAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            Collection<String> statuses, LocalDateTime cutoff);

    @Transactional
    @Modifying
    @Query("UPDATE CallbackTask t SET t.status = 'sending', t.lockedAt = :lockedAt, t.updatedAt = :lockedAt " +
            "WHERE t.id = :id AND t.status IN :statuses")
    int claimTask(@Param("id") Long id,
                  @Param("statuses") Collection<String> statuses,
                  @Param("lockedAt") LocalDateTime lockedAt);

    @Transactional
    @Modifying
    @Query("UPDATE CallbackTask t SET t.status = 'retry_pending', t.nextRetryAt = :now, " +
            "t.lockedAt = null, t.lastError = :lastError, t.updatedAt = :now " +
            "WHERE t.status = 'sending' AND t.lockedAt IS NOT NULL AND t.lockedAt < :cutoff")
    int resetStaleSendingTasks(@Param("cutoff") LocalDateTime cutoff,
                               @Param("now") LocalDateTime now,
                               @Param("lastError") String lastError);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM callback_task WHERE status IN :statuses AND created_at < :cutoff LIMIT :limit", nativeQuery = true)
    int deleteCompletedBefore(@Param("cutoff") LocalDateTime cutoff,
                               @Param("statuses") Collection<String> statuses,
                               @Param("limit") int limit);
}
