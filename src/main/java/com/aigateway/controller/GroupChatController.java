package com.aigateway.controller;

import com.aigateway.config.GroupAuthFilter;
import com.aigateway.model.Provider;
import com.aigateway.model.ResourceGroup;
import com.aigateway.service.ChatForwardService;
import com.aigateway.service.GroupRouteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分组链路 —— OpenAI 兼容入口。
 *
 * 路由：/g/{groupKey}/v1/models、/g/{groupKey}/v1/chat/completions
 * 鉴权由 GroupAuthFilter 完成，进入本类时 request 里已有已校验的 ResourceGroup。
 *
 * 与旧的 ChatController 完全并存：旧 /v1/** 一行未改，行为不变。
 * 转发复用 ChatForwardService 的 forwardWithFallback / forwardStreamWithFallback，
 * 这两个方法入参已经是 List&lt;Provider&gt;，与「谁筛出来的」无关。
 */
@RestController
@RequestMapping("/g/{groupKey}/v1")
public class GroupChatController {

    private static final Logger log = LoggerFactory.getLogger(GroupChatController.class);

    private final GroupRouteService routeService;
    private final ChatForwardService forwardService;
    private final ObjectMapper mapper = new ObjectMapper();

    public GroupChatController(GroupRouteService routeService,
                               ChatForwardService forwardService) {
        this.routeService = routeService;
        this.forwardService = forwardService;
    }

    /**
     * GET /g/{groupKey}/v1/models — 只返回该分组开放的虚拟模型。
     */
    @GetMapping("/models")
    public Map<String, Object> listModels(HttpServletRequest request) {
        ResourceGroup group = currentGroup(request);
        List<Map<String, Object>> data = new ArrayList<>();

        for (String modelName : routeService.resolveVirtualModels(group)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", modelName);
            item.put("object", "model");
            item.put("created", System.currentTimeMillis() / 1000);
            item.put("owned_by", "ai-gateway/" + group.getGroupKey());
            data.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", data);
        return result;
    }

    /**
     * POST /g/{groupKey}/v1/chat/completions — 只轮询该分组绑定的平台。
     */
    @PostMapping("/chat/completions")
    public void chatCompletions(@RequestBody String requestBody,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        ResourceGroup group = currentGroup(request);

        JsonNode root;
        try {
            root = mapper.readTree(requestBody);
        } catch (Exception e) {
            writeError(response, 400, "Invalid JSON body");
            return;
        }

        String modelName = root.has("model") ? root.get("model").asText() : null;
        if (modelName == null || modelName.isBlank()) {
            writeError(response, 400, "Missing 'model' field");
            return;
        }

        List<Provider> providers;
        try {
            providers = routeService.resolveProviders(group, modelName);
        } catch (GroupRouteService.RouteException e) {
            log.warn("[G-CHAT] 路由失败 分组={} 模型={}: {}",
                    group.getGroupKey(), modelName, e.getMessage());
            writeError(response, e.getStatus(), e.getMessage());
            return;
        }

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
            log.error("[G-CHAT] 分组={} 全部平台失败 模型={}: {}",
                    group.getGroupKey(), modelName, e.getMessage());
            if (!response.isCommitted()) {
                writeError(response, 502, "All providers failed: " + e.getMessage());
            }
        }
    }

    private ResourceGroup currentGroup(HttpServletRequest request) {
        Object g = request.getAttribute(GroupAuthFilter.ATTR_GROUP);
        if (g instanceof ResourceGroup rg) {
            return rg;
        }
        // Filter 一定先执行，走到这里说明配置异常
        throw new IllegalStateException("分组上下文缺失，GroupAuthFilter 未生效");
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
