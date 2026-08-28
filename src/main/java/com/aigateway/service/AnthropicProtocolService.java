package com.aigateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Anthropic ↔ OpenAI 协议转换。
 *
 * 抽成独立服务供分组链路（GroupAnthropicController）使用，
 * 旧的 AnthropicController 保持原样、不做任何改动。
 */
@Service
public class AnthropicProtocolService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProtocolService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /** Anthropic 请求体 → OpenAI 请求体 */
    public String toOpenAiRequest(JsonNode anthropicReq) {
        ObjectNode openAiReq = mapper.createObjectNode();
        openAiReq.set("model", anthropicReq.get("model"));

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

        copyIfPresent(openAiReq, anthropicReq, "max_tokens");
        copyIfPresent(openAiReq, anthropicReq, "temperature");
        copyIfPresent(openAiReq, anthropicReq, "top_p");
        copyIfPresent(openAiReq, anthropicReq, "top_k");
        copyIfPresent(openAiReq, anthropicReq, "stop");
        copyIfPresent(openAiReq, anthropicReq, "stream");
        copyIfPresent(openAiReq, anthropicReq, "tools");
        copyIfPresent(openAiReq, anthropicReq, "tool_choice");

        return openAiReq.toString();
    }

    /** OpenAI 非流式响应 → Anthropic 响应 */
    public String toAnthropicResponse(String openAiResp, String modelName) {
        try {
            JsonNode root = mapper.readTree(openAiResp);
            if (root.has("error")) {
                return openAiResp;
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return buildError("Empty upstream response");
            }

            JsonNode message = choices.get(0).get("message");
            String content = extractText(message);
            String finishReason = choices.get(0).has("finish_reason")
                    ? choices.get(0).get("finish_reason").asText(null) : null;

            ObjectNode result = mapper.createObjectNode();
            result.put("id", root.has("id") ? root.get("id").asText()
                    : ("msg_" + System.currentTimeMillis()));
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
            return buildError("Failed to convert upstream response");
        }
    }

    /**
     * 从 OpenAI delta/message 提取文本。
     * 部分国产模型把回复放在 reasoning_content，需要兜底。
     */
    public String extractText(JsonNode node) {
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

    public String mapFinishReason(String finishReason) {
        if (finishReason == null) return null;
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "content_filter";
            default -> finishReason;
        };
    }

    public String buildError(String message) {
        ObjectNode err = mapper.createObjectNode();
        err.put("type", "error");
        err.put("error", mapper.createObjectNode().put("message", message));
        return err.toString();
    }

    public String buildSseEvent(String eventName, String data) {
        return "event: " + eventName + "\n" + "data: " + data + "\n\n";
    }

    public String escapeJson(String s) {
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

    private void copyIfPresent(ObjectNode target, JsonNode source, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field));
        }
    }
}
