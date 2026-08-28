package com.aigateway.controller;

import com.aigateway.config.AdminAuthFilter;
import com.aigateway.config.SessionTokenService;
import com.aigateway.dto.ApiResponse;
import com.aigateway.model.User;
import com.aigateway.store.UserStoreService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 认证接口 —— 整个 /api/auth/** 在 AdminAuthFilter 白名单内，不需要登录即可访问。
 *
 * POST /api/auth/login   登录，成功后下发 HttpOnly Cookie
 * POST /api/auth/logout  登出，清除 Cookie
 * GET  /api/auth/me      查询当前登录状态（登录页判断是否已登录时用）
 * POST /api/auth/change-password  修改当前登录用户的密码
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserStoreService userStore;
    private final SessionTokenService tokenService;

    public AuthController(UserStoreService userStore, SessionTokenService tokenService) {
        this.userStore = userStore;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest body,
                                                 HttpServletResponse response) {
        String username = body == null ? null : body.username();
        String password = body == null ? null : body.password();

        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return ApiResponse.error(400, "请输入用户名和密码");
        }

        Optional<User> uo = userStore.authenticate(username, password);
        if (uo.isEmpty()) {
            log.warn("[AUTH] 登录失败 username={}", username);
            // 不区分「用户不存在」与「密码错误」，避免账号枚举
            return ApiResponse.error(401, "用户名或密码错误");
        }

        User user = uo.get();
        String token = tokenService.issue(user.getUsername());
        if (token == null) {
            return ApiResponse.error(500, "会话创建失败，请重试");
        }
        writeSessionCookie(response, token, 24 * 60 * 60);
        userStore.touchLoginTime(user);
        log.info("[AUTH] 登录成功 username={}", user.getUsername());

        Map<String, Object> data = new HashMap<>();
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        data.put("lastLoginAt", user.getLastLoginAt() == null
                ? null : user.getLastLoginAt().format(TS));
        return ApiResponse.success(data);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // /api/auth/** 在 Filter 白名单内，request attribute 不会被设置，这里自行解析用于日志
        String token = readSessionCookie(request);
        String who = token == null ? "-" : tokenService.verify(token).orElse("-");
        // Cookie 置空并立即过期
        writeSessionCookie(response, "", 0);
        log.info("[AUTH] 登出 username={}", who);
        return ApiResponse.success();
    }

    /** 当前登录状态，未登录返回 code=401（本接口在白名单里，不会被 Filter 拦） */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        String token = readSessionCookie(request);
        Optional<String> uo = token == null ? Optional.empty() : tokenService.verify(token);
        if (uo.isEmpty()) {
            return ApiResponse.error(401, "未登录");
        }
        Optional<User> user = userStore.findByUsername(uo.get());
        Map<String, Object> data = new HashMap<>();
        data.put("username", uo.get());
        data.put("role", user.map(User::getRole).orElse("ADMIN"));
        data.put("lastLoginAt", user.map(User::getLastLoginAt)
                .map(t -> t.format(TS)).orElse(null));
        return ApiResponse.success(data);
    }

    /** 修改当前登录用户密码，需带有效会话 */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest body,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        String token = readSessionCookie(request);
        Optional<String> uo = token == null ? Optional.empty() : tokenService.verify(token);
        if (uo.isEmpty()) {
            return ApiResponse.error(401, "未登录或会话已过期");
        }
        if (body == null || body.newPassword() == null || body.newPassword().length() < 4) {
            return ApiResponse.error(400, "新密码至少 4 位");
        }
        Optional<User> target = userStore.authenticate(uo.get(), body.oldPassword());
        if (target.isEmpty()) {
            return ApiResponse.error(401, "原密码不正确");
        }
        userStore.changePassword(target.get(), body.newPassword());
        // 改密后强制重新登录
        writeSessionCookie(response, "", 0);
        return ApiResponse.success();
    }

    // ==================== Cookie 读写 ====================

    /**
     * 直接写 Set-Cookie header 而不用 Cookie 对象 —— Servlet 的 Cookie API 不支持 SameSite，
     * 两者同时用会下发两条同名 Cookie，浏览器行为不确定。
     */
    private void writeSessionCookie(HttpServletResponse response, String value, int maxAgeSeconds) {
        response.addHeader("Set-Cookie", AdminAuthFilter.SESSION_COOKIE_NAME + "=" + value
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + Math.max(maxAgeSeconds, 0));
    }

    private String readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (AdminAuthFilter.SESSION_COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    public record LoginRequest(String username, String password) {}

    public record ChangePasswordRequest(String oldPassword, String newPassword) {}
}
