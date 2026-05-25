package com.attribution.common.repository;

import com.attribution.common.entity.EventDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventDefinitionRepository extends JpaRepository<EventDefinition, Long> {
    List<EventDefinition> findByGameIdAndEnabledTrue(String gameId);
    List<EventDefinition> findByGameId(String gameId);
    Optional<EventDefinition> findByGameIdAndEventName(String gameId, String eventName);
}
