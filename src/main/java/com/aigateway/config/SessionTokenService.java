package com.aigateway.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * 无状态会话令牌服务 —— HmacSHA256 签名，不依赖 spring-security 或 JWT 库。
 *
 * token 格式: base64url(payload | . | signature)
 *   payload = base64url(username | . | issuedAt | . | expireAt)
 *   signature = HmacSHA256(payload, key)
 *
 * key 启动时随机生成（32 字节），重启后所有历史 token 失效。
 * 默认 TTL 24 小时，超过视为过期。
 */
@Component
public class SessionTokenService {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenService.class);
    private static final String HMAC_ALGO = "HmacSHA256";

    @Value("${ai-gateway.session.ttl-ms:86400000}")
    private long ttlMs;

    private final SecureRandom secureRandom = new SecureRandom();

    private byte[] signingKey;

    @PostConstruct
    public void init() {
        signingKey = new byte[32];
        secureRandom.nextBytes(signingKey);
        log.info("[AUTH] 会话签名密钥已初始化（重启后历史 token 失效），TTL={}ms", ttlMs);
    }

    /**
     * 颁发 token。
     *
     * @return base64url 字符串，可直接写入 Cookie
     */
    public String issue(String username) {
        if (username == null || username.isBlank()) return null;
        long now = System.currentTimeMillis();
        long exp = now + Math.max(ttlMs, 60_000L);
        String payloadPlain = username + "|" + now + "|" + exp;
        String payload = encodeBase64url(payloadPlain);
        String sig = hmac256(payload);
        return payload + "." + sig;
    }

    /**
     * 校验 token 并解析用户名。
     *
     * @return 验证通过返回 username，否则 Optional.empty()
     */
    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int dot = token.lastIndexOf('.');
        if (dot < 0) return Optional.empty();
        String payload = token.substring(0, dot);
        String sigProvided = token.substring(dot + 1);
        // 恒定时间比较，避免通过响应时间差逐字节伪造签名
        if (!java.security.MessageDigest.isEqual(
                sigProvided.getBytes(StandardCharsets.UTF_8),
                hmac256(payload).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        String decoded = decodeBase64url(payload);
        if (decoded == null) return Optional.empty();
        String[] parts = decoded.split("\\|");
        if (parts.length != 3) return Optional.empty();

        String username = parts[0];
        long issuedAt;
        long expireAt;
        try {
            issuedAt = Long.parseLong(parts[1]);
            expireAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (expireAt <= 0 || System.currentTimeMillis() > expireAt) {
            log.info("[AUTH] token 已过期 issuedAt={} expireAt={} now={}", issuedAt, expireAt,
                    System.currentTimeMillis());
            return Optional.empty();
        }
        return Optional.of(username);
    }

    // ==================== 工具方法 ====================

    private String hmac256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGO));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return encodeBase64url(sig);
        } catch (Exception e) {
            throw new RuntimeException("[AUTH] 签名失败", e);
        }
    }

    private static final Base64.Encoder URL_SAFE_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_SAFE_DECODER =
            Base64.getUrlDecoder();

    private String encodeBase64url(byte[] input) {
        return URL_SAFE_ENCODER.encodeToString(input);
    }

    private String encodeBase64url(String plain) {
        return encodeBase64url(plain.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeBase64url(String b64url) {
        try {
            // 补回 padding
            String padded = b64url;
            int mod = padded.length() % 4;
            if (mod != 0) padded += "=".repeat(4 - mod);
            return new String(URL_SAFE_DECODER.decode(padded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
