package com.attribution.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 签名工具 - 用于鲸鸿动能转化回传鉴权
 */
public class SignatureUtil {

    public static String hmacSha256(String body, String secretKey) {
        try {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "HmacSHA256");
            mac.init(keySpec);
            byte[] signatureBytes = mac.doFinal(bodyBytes);

            StringBuilder sb = new StringBuilder();
            for (byte b : signatureBytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256签名计算失败", e);
        }
    }

    public static String buildAuthorizationHeader(String body, String secretKey) {
        String signature = hmacSha256(body, secretKey);
        long timestamp = System.currentTimeMillis();
        return "Digest validTime=\"" + timestamp + "\", response=\"" + signature + "\"";
    }
}
