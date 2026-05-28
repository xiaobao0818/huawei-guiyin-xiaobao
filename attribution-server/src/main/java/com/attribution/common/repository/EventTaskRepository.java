package com.attribution.common.repository;

import com.attribution.common.entity.EventTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EventTaskRepository extends JpaRepository<EventTask, Long> {

    List<EventTask> findTop50ByStatusOrderByCreatedAtAsc(String status);

    @Transactional
    @Modifying
    @Query("UPDATE EventTask t SET t.status = :toStatus, t.updatedAt = :now WHERE t.id = :id AND t.status = :fromStatus")
    int claimTask(@Param("id") Long id, @Param("fromStatus") String fromStatus,
                  @Param("toStatus") String toStatus, @Param("now") LocalDateTime now);

    List<EventTask> findByStatusOrderByCreatedAtAsc(String status);

    @Transactional
    @Modifying
    @Query("UPDATE EventTask t SET t.status = 'pending', t.updatedAt = :now WHERE t.status = 'processing' AND t.updatedAt < :cutoff")
    int resetStaleProcessingTasks(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("DELETE FROM EventTask t WHERE t.status = 'done' AND t.createdAt < :cutoff")
    int deleteDoneBefore(@Param("cutoff") LocalDateTime cutoff);
}
