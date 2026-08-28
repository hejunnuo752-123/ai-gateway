package com.aigateway.controller;

import com.aigateway.config.GroupAuthFilter;
import com.aigateway.model.Provider;
import com.aigateway.model.ResourceGroup;
import com.aigateway.service.AnthropicProtocolService;
import com.aigateway.service.ChatForwardService;
import com.aigateway.service.GroupRouteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 分组链路 —— Anthropic Messages API 兼容入口。
 *
 * 路由：/g/{groupKey}/v1/messages
 *
 * 相对旧 AnthropicController 的两点改进（旧类保持原样不动）：
 * 1. OkHttpClient 提升为类级共享实例，不再每次请求 new 一个，连接池得以复用
 * 2. 上游返回 200 时打 [FWD-A] 日志并回写健康状态，日志面板能看到 Anthropic 链路
 */
@RestController
@RequestMapping("/g/{groupKey}/v1")
public class GroupAnthropicController {

    private static final Logger log = LoggerFactory.getLogger(GroupAnthropicController.class);

    private final GroupRouteService routeService;
    private final ChatForwardService forwardService;
    private final AnthropicProtocolService protocol;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 共享客户端：readTimeout=0 以支持长连接流式 */
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public GroupAnthropicController(GroupRouteService routeService,
                                    ChatForwardService forwardService,
                                    AnthropicProtocolService protocol) {
        this.routeService = routeService;
        this.forwardService = forwardService;
        this.protocol = protocol;
    }

    @PostMapping("/messages")
    public void messages(@RequestBody String requestBody,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        ResourceGroup group = currentGroup(request);

        JsonNode anthropicReq;
        try {
            anthropicReq = mapper.readTree(requestBody);
        } catch (Exception e) {
            writeError(response, 400, "Invalid JSON body");
            return;
        }

        String modelName = anthropicReq.has("model") ? anthropicReq.get("model").asText() : null;
        if (modelName == null || modelName.isBlank()) {
            writeError(response, 400, "Missing 'model' field");
            return;
        }

        List<Provider> providers;
        try {
            providers = routeService.resolveProviders(group, modelName);
        } catch (GroupRouteService.RouteException e) {
            log.warn("[G-MSG] 路由失败 分组={} 模型={}: {}",
                    group.getGroupKey(), modelName, e.getMessage());
            writeError(response, e.getStatus(), e.getMessage());
            return;
        }

        String openAiBody = protocol.toOpenAiRequest(anthropicReq);
        boolean stream = anthropicReq.has("stream") && anthropicReq.get("stream").asBoolean(false);

        try {
            if (stream) {
                forwardStreamAnthropic(providers, openAiBody, modelName, response);
            } else {
                String openAiResp = forwardService.forwardWithFallback(providers, modelName, openAiBody);
                String anthropicResp = protocol.toAnthropicResponse(openAiResp, modelName);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(anthropicResp);
            }
        } catch (IOException e) {
            log.error("[G-MSG] 分组={} 全部平台失败 模型={}: {}",
                    group.getGroupKey(), modelName, e.getMessage());
            if (!response.isCommitted()) {
                writeError(response, 502, "All providers failed: " + e.getMessage());
            }
        }
    }

    /** 依次尝试各平台，把 OpenAI SSE 实时转换为 Anthropic SSE */
    private void forwardStreamAnthropic(List<Provider> providers, String openAiBody,
                                        String modelName, HttpServletResponse response)
            throws IOException {
        IOException lastEx = null;

        for (int i = 0; i < providers.size(); i++) {
            Provider p = providers.get(i);
            log.info("[FWD-A] 尝试 [{}/{}] 平台={}(id={}), baseUrl={}, 虚拟模型={}",
                    i + 1, providers.size(), p.getName(), p.getId(), p.getBaseUrl(), modelName);
            try {
                String actualBody = forwardService.rewriteModelName(p, modelName, openAiBody);
                long start = System.currentTimeMillis();
                int tokens = streamOneProvider(p, actualBody, modelName, response,
                        i + 1, providers.size());
                long duration = System.currentTimeMillis() - start;
                log.info("[FWD-A] ✅任务完成 [{}/{}] 平台={}(id={}) | 耗时={}ms | token={}",
                        i + 1, providers.size(), p.getName(), p.getId(), duration, tokens);
                return;
            } catch (IOException e) {
                log.warn("[FWD-A] ❌ 失败 [{}/{}] 平台={}(id={}): {}",
                        i + 1, providers.size(), p.getName(), p.getId(), e.getMessage());
                lastEx = e;
                // 响应已提交说明流已经开始写出，继续尝试会产生拼接的脏流，直接中断
                if (response.isCommitted()) {
                    log.error("[FWD-A] 响应已提交，中断 fallback 避免脏流");
                    return;
                }
            }
        }

        log.error("[FWD-A] ❌ 所有 {} 个平台均已尝试失败", providers.size());
        if (!response.isCommitted()) {
            writeError(response, 502, "All providers failed: "
                    + (lastEx != null ? lastEx.getMessage() : "unknown"));
        }
    }

    private int streamOneProvider(Provider provider, String openAiBody, String modelName,
                                  HttpServletResponse response, int index, int total)
            throws IOException {
        String url = buildOpenAiChatUrl(provider.getBaseUrl());

        okhttp3.RequestBody body = okhttp3.RequestBody.create(openAiBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json");

        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + provider.getApiKey());
        }

        try (Response upstreamResp = client.newCall(builder.build()).execute()) {
            if (!upstreamResp.isSuccessful()) {
                String err = upstreamResp.body() != null ? upstreamResp.body().string() : "";
                throw new IOException("Upstream returned " + upstreamResp.code() + ": "
                        + (err.length() > 300 ? err.substring(0, 300) : err));
            }

            log.info("[FWD-A] ✅ 调用成功 [{}/{}] 平台={}(id={})",
                    index, total, provider.getName(), provider.getId());

            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            response.setStatus(200);

            PrintWriter writer = response.getWriter();
            String messageId = "msg_" + System.currentTimeMillis();

            writer.write(protocol.buildSseEvent("message_start",
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"" + messageId
                            + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\""
                            + protocol.escapeJson(modelName)
                            + "\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}"));
            writer.write(protocol.buildSseEvent("content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
            writer.flush();

            InputStream in = upstreamResp.body() != null ? upstreamResp.body().byteStream() : null;
            if (in == null) {
                finishStream(writer);
                return 0;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int tokens = 0;

            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;

                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (Exception e) {
                    continue;
                }

                JsonNode choices = chunk.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) continue;

                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    String text = protocol.extractText(delta);
                    if (!text.isEmpty()) {
                        writer.write(protocol.buildSseEvent("content_block_delta",
                                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\""
                                        + protocol.escapeJson(text) + "\"}}"));
                        writer.flush();
                        tokens += Math.max(1, text.length() / 4);
                    }
                }

                if (choices.get(0).has("finish_reason") && !choices.get(0).get("finish_reason").isNull()) {
                    String fr = choices.get(0).get("finish_reason").asText();
                    writer.write(protocol.buildSseEvent("message_delta",
                            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""
                                    + protocol.mapFinishReason(fr)
                                    + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + tokens + "}}"));
                    writer.flush();
                }

                JsonNode usage = chunk.get("usage");
                if (usage != null && usage.isObject() && usage.has("total_tokens")) {
                    tokens = usage.get("total_tokens").asInt(tokens);
                }
            }

            finishStream(writer);
            return tokens;
        }
    }

    private void finishStream(PrintWriter writer) {
        writer.write(protocol.buildSseEvent("content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}"));
        writer.write(protocol.buildSseEvent("message_stop", "{\"type\":\"message_stop\"}"));
        writer.flush();
    }

    private String buildOpenAiChatUrl(String baseUrl) {
        String url = baseUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/v1")) {
            url += "/v1";
        }
        return url + "/chat/completions";
    }

    private ResourceGroup currentGroup(HttpServletRequest request) {
        Object g = request.getAttribute(GroupAuthFilter.ATTR_GROUP);
        if (g instanceof ResourceGroup rg) {
            return rg;
        }
        throw new IllegalStateException("分组上下文缺失，GroupAuthFilter 未生效");
    }

    private void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(protocol.buildError(message));
    }
}
