package com.aigateway.controller;

import com.aigateway.dto.ApiResponse;
import com.aigateway.model.ModelConfig;
import com.aigateway.service.ModelService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ModelController {

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping("/providers/{providerId}/models")
    public ApiResponse<List<ModelConfig>> listByProvider(@PathVariable Long providerId) {
        return ApiResponse.success(modelService.listByProvider(providerId));
    }

    @PostMapping("/providers/{providerId}/fetch")
    public ApiResponse<List<ModelConfig>> fetchModels(@PathVariable Long providerId) {
        try {
            List<ModelConfig> models = modelService.fetchModels(providerId);
            return ApiResponse.success(models);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error(500, "获取模型列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/providers/{providerId}/models")
    public ApiResponse<ModelConfig> addModel(@PathVariable Long providerId,
                                              @RequestBody ModelConfig model) {
        if (model.getModelName() == null || model.getModelName().isBlank()) {
            return ApiResponse.error(400, "模型名不能为空");
        }
        model.setProviderId(providerId);
        if (model.getContextLength() == null) model.setContextLength(4096);
        if (model.getSupportsVision() == null) model.setSupportsVision(false);
        return ApiResponse.success(modelService.save(model));
    }

    @PutMapping("/models/{id}")
    public ApiResponse<ModelConfig> updateModel(@PathVariable Long id,
                                                 @RequestBody ModelConfig partial) {
        ModelConfig existing = modelService.get(id)
                .orElse(null);
        if (existing == null) {
            return ApiResponse.error(404, "模型不存在");
        }
        // 只更新前端传来的非 null 字段，保留其余字段不变
        if (partial.getModelName() != null) existing.setModelName(partial.getModelName());
        if (partial.getContextLength() != null) existing.setContextLength(partial.getContextLength());
        if (partial.getSupportsVision() != null) existing.setSupportsVision(partial.getSupportsVision());
        if (partial.getProviderId() != null) existing.setProviderId(partial.getProviderId());
        // upstreamModelName 允许清空，但前端用 null 表示清空；这里直接覆盖即可
        existing.setUpstreamModelName(partial.getUpstreamModelName());
        return ApiResponse.success(modelService.save(existing));
    }

    @DeleteMapping("/models/{id}")
    public ApiResponse<Void> deleteModel(@PathVariable Long id) {
        if (!modelService.get(id).isPresent()) {
            return ApiResponse.error(404, "模型不存在");
        }
        modelService.delete(id);
        return ApiResponse.success();
    }
}
