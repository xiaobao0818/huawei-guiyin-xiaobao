package com.attribution.core.matcher;

import com.attribution.common.entity.ClickRecord;
import com.attribution.common.repository.ClickRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FingerprintMatcher {

    private static final Logger log = LoggerFactory.getLogger(FingerprintMatcher.class);

    private final ClickRecordRepository clickRepo;

    public FingerprintMatcher(ClickRecordRepository clickRepo) {
        this.clickRepo = clickRepo;
    }

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
        List<ClickRecord> candidates = clickRepo.findUnmatchedByGameAndTime(gameId, since);

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
            log.info("指纹匹配成功: game={}, score={}, ip={}", gameId, bestScore,
                    requestIp.substring(0, Math.min(requestIp.length(), 8)) + "***");
            return bestMatch.getId();
        }

        log.debug("指纹匹配失败: game={}, score={}", gameId, bestScore);
        return null;
    }

    private String ipPrefix(String ip) {
        if (ip == null || ip.isEmpty()) return "";
        int idx = ip.lastIndexOf('.');
        return idx > 0 ? ip.substring(0, idx) : ip;
    }
}
