package com.aigateway.store;

import com.aigateway.model.AppSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

/**
 * 应用设置存储 —— 读写 data/settings.json。
 *
 * 与 GroupStoreService 一样用 .tmp + Files.move 原子替换，避免写入中断导致 JSON 损坏。
 * 设置只有一份，全内存缓存，读取零 IO。
 */
@Service
public class SettingsStoreService {

    private static final Logger log = LoggerFactory.getLogger(SettingsStoreService.class);

    /** 出厂默认背景图，位于 jar 内 static/images/ */
    public static final String DEFAULT_BACKGROUND = "/images/platform-bg.jpg";

    /** 上传图片的存放目录名（在 data-dir 之下） */
    public static final String BACKGROUND_DIR_NAME = "backgrounds";

    @Value("${ai-gateway.data-dir:data}")
    private String dataDir;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Path settingsFile;
    private Path backgroundDir;

    private volatile AppSettings cache;

    @PostConstruct
    public void init() throws IOException {
        Path dataPath = Paths.get(dataDir);
        Files.createDirectories(dataPath);
        settingsFile = dataPath.resolve("settings.json");

        backgroundDir = dataPath.resolve(BACKGROUND_DIR_NAME);
        Files.createDirectories(backgroundDir);

        load();
        log.info("[SETTINGS] 设置已加载 背景图={} 上传目录={}",
                cache.getBackgroundUrl(), backgroundDir.toAbsolutePath());
    }

    /** 上传图片目录的绝对路径，供资源映射与 BackgroundService 使用 */
    public Path getBackgroundDir() {
        return backgroundDir;
    }

    public AppSettings get() {
        return cache;
    }

    /** 当前背景图 URL，永不返回 null（数据异常时回落默认图） */
    public String getBackgroundUrl() {
        AppSettings s = cache;
        if (s == null || s.getBackgroundUrl() == null || s.getBackgroundUrl().isBlank()) {
            return DEFAULT_BACKGROUND;
        }
        return s.getBackgroundUrl();
    }

    public synchronized AppSettings updateBackgroundUrl(String url, String updatedBy) {
        AppSettings s = cache;
        s.setBackgroundUrl(url);
        s.setUpdatedAt(LocalDateTime.now());
        s.setUpdatedBy(updatedBy);
        persist();
        log.info("[SETTINGS] 背景图已切换为 {} by={}", url, updatedBy);
        return s;
    }

    private void load() {
        try {
            if (Files.exists(settingsFile) && Files.size(settingsFile) > 0) {
                cache = mapper.readValue(settingsFile.toFile(), AppSettings.class);
            }
        } catch (IOException e) {
            log.error("[SETTINGS] settings.json 读取失败，回落默认设置: {}", e.getMessage());
        }
        if (cache == null) {
            cache = new AppSettings();
            cache.setBackgroundUrl(DEFAULT_BACKGROUND);
            cache.setUpdatedAt(LocalDateTime.now());
            cache.setUpdatedBy("system");
            persist();
            log.info("[SETTINGS] 首次启动，已写入默认设置");
        }
        if (cache.getBackgroundUrl() == null || cache.getBackgroundUrl().isBlank()) {
            cache.setBackgroundUrl(DEFAULT_BACKGROUND);
        }
    }

    private void persist() {
        try {
            Path tmp = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), cache);
            Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[SETTINGS] settings.json 持久化失败: {}", e.getMessage());
        }
    }
}
