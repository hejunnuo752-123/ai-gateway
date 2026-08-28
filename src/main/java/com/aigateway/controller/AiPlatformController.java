package com.aigateway.controller;

import com.aigateway.dto.ApiResponse;
import com.aigateway.model.AiPlatform;
import com.aigateway.service.AiPlatformService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai-platforms")
public class AiPlatformController {

    private final AiPlatformService service;

    public AiPlatformController(AiPlatformService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AiPlatform>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<AiPlatform> get(@PathVariable Long id) {
        return service.get(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "平台不存在"));
    }

    @PostMapping
    public ApiResponse<AiPlatform> create(@RequestBody AiPlatform platform) {
        if (platform.getName() == null || platform.getName().isBlank()) {
            return ApiResponse.error(400, "平台名称不能为空");
        }
        if (platform.getUrl() == null || platform.getUrl().isBlank()) {
            return ApiResponse.error(400, "跳转链接不能为空");
        }
        return ApiResponse.success(service.save(platform));
    }

    @PutMapping("/{id}")
    public ApiResponse<AiPlatform> update(@PathVariable Long id, @RequestBody AiPlatform platform) {
        if (!service.get(id).isPresent()) {
            return ApiResponse.error(404, "平台不存在");
        }
        platform.setId(id);
        return ApiResponse.success(service.save(platform));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (!service.get(id).isPresent()) {
            return ApiResponse.error(404, "平台不存在");
        }
        service.delete(id);
        return ApiResponse.success();
    }
}
