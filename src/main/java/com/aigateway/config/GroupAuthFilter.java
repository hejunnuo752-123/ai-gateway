package com.aigateway.config;

import com.aigateway.model.ResourceGroup;
import com.aigateway.store.GroupStoreService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 分组链路鉴权过滤器 —— 只作用于 /g/**，旧的 /v1/** 完全不受影响。
 *
 * 职责：
 * 1. 从路径 /g/{groupKey}/... 解析 groupKey
 * 2. 校验 Authorization: Bearer xxx（OpenAI 风格）或 x-api-key（Anthropic 风格）
 *    与该分组的 apiKey 是否一致 —— 不一致直接 401，杜绝跨组访问
 * 3. 把 ResourceGroup 放进 request attribute 供 Controller 取用
 * 4. 把 groupKey 写入 MDC，让 ChatForwardService 打出的日志自动带上组标识
 *    （logback pattern 里的 %X{groupKey}），实现零侵入的日志归属
 */
@Configuration
public class GroupAuthFilter {

    /** Controller 通过该 key 从 request 取出已鉴权的分组 */
    public static final String ATTR_GROUP = "ai-gateway.resolvedGroup";

    /** logback pattern 中引用的 MDC key */
    public static final String MDC_GROUP_KEY = "groupKey";

    private static final Logger log = LoggerFactory.getLogger(GroupAuthFilter.class);

    @Bean
    public FilterRegistrationBean<GroupAuthServletFilter> groupAuthFilterRegistration(
            GroupStoreService groupStore) {
        FilterRegistrationBean<GroupAuthServletFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new GroupAuthServletFilter(groupStore));
        bean.addUrlPatterns("/g/*");
        bean.setName("groupAuthFilter");
        bean.setOrder(1);
        return bean;
    }

    public static class GroupAuthServletFilter extends OncePerRequestFilter {

        private final GroupStoreService groupStore;

        public GroupAuthServletFilter(GroupStoreService groupStore) {
            this.groupStore = groupStore;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String groupKey = extractGroupKey(request.getRequestURI(), request.getContextPath());
            if (groupKey == null) {
                writeError(response, 404, "路由格式应为 /g/{groupKey}/v1/...");
                return;
            }

            Optional<ResourceGroup> go = groupStore.getGroupByKey(groupKey);
            if (go.isEmpty()) {
                log.warn("[G-AUTH] 分组不存在 groupKey={} uri={}", groupKey, request.getRequestURI());
                writeError(response, 404, "分组 '" + groupKey + "' 不存在");
                return;
            }
            ResourceGroup group = go.get();

            if (!"ACTIVE".equalsIgnoreCase(group.getStatus())) {
                writeError(response, 403, "分组 '" + groupKey + "' 已停用");
                return;
            }

            String presented = extractApiKey(request);
            if (presented == null || presented.isBlank()) {
                log.warn("[G-AUTH] 缺少密钥 groupKey={}", groupKey);
                writeError(response, 401, "缺少 API Key，请通过 Authorization: Bearer 或 x-api-key 传入");
                return;
            }
            if (group.getApiKey() == null || !constantTimeEquals(group.getApiKey(), presented)) {
                log.warn("[G-AUTH] 密钥不匹配 groupKey={}（可能是跨组访问）", groupKey);
                writeError(response, 401, "API Key 与分组 '" + groupKey + "' 不匹配");
                return;
            }

            request.setAttribute(ATTR_GROUP, group);
            MDC.put(MDC_GROUP_KEY, groupKey);
            try {
                chain.doFilter(request, response);
            } finally {
                // 线程会被 Tomcat 复用，必须清理，否则旧链路会串到别人的组标识
                MDC.remove(MDC_GROUP_KEY);
            }
        }

        /** /g/team-rd/v1/chat/completions → team-rd */
        private String extractGroupKey(String uri, String contextPath) {
            if (uri == null) return null;
            String path = uri;
            if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
            if (!path.startsWith("/g/")) return null;
            String rest = path.substring(3);
            int slash = rest.indexOf('/');
            String key = slash >= 0 ? rest.substring(0, slash) : rest;
            return key.isBlank() ? null : key.toLowerCase();
        }

        private String extractApiKey(HttpServletRequest request) {
            String auth = request.getHeader("Authorization");
            if (auth != null && !auth.isBlank()) {
                String v = auth.trim();
                if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    return v.substring(7).trim();
                }
                return v;
            }
            String xKey = request.getHeader("x-api-key");
            if (xKey != null && !xKey.isBlank()) {
                return xKey.trim();
            }
            return null;
        }

        /** 避免通过响应时间差逐字节猜测密钥 */
        private boolean constantTimeEquals(String a, String b) {
            byte[] ba = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(ba, bb);
        }

        private void writeError(HttpServletResponse response, int status, String message)
                throws IOException {
            if (response.isCommitted()) return;
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":{\"type\":\"gateway_error\",\"message\":\""
                    + message.replace("\"", "'") + "\"}}");
        }
    }
}
