package com.aigateway.controller;

import com.aigateway.config.VirtualModelProperties;
import com.aigateway.model.ModelConfig;
import com.aigateway.model.Provider;
import com.aigateway.service.ChatForwardService;
import com.aigateway.service.ModelService;
import com.aigateway.service.ProviderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容统一 API 接口。
 * 提供给外部客户端（ChatBox / LobeChat / Open WebUI 等）调用的统一入口。
 */
@RestController
@RequestMapping("/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ProviderService providerService;
    private final ModelService modelService;
    private final ChatForwardService forwardService;
    private final VirtualModelProperties virtualModelProps;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(ProviderService providerService,
                          ModelService modelService,
                          ChatForwardService forwardService,
                          VirtualModelProperties virtualModelProps) {
        this.providerService = providerService;
        this.modelService = modelService;
        this.forwardService = forwardService;
        this.virtualModelProps = virtualModelProps;
    }

    /**
     * GET /v1/models — 从 application.yml 配置返回虚拟模型名，不依赖外部文件。
     */
    @GetMapping("/models")
    public Map<String, Object> listModels() {
        List<Map<String, Object>> data = new ArrayList<>();

        for (VirtualModelProperties.VirtualModel vm : virtualModelProps.getVirtualModels()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", vm.getModelName());
            item.put("object", "model");
            item.put("created", System.currentTimeMillis() / 1000);
            item.put("owned_by", "ai-gateway");
            data.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", data);
        return result;
    }

    /**
     * POST /v1/chat/completions — 统一聊天接口（OpenAI 兼容）。
     *
     * 虚拟模型路由 + Provider 轮询 fallback：
     * 1. 请求中的 model 作为"虚拟模型名"
     * 2. 查找所有匹配该虚拟模型的平台（model_config 表 + provider.selectedModel）
     * 3. 按顺序依次尝试每个平台，第一个成功的直接返回
     * 4. 全部失败则返回错误
     */
    @PostMapping("/chat/completions")
    public void chatCompletions(@RequestBody String requestBody,
                                 HttpServletResponse response) throws IOException {
        JsonNode root;
        try {
            root = mapper.readTree(requestBody);
        } catch (Exception e) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid JSON body\"}");
            return;
        }

        String modelName = root.has("model") ? root.get("model").asText() : null;
        if (modelName == null || modelName.isBlank()) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing 'model' field\"}");
            return;
        }

        // 虚拟模型 → 查找所有匹配的 Provider
        List<Provider> providers = forwardService.findProviders(modelName);
        if (providers.isEmpty()) {
            log.warn("No provider found for virtual model: {}", modelName);
            response.setStatus(404);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"No provider configured for model '" + modelName +
                    "'. Please add a provider in the management UI.\"}");
            return;
        }

        log.info("Virtual model '{}' matched {} provider(s): {}",
                modelName, providers.size(),
                providers.stream().map(Provider::getName).toList());

        boolean stream = root.has("stream") && root.get("stream").asBoolean(false);

        try {
            if (stream) {
                forwardService.forwardStreamWithFallback(providers, modelName, requestBody, response);
            } else {
                String result = forwardService.forwardWithFallback(providers, modelName, requestBody);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(result);
            }
        } catch (IOException e) {
            log.error("All providers failed for model '{}': {}", modelName, e.getMessage());
            if (!response.isCommitted()) {
                response.setStatus(502);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"All providers failed: " + e.getMessage() + "\"}");
            }
        }
    }
}
