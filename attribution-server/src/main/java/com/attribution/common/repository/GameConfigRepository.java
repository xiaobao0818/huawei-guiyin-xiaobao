package com.attribution.common.repository;

import com.attribution.common.entity.GameConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface GameConfigRepository extends JpaRepository<GameConfig, Long> {
    Optional<GameConfig> findByGameId(String gameId);
    Optional<GameConfig> findByGameIdAndStatusTrue(String gameId);
    List<GameConfig> findByStatusTrue();
    boolean existsByGameId(String gameId);
    long countByStatusTrue();
}
