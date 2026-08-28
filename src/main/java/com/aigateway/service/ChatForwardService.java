package com.aigateway.service;

import com.aigateway.model.Provider;
import com.aigateway.store.FileStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 聊天请求转发服务。
 * 接收客户端 OpenAI 格式的请求，按虚拟模型匹配上游平台列表，轮询尝试转发。
 *
 * 路由逻辑：
 * 1. 虚拟模型名（请求中的 model）对应多个平台
 * 2. 按顺序依次尝试每个平台，第一个成功的直接返回
 * 3. 全部失败则返回错误
 */
@Service
public class ChatForwardService {

    private static final Logger log = LoggerFactory.getLogger(ChatForwardService.class);

    private final FileStoreService store;
    private final ObjectMapper mapper = new ObjectMapper();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)       // streaming 不设读超时
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public ChatForwardService(FileStoreService store) {
        this.store = store;
    }

    /**
     * 虚拟模型 → 直接轮询所有 ACTIVE 平台（不经过 model_config 匹配）。
     * 按 sortOrder 从小到大排序，保证轮询顺序稳定。
     */
    public List<Provider> findProviders(String modelName) {
        List<Provider> result = new ArrayList<>();

        for (Provider p : store.getAllProviders()) {
            if ("ACTIVE".equalsIgnoreCase(p.getStatus())) {
                result.add(p);
            }
        }

        // 按序号从小到大排序（序号相同则按 ID），保证轮询顺序稳定
        result.sort(Comparator.comparing(Provider::getSortOrder,
                Comparator.nullsLast(Integer::compareTo))
                .thenComparing(Provider::getId));

        return result;
    }

    /**
     * 带 fallback 的非流式转发（支持模型名映射）。
     * 依次尝试 providers 列表中的每个平台，直到返回 2xx。
     */
    public String forwardWithFallback(List<Provider> providers, String modelName, String requestBody) throws IOException {
        IOException lastEx = null;
        String lastErrorBody = null;

        for (int i = 0; i < providers.size(); i++) {
            Provider p = providers.get(i);
            String upstreamName = resolveUpstreamModelName(p, modelName);
            log.info("[FWD] 尝试 [{}/{}] 平台={}(id={}, 序号={}), baseUrl={}, 虚拟模型={} -> 上游模型={}",
                    i + 1, providers.size(), p.getName(), p.getId(), p.getSortOrder(), p.getBaseUrl(), modelName, upstreamName);
            try {
                String actualBody = rewriteModelName(p, modelName, requestBody);
                long start = System.currentTimeMillis();
                String result = forward(p, actualBody);
                long duration = System.currentTimeMillis() - start;
                int tokens = extractTotalTokens(result);
                log.info("[FWD] ✅ 成功 [{}/{}] 平台={}(id={}) | 耗时={}ms | token={}",
                        i + 1, providers.size(), p.getName(), p.getId(), duration, tokens);
                markHealth(p, true);
                return result;
            } catch (IOException e) {
                log.warn("[FWD] ❌ 失败 [{}/{}] 平台={}(id={}): {}",
                        i + 1, providers.size(), p.getName(), p.getId(), e.getMessage());
                markHealth(p, false);
                lastEx = e;
                lastErrorBody = "{\"error\":\"Provider " + p.getName() + " failed: " + e.getMessage() + "\"}";
            }
        }

        log.error("[FWD] ❌ 所有 {} 个平台均已尝试失败", providers.size());
        if (lastEx != null) {
            throw new IOException(lastErrorBody != null ? lastErrorBody : "All providers failed", lastEx);
        }
        throw new IOException("{\"error\":\"No available providers\"}");
    }

    /**
     * 带 fallback 的流式转发 (SSE，支持模型名映射)。
     * 依次尝试 providers，一旦某个成功开始流式输出，就直接写入 response。
     */
    public void forwardStreamWithFallback(List<Provider> providers, String modelName, String requestBody,
                                           HttpServletResponse response) throws IOException {
        for (int i = 0; i < providers.size(); i++) {
            Provider p = providers.get(i);
            String upstreamName = resolveUpstreamModelName(p, modelName);
            log.info("[FWD-STREAM] 尝试 [{}/{}] 平台={}(id={}, 序号={}), baseUrl={}, 虚拟模型={} -> 上游模型={}",
                    i + 1, providers.size(), p.getName(), p.getId(), p.getSortOrder(), p.getBaseUrl(), modelName, upstreamName);
            try {
                String actualBody = rewriteModelName(p, modelName, requestBody);
                long start = System.currentTimeMillis();
                int tokens = forwardStream(p, actualBody, response, i + 1, providers.size());
                long duration = System.currentTimeMillis() - start;
                log.info("[FWD-STREAM] ✅任务完成 [{}/{}] 平台={}(id={}) | 耗时={}ms | token={}",
                        i + 1, providers.size(), p.getName(), p.getId(), duration, tokens);
                return;
            } catch (IOException e) {
                log.warn("[FWD-STREAM] ❌ 失败 [{}/{}] 平台={}(id={}): {}",
                        i + 1, providers.size(), p.getName(), p.getId(), e.getMessage());
                markHealth(p, false);
            }
        }

        log.error("[FWD-STREAM] ❌ 所有 {} 个平台均已尝试失败", providers.size());
        if (!response.isCommitted()) {
            response.setStatus(502);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"All providers failed for this model\"}");
        }
    }

    /**
     * 标记平台健康状态并持久化到 providers.json。
     */
    private void markHealth(Provider p, boolean success) {
        try {
            p.setHealthStatus(success ? "NORMAL" : "ERROR");
            p.setLastHealthCheck(java.time.LocalDateTime.now());
            store.saveProvider(p);
            log.info("[HEALTH] 平台={}(id={}) 状态更新为 {}",
                    p.getName(), p.getId(), p.getHealthStatus());
        } catch (Exception e) {
            log.warn("[HEALTH] 平台={}(id={}) 状态持久化失败: {}", p.getName(), p.getId(), e.getMessage());
        }
    }

    /**
     * 根据 Provider + 虚拟模型名，解析上游真实模型名，并改写请求体。
     * 如果该 Provider 下没有配置 upstreamModelName，则保持原请求体不变。
     */
    public String rewriteModelName(Provider provider, String modelName, String requestBody) {
        String upstreamName = resolveUpstreamModelName(provider, modelName);
        if (upstreamName == null || upstreamName.equals(modelName)) {
            return requestBody;
        }
        try {
            JsonNode root = mapper.readTree(requestBody);
            if (root instanceof ObjectNode obj) {
                obj.put("model", upstreamName);
                return obj.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to rewrite model name in request body: {}", e.getMessage());
        }
        return requestBody;
    }

    /**
     * 解析上游模型名：直接用 provider 自身配置的 selectedModel。
     */
    private String resolveUpstreamModelName(Provider provider, String modelName) {
        if (provider.getSelectedModel() != null && !provider.getSelectedModel().isBlank()) {
            return provider.getSelectedModel();
        }
        return modelName;
    }

    /**
     * 非流式转发（单次尝试）。
     * 上游返回非 2xx 时抛 IOException，便于上层做 fallback。
     */
    public String forward(Provider provider, String requestBody) throws IOException {
        String url = buildChatUrl(provider.getBaseUrl());

        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json");

        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + provider.getApiKey());
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            String respBody = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                log.warn("Upstream {} returned {}: {}", url, response.code(),
                        respBody.length() > 500 ? respBody.substring(0, 500) : respBody);
                // 抛异常让上层 fallback 到下一个平台
                throw new IOException("Upstream returned " + response.code() + ": "
                        + (respBody.length() > 300 ? respBody.substring(0, 300) : respBody));
            }
            return respBody;
        }
    }

    /**
     * 流式转发 (SSE) — 单次尝试。
     * 直接写入 HttpServletResponse 的 OutputStream。
     * 上游返回非 2xx 时抛 IOException。
     * 返回流式响应中产生的 token 数（从 content 块累加）。
     */
    public int forwardStream(Provider provider, String requestBody,
                              HttpServletResponse response, int index, int total) throws IOException {
        String url = buildChatUrl(provider.getBaseUrl());

        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json");

        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + provider.getApiKey());
        }

        try (okhttp3.Response upstreamResp = client.newCall(builder.build()).execute()) {
            if (!upstreamResp.isSuccessful()) {
                String errBody = upstreamResp.body() != null ? upstreamResp.body().string() : "";
                log.warn("Upstream {} returned {}: {}", url, upstreamResp.code(),
                        errBody.length() > 300 ? errBody.substring(0, 300) : errBody);
                throw new IOException("Upstream returned " + upstreamResp.code());
            }

            // 收到上游 200 响应，立即记录调用成功（不等流式结束）
            log.info("[FWD-STREAM] ✅ 调用成功 [{}/{}] 平台={}(id={})",
                    index, total, provider.getName(), provider.getId());
            markHealth(provider, true);

            // 设置 SSE 响应头
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            response.setStatus(upstreamResp.code());

            InputStream in = upstreamResp.body() != null
                    ? upstreamResp.body().byteStream() : null;
            if (in == null) return 0;

            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int tokens = 0;
            while ((n = in.read(buf)) != -1) {
                tokens += countStreamTokens(buf, n);
                out.write(buf, 0, n);
                out.flush();
            }
            return tokens;
        }
    }

    /**
     * 从 SSE 数据块中估算 token 数：统计 content 字段的字数（按空格/字符分词近似）。
     */
    private int countStreamTokens(byte[] buf, int len) {
        String chunk = new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);
        int count = 0;
        for (String line : chunk.split("\r?\n")) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) continue;
            try {
                JsonNode root = mapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (choices.isArray()) {
                    for (JsonNode choice : choices) {
                        String content = choice.path("delta").path("content").asText("");
                        if (!content.isEmpty()) {
                            // 简单估算：每 4 个字符约 1 个 token
                            count += Math.max(1, content.length() / 4);
                        }
                    }
                }
                // 有些服务商在最后一个 chunk 的 usage 里给出总数
                JsonNode usage = root.path("usage");
                if (usage.isObject() && usage.has("total_tokens")) {
                    count = usage.path("total_tokens").asInt(count);
                }
            } catch (Exception ignored) {
            }
        }
        return count;
    }

    /**
     * 从非流式响应 JSON 中提取 usage.total_tokens。
     */
    private int extractTotalTokens(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode usage = root.path("usage");
            if (usage.isObject() && usage.has("total_tokens")) {
                return usage.path("total_tokens").asInt(0);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** /v1/chat/completions */
    private String buildChatUrl(String baseUrl) {
        String url = baseUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 如果 baseUrl 已经以 /v1 结尾，直接拼接；否则追加 /v1
        if (!url.endsWith("/v1")) {
            url += "/v1";
        }
        url += "/chat/completions";
        return url;
    }
}
