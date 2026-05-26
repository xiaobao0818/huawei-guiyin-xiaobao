package com.attribution.core.matcher;

import com.attribution.common.entity.ClickRecord;
import com.attribution.common.repository.ClickRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FingerprintMatcher {

    private static final Logger log = LoggerFactory.getLogger(FingerprintMatcher.class);

    /** Maximum number of unmatched click candidates to load from DB per match attempt. */
    private static final int MAX_CANDIDATES = 500;

    private final ClickRecordRepository clickRepo;

    public FingerprintMatcher(ClickRecordRepository clickRepo) {
        this.clickRepo = clickRepo;
    }

    /**
     * Try to match a device fingerprint to a recent click record using IP and User-Agent.
     * <p>
     * Strategy: first query with IP prefix filter (DB-level), fall back to full-scan
     * if no candidates found. Candidate count is capped at {@link #MAX_CANDIDATES}.
     *
     * @param gameId        the game identifier
     * @param fingerprint   map containing "ip" and "user_agent" (or "ua")
     * @param windowMinutes lookback window in minutes
     * @return the matched click record ID, or null if no match
     */
    public Long matchByFingerprint(String gameId, Map<String, String> fingerprint, int windowMinutes) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return null;
        }

        String requestIp = fingerprint.getOrDefault("ip", "");
        String requestUa = fingerprint.getOrDefault("user_agent",
                fingerprint.getOrDefault("ua", ""));

        if (requestIp.isEmpty()) {
            log.debug("指纹匹配: 无 IP，放弃");
            return null;
        }

        long since = System.currentTimeMillis() - (long) windowMinutes * 60 * 1000;

        // 1. Try DB-level IP prefix filter first (most efficient)
        String ipPrefix = requestIp.contains(".") ? ipPrefix(requestIp) : requestIp;
        List<ClickRecord> candidates = clickRepo.findUnmatchedByGameTimeAndIpPrefix(
                gameId, since, ipPrefix, PageRequest.of(0, MAX_CANDIDATES));

        // 2. Fall back to full scan if no IP-prefix matches found
        if (candidates.isEmpty()) {
            List<ClickRecord> fullList = clickRepo.findUnmatchedByGameAndTime(gameId, since);
            candidates = fullList.size() > MAX_CANDIDATES
                    ? fullList.subList(0, MAX_CANDIDATES)
                    : fullList;
        }

        ClickRecord bestMatch = null;
        int bestScore = 0;

        for (ClickRecord click : candidates) {
            int score = 0;

            if (click.getIp() != null && !click.getIp().isEmpty()) {
                if (click.getIp().equals(requestIp)) {
                    score += 3;
                } else if (ipPrefix(click.getIp()).equals(ipPrefix(requestIp))) {
                    score += 1;
                }
            }

            if (click.getUserAgent() != null && !click.getUserAgent().isEmpty()
                    && click.getUserAgent().equals(requestUa)) {
                score += 2;
            }

            if (score > bestScore) {
                bestScore = score;
                bestMatch = click;
            }
        }

        if (bestMatch != null && bestScore >= 2) {
            String maskedIp = requestIp.length() > 8
                    ? requestIp.substring(0, Math.min(requestIp.length(), 8)) + "***"
                    : requestIp;
            log.info("指纹匹配成功: game={}, score={}, ip={}", gameId, bestScore, maskedIp);
            return bestMatch.getId();
        }

        log.debug("指纹匹配失败: game={}, candidateCount={}, bestScore={}",
                gameId, candidates.size(), bestScore);
        return null;
    }

    private String ipPrefix(String ip) {
        if (ip == null || ip.isEmpty()) return "";
        int idx = ip.lastIndexOf('.');
        return idx > 0 ? ip.substring(0, idx) : ip;
    }
}
