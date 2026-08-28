package com.aigateway.controller;

import com.aigateway.config.AdminAuthFilter;
import com.aigateway.dto.ApiResponse;
import com.aigateway.model.AppSettings;
import com.aigateway.service.BackgroundService;
import com.aigateway.store.SettingsStoreService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 应用设置接口。
 *
 * 全部在 AdminAuthFilter 的默认拦截范围内（/api/settings 不在白名单），必须登录。
 * 注意上传图片本身的访问路径 /bg/** 是放行的 —— 登录页要显示背景图。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final BackgroundService backgroundService;
    private final SettingsStoreService settingsStore;

    public SettingsController(BackgroundService backgroundService,
                              SettingsStoreService settingsStore) {
        this.backgroundService = backgroundService;
        this.settingsStore = settingsStore;
    }

    /** 当前全部设置 */
    @GetMapping
    public ApiResponse<AppSettings> get() {
        return ApiResponse.success(settingsStore.get());
    }

    /** 可选背景图清单：内置 + 已上传 + 当前生效 */
    @GetMapping("/backgrounds")
    public ApiResponse<BackgroundService.BackgroundList> backgrounds() {
        return ApiResponse.success(backgroundService.list());
    }

    /** 切换到已有的图（内置或已上传） */
    @PostMapping("/background")
    public ResponseEntity<ApiResponse<Map<String, String>>> apply(
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
            HttpServletRequest request) {
        try {
            String url = backgroundService.apply(body.get("url"), operator(request));
            return ResponseEntity.ok(ApiResponse.success(Map.of("url", url)));
        } catch (BackgroundService.BackgroundException e) {
            return fail(e);
        }
    }

    /** 上传新图，成功后自动应用为当前背景 */
    @PostMapping("/background/upload")
    public ResponseEntity<ApiResponse<Map<String, String>>> upload(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        try {
            String url = backgroundService.upload(file, operator(request));
            return ResponseEntity.ok(ApiResponse.success(Map.of("url", url)));
        } catch (BackgroundService.BackgroundException e) {
            return fail(e);
        }
    }

    /** 删除已上传的图，内置图不可删 */
    @DeleteMapping("/background/upload/{filename}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(
            @PathVariable String filename,
            HttpServletRequest request) {
        try {
            backgroundService.delete(filename, operator(request));
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("current", settingsStore.getBackgroundUrl())));
        } catch (BackgroundService.BackgroundException e) {
            return fail(e);
        }
    }

    /** 恢复出厂默认背景图 */
    @PostMapping("/background/reset")
    public ApiResponse<Map<String, String>> reset(HttpServletRequest request) {
        String url = backgroundService.reset(operator(request));
        return ApiResponse.success(Map.of("url", url));
    }

    private ResponseEntity<ApiResponse<Map<String, String>>> fail(
            BackgroundService.BackgroundException e) {
        log.warn("[SETTINGS] 操作失败 status={} msg={}", e.getStatus(), e.getMessage());
        return ResponseEntity.status(HttpStatus.valueOf(e.getStatus()))
                .body(ApiResponse.error(e.getStatus(), e.getMessage()));
    }

    private String operator(HttpServletRequest request) {
        Object u = request.getAttribute(AdminAuthFilter.ATTR_USER);
        return u == null ? "-" : String.valueOf(u);
    }
}
