package com.attribution;

import com.attribution.common.util.SignatureUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignatureUtilTest {

    private static final String SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1obWFj";

    @Test
    void hmacSha256_producesConsistentOutput() {
        String body = "{\"callback\":\"test\",\"conversion_type\":\"activate\"}";
        String sig1 = SignatureUtil.hmacSha256(body, SECRET);
        String sig2 = SignatureUtil.hmacSha256(body, SECRET);
        assertEquals(sig1, sig2, "HMAC should be deterministic for same input");
    }

    @Test
    void hmacSha256_differentBodyProducesDifferentSig() {
        String sig1 = SignatureUtil.hmacSha256("{\"a\":1}", SECRET);
        String sig2 = SignatureUtil.hmacSha256("{\"a\":2}", SECRET);
        assertNotEquals(sig1, sig2);
    }

    @Test
    void hmacSha256_outputIsHexString() {
        String sig = SignatureUtil.hmacSha256("test", SECRET);
        assertTrue(sig.matches("^[0-9a-f]+$"), "HMAC output should be lowercase hex");
        assertEquals(64, sig.length(), "SHA-256 produces 32 bytes = 64 hex chars");
    }

    @Test
    void buildAuthorizationHeader_containsDigestFormat() {
        String auth = SignatureUtil.buildAuthorizationHeader("{\"a\":1}", SECRET);
        assertTrue(auth.startsWith("Digest validTime=\""));
        assertTrue(auth.contains("\", response=\""));
    }

    @Test
    void buildAuthorizationHeader_containsValidTimestamp() {
        long before = System.currentTimeMillis();
        String auth = SignatureUtil.buildAuthorizationHeader("body", SECRET);
        long after = System.currentTimeMillis();

        String tsStr = auth.substring(auth.indexOf('"') + 1, auth.indexOf('"', auth.indexOf('"') + 1));
        long ts = Long.parseLong(tsStr);
        assertTrue(ts >= before && ts <= after, "Timestamp should be current time");
    }

    @Test
    void hmacSha256_acceptsEmptyBody() {
        String sig = SignatureUtil.hmacSha256("", SECRET);
        assertNotNull(sig);
        assertEquals(64, sig.length());
    }
}
