package com.aigateway.store;

import com.aigateway.model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理后台用户存储 —— 独立于 FileStoreService / GroupStoreService，互不影响。
 *
 * data/users.json 的加载与持久化，首次启动 seed 默认账号 admin/admin。
 * 密码用 JDK 自带的 PBKDF2WithHmacSHA256，不引入 spring-security 依赖。
 */
@Service
public class UserStoreService {

    private static final Logger log = LoggerFactory.getLogger(UserStoreService.class);

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    @Value("${ai-gateway.data-dir:data}")
    private String dataDir;

    private final Map<Long, User> cache = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);
    private final SecureRandom random = new SecureRandom();

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Path userFile;

    @PostConstruct
    public void init() throws IOException {
        Path dataPath = Paths.get(dataDir);
        Files.createDirectories(dataPath);
        userFile = dataPath.resolve("users.json");
        load();
        ensureDefaultUser();
    }

    // ==================== 查询 ====================

    public List<User> getAll() {
        List<User> list = new ArrayList<>(cache.values());
        list.sort(Comparator.comparing(User::getId));
        return list;
    }

    public Optional<User> findByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String key = username.trim().toLowerCase();
        return cache.values().stream()
                .filter(u -> key.equals(u.getUsername()))
                .findFirst();
    }

    // ==================== 认证 ====================

    /**
     * 校验用户名密码。
     *
     * 用户不存在时依然走一次 PBKDF2 计算（对随机盐），
     * 让「用户不存在」与「密码错误」的耗时接近，避免通过响应时间枚举用户名。
     */
    public Optional<User> authenticate(String username, String rawPassword) {
        Optional<User> uo = findByUsername(username);
        if (uo.isEmpty()) {
            derive(rawPassword == null ? "" : rawPassword, randomSaltBase64());
            return Optional.empty();
        }
        User u = uo.get();
        if (!"ACTIVE".equalsIgnoreCase(u.getStatus())) {
            return Optional.empty();
        }
        String candidate = derive(rawPassword == null ? "" : rawPassword, u.getSalt());
        if (candidate == null || u.getPasswordHash() == null) return Optional.empty();

        boolean ok = MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                u.getPasswordHash().getBytes(StandardCharsets.UTF_8));
        return ok ? Optional.of(u) : Optional.empty();
    }

    /** 登录成功后回写最近登录时间 */
    public void touchLoginTime(User user) {
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        cache.put(user.getId(), user);
        persist();
    }

    /** 修改密码，重新生成盐 */
    public void changePassword(User user, String newRawPassword) {
        String salt = randomSaltBase64();
        user.setSalt(salt);
        user.setPasswordHash(derive(newRawPassword, salt));
        user.setUpdatedAt(LocalDateTime.now());
        cache.put(user.getId(), user);
        persist();
        log.info("[AUTH] 用户 {} 密码已更新", user.getUsername());
    }

    // ==================== 初始化与持久化 ====================

    private void ensureDefaultUser() {
        if (findByUsername(DEFAULT_USERNAME).isPresent()) return;

        String salt = randomSaltBase64();
        User u = new User();
        u.setId(idSeq.incrementAndGet());
        u.setUsername(DEFAULT_USERNAME);
        u.setSalt(salt);
        u.setPasswordHash(derive(DEFAULT_PASSWORD, salt));
        u.setRole("ADMIN");
        u.setStatus("ACTIVE");
        u.setRemark("首次启动自动创建的默认管理员");
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        cache.put(u.getId(), u);
        persist();
        log.info("[AUTH] 已初始化默认管理员 username={} password={}（建议登录后修改）",
                DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }

    private void load() {
        try {
            if (Files.exists(userFile) && Files.size(userFile) > 0) {
                List<User> list = mapper.readValue(userFile.toFile(),
                        new TypeReference<List<User>>() {});
                for (User u : list) {
                    if (u.getId() == null) continue;
                    if (u.getUsername() != null) {
                        u.setUsername(u.getUsername().trim().toLowerCase());
                    }
                    cache.put(u.getId(), u);
                    if (u.getId() > idSeq.get()) idSeq.set(u.getId());
                }
            }
        } catch (IOException e) {
            log.error("Failed to load users.json: {}", e.getMessage());
        }
    }

    private synchronized void persist() {
        List<User> list = new ArrayList<>(cache.values());
        list.sort(Comparator.comparing(User::getId));
        try {
            Path tmp = userFile.resolveSibling(userFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), list);
            Files.move(tmp, userFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to persist users.json: {}", e.getMessage());
        }
    }

    // ==================== 密码派生 ====================

    private String randomSaltBase64() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private String derive(String rawPassword, String saltBase64) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(),
                    salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance(PBKDF2_ALGO);
            return Base64.getEncoder().encodeToString(f.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            log.error("[AUTH] 密码派生失败: {}", e.getMessage());
            return null;
        }
    }
}
