package com.aigateway.controller;

import com.aigateway.dto.ApiResponse;
import com.aigateway.model.ResourceGroup;
import com.aigateway.service.GroupService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 分组管理 API，供 /groups 页面调用。
 *
 * 全部在 /api/groups 命名空间下，不影响现有 /api/providers 等接口。
 */
@RestController
@RequestMapping("/api/groups")
public class GroupAdminController {

    private final GroupService groupService;

    public GroupAdminController(GroupService groupService) {
        this.groupService = groupService;
    }

    /** 分组列表（含组内平台明细、成员数、健康数） */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(groupService.listGroupViews());
    }

    /** 全部平台候选 + 各自已归属分组，供绑定弹窗使用 */
    @GetMapping("/provider-candidates")
    public ApiResponse<List<Map<String, Object>>> providerCandidates() {
        return ApiResponse.success(groupService.listProviderCandidates());
    }

    @PostMapping
    public ApiResponse<ResourceGroup> create(@RequestBody ResourceGroup group) {
        try {
            return ApiResponse.success(groupService.createGroup(group));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<ResourceGroup> update(@PathVariable Long id,
                                             @RequestBody ResourceGroup group) {
        try {
            return ApiResponse.success(groupService.updateGroup(id, group));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            groupService.deleteGroup(id);
            return ApiResponse.success();
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 重新生成该分组的访问密钥 */
    @PostMapping("/{id}/regenerate-key")
    public ApiResponse<ResourceGroup> regenerateKey(@PathVariable Long id) {
        try {
            return ApiResponse.success(groupService.regenerateApiKey(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 组内平台列表 */
    @GetMapping("/{id}/members")
    public ApiResponse<List<Map<String, Object>>> members(@PathVariable Long id) {
        return ApiResponse.success(groupService.listMemberViews(id));
    }

    /** 批量替换绑定平台，数组顺序即组内轮询顺序 */
    @PutMapping("/{id}/members")
    public ApiResponse<List<Map<String, Object>>> replaceMembers(
            @PathVariable Long id,
            @RequestBody MemberRequest body) {
        try {
            return ApiResponse.success(
                    groupService.replaceMembers(id, body.providerIds()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 仅调整组内轮询顺序，不改动绑定关系 */
    @PutMapping("/{id}/members/order")
    public ApiResponse<List<Map<String, Object>>> reorderMembers(
            @PathVariable Long id,
            @RequestBody MemberRequest body) {
        try {
            return ApiResponse.success(
                    groupService.reorderMembers(id, body.providerIds()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 组内单独启用/禁用某平台 */
    @PutMapping("/{id}/members/{providerId}/toggle")
    public ApiResponse<Map<String, Object>> toggleMember(@PathVariable Long id,
                                                        @PathVariable Long providerId,
                                                        @RequestParam boolean enabled) {
        try {
            return ApiResponse.success(groupService.toggleMember(id, providerId, enabled));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    public record MemberRequest(List<Long> providerIds) {
        public List<Long> providerIds() {
            return providerIds == null ? List.of() : providerIds;
        }
    }
}
