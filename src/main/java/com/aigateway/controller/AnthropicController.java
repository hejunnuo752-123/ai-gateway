package com.aigateway.controller;

import com.aigateway.model.Provider;
import com.aigateway.service.ChatForwardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Anthropic Messages API 兼容入口。
 *
 * Claude 客户端（Claude Code / Claude Desktop 等）默认使用 Anthropic 协议调用 /v1/messages。
 * 本控制器把 Anthropic 格式的请求转换为 OpenAI 格式，复用现有的 Provider 轮询转发逻辑，
 * 再把上游返回的 OpenAI 格式响应转换回 Anthropic 格式返回给客户端。
 *
 * 支持非流式（stream=false）和流式（stream=true，SSE）两种模式。
 */
@RestController
@RequestMapping("/v1")
public class AnthropicController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicController.class);

    private final ChatForwardService forwardService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicController(ChatForwardService forwardService) {
        this.forwardService = forwardService;
    }

    /**
     * POST /v1/messages
     */
    @PostMapping("/messages")
    public void messages(@RequestBody String requestBody,
                         @RequestHeader(value = "x-api-key", required = false) String apiKey,
                         @RequestHeader(value = "anthropic-version", required = false) String anthropicVersion,
                         HttpServletResponse response) throws IOException {
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

        List<Provider> providers = forwardService.findProviders(modelName);
        if (providers.isEmpty()) {
            log.warn("No provider found for virtual model: {}", modelName);
            writeError(response, 404, "No provider configured for model '" + modelName +
                    "'. Please add a provider in the management UI.");
            return;
        }

        log.info("Anthropic virtual model '{}' matched {} provider(s): {}",
                modelName, providers.size(),
                providers.stream().map(Provider::getName).toList());

        String openAiBody = convertAnthropicToOpenAi(anthropicReq);
        boolean stream = anthropicReq.has("stream") && anthropicReq.get("stream").asBoolean(false);

        try {
            if (stream) {
                forwardStreamAnthropic(providers, openAiBody, modelName, response);
            } else {
                String openAiResp = forwardService.forwardWithFallback(providers, modelName, openAiBody);
                String anthropicResp = convertOpenAiToAnthropic(openAiResp, modelName);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(anthropicResp);
            }
        } catch (IOException e) {
            log.error("Anthropic forward failed for model '{}': {}", modelName, e.getMessage());
            if (!response.isCommitted()) {
                writeError(response, 502, "All providers failed: " + e.getMessage());
            }
        }
    }

    /**
     * Anthropic 请求体 → OpenAI 请求体。
     */
    private String convertAnthropicToOpenAi(JsonNode anthropicReq) {
        ObjectNode openAiReq = mapper.createObjectNode();

        // model 保持虚拟模型名
        openAiReq.set("model", anthropicReq.get("model"));

        // messages：把顶层 system 提到数组最前面
        ArrayNode messages = mapper.createArrayNode();
        if (anthropicReq.has("system")) {
            JsonNode system = anthropicReq.get("system");
            if (system.isTextual()) {
                ObjectNode sysMsg = mapper.createObjectNode();
                sysMsg.put("role", "system");
                sysMsg.set("content", system);
                messages.add(sysMsg);
            } else if (system.isArray()) {
                for (JsonNode item : system) {
                    ObjectNode sysMsg = mapper.createObjectNode();
                    sysMsg.put("role", "system");
                    if (item.has("type") && "text".equals(item.get("type").asText()) && item.has("text")) {
                        sysMsg.set("content", item.get("text"));
                    } else {
                        sysMsg.set("content", item);
                    }
                    messages.add(sysMsg);
                }
            }
        }

        if (anthropicReq.has("messages") && anthropicReq.get("messages").isArray()) {
            for (JsonNode msg : anthropicReq.get("messages")) {
                messages.add(msg);
            }
        }
        openAiReq.set("messages", messages);

        // 通用参数直接透传
        copyIfPresent(openAiReq, anthropicReq, "max_tokens");
        copyIfPresent(openAiReq, anthropicReq, "temperature");
        copyIfPresent(openAiReq, anthropicReq, "top_p");
        copyIfPresent(openAiReq, anthropicReq, "top_k");
        copyIfPresent(openAiReq, anthropicReq, "stop");
        copyIfPresent(openAiReq, anthropicReq, "stream");

        // tools / tool_choice 如果存在也透传（OpenAI 与 Anthropic 格式近似）
        if (anthropicReq.has("tools")) {
            openAiReq.set("tools", anthropicReq.get("tools"));
        }
        if (anthropicReq.has("tool_choice")) {
            openAiReq.set("tool_choice", anthropicReq.get("tool_choice"));
        }

        return openAiReq.toString();
    }

    private void copyIfPresent(ObjectNode target, JsonNode source, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field));
        }
    }

    /**
     * 从 OpenAI delta/message 中提取文本内容。
     * 优先 content，没有时尝试 reasoning_content（部分国产模型会把回复放在该字段）。
     */
    private String extractText(JsonNode node) {
        if (node == null) return "";
        if (node.has("content") && !node.get("content").isNull()) {
            String text = node.get("content").asText("");
            if (!text.isEmpty()) return text;
        }
        if (node.has("reasoning_content") && !node.get("reasoning_content").isNull()) {
            return node.get("reasoning_content").asText("");
        }
        return "";
    }

    /**
     * OpenAI 非流式响应 → Anthropic 响应。
     */
    private String convertOpenAiToAnthropic(String openAiResp, String modelName) {
        try {
            JsonNode root = mapper.readTree(openAiResp);

            // 上游本身就是错误体，尽量保持格式返回 502
            if (root.has("error")) {
                return openAiResp;
            }

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return buildAnthropicError("Empty upstream response");
            }

            JsonNode message = choices.get(0).get("message");
            String content = extractText(message);
            String finishReason = choices.get(0).has("finish_reason")
                    ? choices.get(0).get("finish_reason").asText(null)
                    : null;

            ObjectNode result = mapper.createObjectNode();
            result.put("id", root.has("id") ? root.get("id").asText() : ("msg_" + System.currentTimeMillis()));
            result.put("type", "message");
            result.put("role", "assistant");
            result.put("model", modelName);

            ArrayNode contentArr = mapper.createArrayNode();
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", content);
            contentArr.add(textBlock);
            result.set("content", contentArr);

            result.put("stop_reason", mapFinishReason(finishReason));
            result.set("stop_sequence", null);

            ObjectNode usage = mapper.createObjectNode();
            usage.put("input_tokens", root.has("usage") && root.get("usage").has("prompt_tokens")
                    ? root.get("usage").get("prompt_tokens").asInt(0) : 0);
            usage.put("output_tokens", root.has("usage") && root.get("usage").has("completion_tokens")
                    ? root.get("usage").get("completion_tokens").asInt(0) : 0);
            result.set("usage", usage);

            return result.toString();
        } catch (Exception e) {
            log.warn("Failed to convert OpenAI response to Anthropic format: {}", e.getMessage());
            return buildAnthropicError("Failed to convert upstream response");
        }
    }

    private String mapFinishReason(String finishReason) {
        if (finishReason == null) return null;
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "content_filter";
            default -> finishReason;
        };
    }

    private String buildAnthropicError(String message) {
        ObjectNode err = mapper.createObjectNode();
        err.put("type", "error");
        err.put("error", mapper.createObjectNode().put("message", message));
        return err.toString();
    }

    /**
     * 流式转发，并把 OpenAI SSE 转换为 Anthropic SSE。
     */
    private void forwardStreamAnthropic(List<Provider> providers, String openAiBody,
                                        String modelName, HttpServletResponse response) throws IOException {
        // 先拿到一个成功的 SSE 上游响应，然后边读边转换
        IOException lastEx = null;

        for (int i = 0; i < providers.size(); i++) {
            Provider p = providers.get(i);
            log.info("Anthropic streaming trying provider [{}/{}] {} ({})",
                    i + 1, providers.size(), p.getName(), p.getBaseUrl());
            try {
                String actualBody = forwardService.rewriteModelName(p, modelName, openAiBody);
                streamOneProviderAnthropic(p, actualBody, modelName, response);
                return;
            } catch (IOException e) {
                log.warn("Provider {} failed for Anthropic stream: {}", p.getName(), e.getMessage());
                lastEx = e;
            }
        }

        if (!response.isCommitted()) {
            writeError(response, 502, "All providers failed: " + (lastEx != null ? lastEx.getMessage() : "unknown"));
        }
    }

    private void streamOneProviderAnthropic(Provider provider, String openAiBody,
                                            String modelName, HttpServletResponse response) throws IOException {
        String url = buildOpenAiChatUrl(provider.getBaseUrl());

        okhttp3.MediaType mediaType = okhttp3.MediaType.parse("application/json");
        okhttp3.RequestBody body = okhttp3.RequestBody.create(openAiBody, mediaType);
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json");

        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + provider.getApiKey());
        }

        try (okhttp3.Response upstreamResp = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(builder.build()).execute()) {

            if (!upstreamResp.isSuccessful()) {
                String err = upstreamResp.body() != null ? upstreamResp.body().string() : "";
                throw new IOException("Upstream returned " + upstreamResp.code() + ": " + err);
            }

            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            response.setStatus(200);

            PrintWriter writer = response.getWriter();
            String messageId = "msg_" + System.currentTimeMillis();

            // 发送 message_start
            writer.write(buildSseEvent("message_start",
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"" + messageId + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"" + escapeJson(modelName) + "\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}"));
            writer.write(buildSseEvent("content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
            writer.flush();

            InputStream in = upstreamResp.body() != null ? upstreamResp.body().byteStream() : null;
            if (in == null) {
                finishAnthropicStream(writer);
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            boolean started = false;
            StringBuilder contentBuffer = new StringBuilder();

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
                if (delta == null) continue;

                // role
                if (delta.has("role") && !started) {
                    started = true;
                }

                // content（部分模型会放在 reasoning_content 里）
                String text = extractText(delta);
                if (!text.isEmpty()) {
                    writer.write(buildSseEvent("content_block_delta",
                            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + escapeJson(text) + "\"}}"));
                    writer.flush();
                    contentBuffer.append(text);
                }

                // finish_reason
                if (choices.get(0).has("finish_reason") && !choices.get(0).get("finish_reason").isNull()) {
                    String fr = choices.get(0).get("finish_reason").asText();
                    writer.write(buildSseEvent("message_delta",
                            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + mapFinishReason(fr) + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}"));
                    writer.flush();
                }
            }

            finishAnthropicStream(writer);
        }
    }

    private void finishAnthropicStream(PrintWriter writer) {
        writer.write(buildSseEvent("content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}"));
        writer.write(buildSseEvent("message_stop",
                "{\"type\":\"message_stop\"}"));
        writer.flush();
    }

    private String buildSseEvent(String eventName, String data) {
        return "event: " + eventName + "\n" +
                "data: " + data + "\n\n";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private String buildOpenAiChatUrl(String baseUrl) {
        String url = baseUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/v1")) {
            url += "/v1";
        }
        url += "/chat/completions";
        return url;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(buildAnthropicError(message));
        }
    }
}
