package com.aigateway.service;

import com.aigateway.store.SettingsStoreService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 背景图管理 —— 内置图（jar 内只读）与上传图（data/backgrounds/ 可写）两条来源。
 *
 * 上传的安全边界（这块必须做实，因为是往磁盘写文件）：
 *   1. 文件名完全由服务端生成，绝不采用客户端传来的名字 —— 掐死 ../../ 路径穿越
 *   2. 扩展名由文件魔数决定，不看客户端后缀 —— 改后缀的可执行文件进不来
 *   3. 大小上限 10MB，数量上限 20 张
 *   4. 删除时校验文件名字符集，并确认 normalize 后仍在 backgrounds 目录内
 */
@Service
public class BackgroundService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundService.class);

    /**
     * 内置图清单。硬编码而非扫描 —— 打进 jar 后 classpath 目录遍历在不同打包方式下行为不一致，
     * 而这批图是随代码走的固定资源，写死最可靠。新增内置图时在这里加一行。
     */
    private static final List<String> BUILTIN_IMAGES = List.of(
            "/images/platform-bg.jpg",
            "/images/platform-bg2.jpg",
            "/images/platform-bg4.jpg",
            "/images/platform-bg5.jpg",
            "/images/platform-bg6.jpg");

    /** 上传图的对外访问前缀，由 StaticResourceConfig 映射到 data/backgrounds/ */
    public static final String UPLOAD_URL_PREFIX = "/bg/";

    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_UPLOAD_COUNT = 20;

    /** 删除接口只接受这种纯文件名，任何斜杠 / 点点都直接拒绝 */
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SettingsStoreService settingsStore;
    private final SecureRandom random = new SecureRandom();

    public BackgroundService(SettingsStoreService settingsStore) {
        this.settingsStore = settingsStore;
    }

    // ==================== 查询 ====================

    public BackgroundList list() {
        List<BackgroundItem> builtin = new ArrayList<>();
        for (String url : BUILTIN_IMAGES) {
            builtin.add(new BackgroundItem(url, url.substring(url.lastIndexOf('/') + 1), true, 0L));
        }
        return new BackgroundList(builtin, listUploaded(),
                settingsStore.getBackgroundUrl(), MAX_UPLOAD_COUNT);
    }

    private List<BackgroundItem> listUploaded() {
        List<BackgroundItem> result = new ArrayList<>();
        Path dir = settingsStore.getBackgroundDir();
        if (dir == null || !Files.isDirectory(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp")) continue;
                long size = 0L;
                try {
                    size = Files.size(p);
                } catch (IOException ignored) {
                }
                result.add(new BackgroundItem(UPLOAD_URL_PREFIX + name, name, false, size));
            }
        } catch (IOException e) {
            log.error("[BG] 读取上传目录失败: {}", e.getMessage());
        }
        result.sort(Comparator.comparing(BackgroundItem::getFilename).reversed());
        return result;
    }

    // ==================== 切换 ====================

    /** 切换当前背景图。只接受内置清单或确实存在的上传文件，杜绝把任意 URL 写进设置。 */
    public String apply(String url, String operator) {
        if (url == null || url.isBlank()) {
            throw new BackgroundException(400, "背景图地址不能为空");
        }
        String target = url.trim();

        if (BUILTIN_IMAGES.contains(target)) {
            settingsStore.updateBackgroundUrl(target, operator);
            return target;
        }

        if (target.startsWith(UPLOAD_URL_PREFIX)) {
            String name = target.substring(UPLOAD_URL_PREFIX.length());
            Path file = resolveUploaded(name);
            if (!Files.isRegularFile(file)) {
                throw new BackgroundException(404, "图片不存在: " + name);
            }
            settingsStore.updateBackgroundUrl(target, operator);
            return target;
        }

        throw new BackgroundException(400, "不支持的背景图地址，仅允许内置图或已上传图");
    }

    public String reset(String operator) {
        settingsStore.updateBackgroundUrl(SettingsStoreService.DEFAULT_BACKGROUND, operator);
        return SettingsStoreService.DEFAULT_BACKGROUND;
    }
    // ==================== 上传 ====================

    /**
     * 保存上传的图片并立即应用为当前背景。
     * 返回新图片的访问 URL。
     */
    public String upload(MultipartFile file, String operator) {
        if (file == null || file.isEmpty()) {
            throw new BackgroundException(400, "未选择文件");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BackgroundException(400, "图片超过 10MB 限制，当前 "
                    + (file.getSize() / 1024 / 1024) + "MB");
        }
        if (listUploaded().size() >= MAX_UPLOAD_COUNT) {
            throw new BackgroundException(400,
                    "已上传图片达到上限 " + MAX_UPLOAD_COUNT + " 张，请先删除部分图片");
        }

        // 扩展名完全由魔数决定，客户端文件名一个字符都不采用
        String ext = detectByMagic(readHead(file, 12));
        if (ext == null) {
            throw new BackgroundException(400, "文件内容不是有效的 JPEG / PNG / WebP 图片");
        }

        String filename = "bg-" + LocalDateTime.now().format(DATE_FMT) + "-" + randomHex(4) + "." + ext;
        Path dir = settingsStore.getBackgroundDir();
        Path target = dir.resolve(filename);
        Path tmp = dir.resolve(filename + ".tmp");

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            log.error("[BG] 上传落盘失败: {}", e.getMessage());
            throw new BackgroundException(500, "图片保存失败: " + e.getMessage());
        }

        String url = UPLOAD_URL_PREFIX + filename;
        settingsStore.updateBackgroundUrl(url, operator);
        log.info("[BG] 上传成功 file={} size={}KB by={}", filename, file.getSize() / 1024, operator);
        return url;
    }

    // ==================== 删除 ====================

    /**
     * 删除已上传的图片。内置图不可删。
     * 若删掉的正是当前生效的图，自动回落到默认图，避免页面背景 404。
     */
    public void delete(String filename, String operator) {
        Path file = resolveUploaded(filename);
        if (!Files.isRegularFile(file)) {
            throw new BackgroundException(404, "图片不存在: " + filename);
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new BackgroundException(500, "删除失败: " + e.getMessage());
        }

        String url = UPLOAD_URL_PREFIX + filename;
        if (url.equals(settingsStore.getBackgroundUrl())) {
            settingsStore.updateBackgroundUrl(SettingsStoreService.DEFAULT_BACKGROUND, operator);
            log.info("[BG] 删除的是当前背景图，已回落默认图");
        }
        log.info("[BG] 删除成功 file={} by={}", filename, operator);
    }

    // ==================== 内部工具 ====================

    /**
     * 把文件名解析成 backgrounds 目录下的路径。
     * 双重防线：先做字符集白名单，再确认 normalize 后仍在目录内。
     */
    private Path resolveUploaded(String filename) {
        if (filename == null || !SAFE_FILENAME.matcher(filename).matches()) {
            throw new BackgroundException(400, "非法文件名");
        }
        Path dir = settingsStore.getBackgroundDir().toAbsolutePath().normalize();
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(dir)) {
            log.warn("[BG] 拦截路径穿越尝试: {}", filename);
            throw new BackgroundException(400, "非法文件名");
        }
        return target;
    }

    private byte[] readHead(MultipartFile file, int n) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[n];
            int read = in.readNBytes(buf, 0, n);
            if (read < n) {
                byte[] shorter = new byte[Math.max(read, 0)];
                System.arraycopy(buf, 0, shorter, 0, Math.max(read, 0));
                return shorter;
            }
            return buf;
        } catch (IOException e) {
            throw new BackgroundException(400, "读取文件内容失败: " + e.getMessage());
        }
    }

    /** 返回魔数对应的扩展名，不是已知图片格式则返回 null */
    private String detectByMagic(byte[] h) {
        if (h.length >= 3
                && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (h.length >= 8
                && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && (h[4] & 0xFF) == 0x0D && (h[5] & 0xFF) == 0x0A
                && (h[6] & 0xFF) == 0x1A && (h[7] & 0xFF) == 0x0A) {
            return "png";
        }
        if (h.length >= 12
                && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    // ==================== DTO ====================

    @Data
    @AllArgsConstructor
    public static class BackgroundItem {
        private String url;
        private String filename;
        private boolean builtin;
        private long size;
    }

    @Data
    @AllArgsConstructor
    public static class BackgroundList {
        private List<BackgroundItem> builtin;
        private List<BackgroundItem> uploaded;
        private String current;
        private int maxUpload;
    }

    /** 携带建议 HTTP 状态码，由 Controller 直接映射 */
    public static class BackgroundException extends RuntimeException {
        private final int status;

        public BackgroundException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
