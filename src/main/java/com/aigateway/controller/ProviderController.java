package com.aigateway.controller;

import com.aigateway.dto.ApiResponse;
import com.aigateway.model.Provider;
import com.aigateway.service.ModelFetchService;
import com.aigateway.service.ProviderService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderService providerService;
    private final ModelFetchService modelFetchService;

    public ProviderController(ProviderService providerService,
                              ModelFetchService modelFetchService) {
        this.providerService = providerService;
        this.modelFetchService = modelFetchService;
    }

    @GetMapping
    public ApiResponse<List<Provider>> list() {
        return ApiResponse.success(providerService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Provider> get(@PathVariable Long id) {
        return providerService.get(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "平台不存在"));
    }

    @PostMapping
    public ApiResponse<Provider> create(@RequestBody Provider provider) {
        if (provider.getName() == null || provider.getName().isBlank()) {
            return ApiResponse.error(400, "平台名称不能为空");
        }
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            return ApiResponse.error(400, "Base URL 不能为空");
        }
        if (provider.getType() == null || provider.getType().isBlank()) {
            return ApiResponse.error(400, "平台类型不能为空");
        }
        return ApiResponse.success(providerService.save(provider));
    }

    /**
     * 整表重排调用顺序。
     *
     * <p>入参是完整的新 id 顺序，后端按数组下标重写 sortOrder = 0..N-1。
     * 注意这里的路径 /order 与下面的 /{id} 都是 PUT，Spring 的精确路径
     * 优先级高于路径变量，不会被 /{id} 抢走（id 是 Long，"order" 也转不过去）。
     */
    @PutMapping("/order")
    public ApiResponse<List<Provider>> reorder(@RequestBody OrderRequest req) {
        if (req == null || req.providerIds() == null || req.providerIds().isEmpty()) {
            return ApiResponse.error(400, "providerIds 不能为空");
        }
        return ApiResponse.success(providerService.reorder(req.providerIds()));
    }

    public record OrderRequest(List<Long> providerIds) {
    }

    @PutMapping("/{id}")
    public ApiResponse<Provider> update(@PathVariable Long id, @RequestBody Provider provider) {
        if (!providerService.get(id).isPresent()) {
            return ApiResponse.error(404, "平台不存在");
        }
        provider.setId(id);
        return ApiResponse.success(providerService.save(provider));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (!providerService.get(id).isPresent()) {
            return ApiResponse.error(404, "平台不存在");
        }
        providerService.delete(id);
        return ApiResponse.success();
    }

    /**
     * 预览模型列表（不保存）。
     * 用于平台编辑弹窗中「获取模型」后单选模型。
     */
    @PostMapping("/{id}/preview-models")
    public ApiResponse<List<String>> previewModels(@PathVariable Long id) {
        return providerService.get(id)
                .map(p -> doPreviewModels(p))
                .orElse(ApiResponse.error(404, "平台不存在"));
    }

    /**
     * 新增平台时也可以先预览模型列表（不需要平台已存在）。
     */
    @PostMapping("/preview-models")
    public ApiResponse<List<String>> previewModelsForNew(@RequestBody Provider provider) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            return ApiResponse.error(400, "Base URL 不能为空");
        }
        return doPreviewModels(provider);
    }

    private ApiResponse<List<String>> doPreviewModels(Provider p) {
        if (!"OPENAI".equalsIgnoreCase(p.getType())) {
            return ApiResponse.error(400, "仅 OPENAI 类型平台支持自动获取模型");
        }
        try {
            List<String> names = modelFetchService.fetchFromOpenAI(p).stream()
                    .map(m -> m.getModelName())
                    .collect(Collectors.toList());
            return ApiResponse.success(names);
        } catch (IOException e) {
            return ApiResponse.error(500, "获取模型失败: " + e.getMessage());
        }
    }
}
