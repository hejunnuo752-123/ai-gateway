package com.aigateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 管理后台登录鉴权过滤器。
 *
 * 策略是「默认拦截 + 白名单放行」，比枚举需要拦的路径更安全（新增管理接口不会漏保护）。
 *
 * 白名单（永远放行）：
 *   /v1/**        旧 OpenAI 兼容路由（对外 AI 接口，不能拦）
 *   /g/**         分组路由（对外 AI 接口，由 GroupAuthFilter 用 apiKey 独立鉴权）
 *   /login        登录页
 *   /api/auth/**  登录/登出/当前用户
 *   /js/** /images/** /fonts/** /favicon.ico  静态资源
 *   /bg/**       用户上传的背景图（登录页也要显示背景，必须放行）
 *   /actuator/**  健康检查
 *   /error        Spring 默认错误页，拦了会导致错误响应变成 302
 *
 * 其余一切（/、/groups、/ai-platforms、/api/providers、/api/groups、/api/logs...）都要登录。
 *
 * 未登录时：
 *   - 页面请求（Accept 含 text/html）→ 302 /login
 *   - 其他（fetch / XHR / curl）→ 401 JSON，前端可据此跳登录页
 *
 * order=0，早于 GroupAuthFilter(order=1)；但 /g/** 在白名单里，所以对分组鉴权无影响。
 */
@Configuration
public class AdminAuthFilter {

    /** Cookie 名带项目前缀，避免和同机其他服务的 session 冲突 */
    public static final String SESSION_COOKIE_NAME = "ai-gateway-session";

    /** Controller 可通过该 key 取当前登录用户名 */
    public static final String ATTR_USER = "ai-gateway.authenticatedUser";

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    @Bean
    public FilterRegistrationBean<AdminAuthServletFilter> adminAuthFilterRegistration(
            SessionTokenService tokenService) {
        FilterRegistrationBean<AdminAuthServletFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new AdminAuthServletFilter(tokenService));
        bean.addUrlPatterns("/*");
        bean.setName("adminAuthFilter");
        bean.setOrder(0);
        return bean;
    }

    public static class AdminAuthServletFilter extends OncePerRequestFilter {

        /** 前缀匹配即放行 */
        private static final List<String> WHITE_PREFIXES = List.of(
                "/v1/", "/g/", "/api/auth/",
                "/js/", "/images/", "/fonts/", "/bg/", "/actuator/");

        /** 完整路径相等即放行 */
        private static final List<String> WHITE_EXACT = List.of(
                "/login", "/v1", "/error", "/favicon.ico");

        private final SessionTokenService tokenService;

        public AdminAuthServletFilter(SessionTokenService tokenService) {
            this.tokenService = tokenService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String path = stripContextPath(request);

            if (isWhitelisted(path)) {
                chain.doFilter(request, response);
                return;
            }

            String username = resolveUser(request);
            if (username == null) {
                if (wantsHtml(request)) {
                    log.debug("[AUTH] 未登录访问页面 {}，跳转登录页", path);
                    response.sendRedirect("/login");
                } else {
                    log.debug("[AUTH] 未登录访问接口 {}，返回 401", path);
                    writeJsonError(response, 401, "未登录或会话已过期，请重新登录");
                }
                return;
            }

            request.setAttribute(ATTR_USER, username);
            chain.doFilter(request, response);
        }

        private boolean isWhitelisted(String path) {
            for (String exact : WHITE_EXACT) {
                if (exact.equals(path)) return true;
            }
            for (String prefix : WHITE_PREFIXES) {
                if (path.startsWith(prefix)) return true;
            }
            return false;
        }

        /** 浏览器地址栏导航才跳转，fetch/XHR/curl 一律拿 401 */
        private boolean wantsHtml(HttpServletRequest request) {
            if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
                return false;
            }
            String accept = request.getHeader("Accept");
            return accept != null && accept.contains("text/html");
        }

        private String resolveUser(HttpServletRequest request) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) return null;
            for (Cookie c : cookies) {
                if (SESSION_COOKIE_NAME.equals(c.getName())) {
                    String token = c.getValue();
                    if (token == null || token.isBlank()) return null;
                    return tokenService.verify(token).orElse(null);
                }
            }
            return null;
        }

        private String stripContextPath(HttpServletRequest request) {
            String uri = request.getRequestURI();
            if (uri == null) return "/";
            String ctx = request.getContextPath();
            if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
                uri = uri.substring(ctx.length());
            }
            return uri.isEmpty() ? "/" : uri;
        }

        private void writeJsonError(HttpServletResponse response, int status, String message)
                throws IOException {
            if (response.isCommitted()) return;
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":" + status
                    + ",\"message\":\"" + message.replace("\"", "'") + "\",\"data\":null}");
        }
    }
}
