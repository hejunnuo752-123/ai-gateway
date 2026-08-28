package com.aigateway.service;

import com.aigateway.model.GroupMember;
import com.aigateway.model.Provider;
import com.aigateway.model.ResourceGroup;
import com.aigateway.store.FileStoreService;
import com.aigateway.store.GroupStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 分组路由解析服务 —— 新链路的核心。
 *
 * 与旧的 ChatForwardService.findProviders(modelName) 的区别：
 * 旧方法忽略 modelName，直接返回全部 ACTIVE 平台；
 * 本方法做三层过滤 —— 分组状态、虚拟模型白名单、组内成员启用状态，
 * 并按 orderInGroup（组内独立序号）排序。
 *
 * 解析结果是 List&lt;Provider&gt;，可直接交给现有的
 * forwardWithFallback / forwardStreamWithFallback，不需要改它们的签名。
 */
@Service
public class GroupRouteService {

    private static final Logger log = LoggerFactory.getLogger(GroupRouteService.class);

    private final GroupStoreService groupStore;
    private final FileStoreService fileStore;

    public GroupRouteService(GroupStoreService groupStore, FileStoreService fileStore) {
        this.groupStore = groupStore;
        this.fileStore = fileStore;
    }

    /** 路由解析失败时抛出，携带建议的 HTTP 状态码 */
    public static class RouteException extends RuntimeException {
        private final int status;

        public RouteException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    public Optional<ResourceGroup> findGroup(String groupKey) {
        return groupStore.getGroupByKey(groupKey);
    }

    /**
     * 解析该分组应轮询的平台列表。
     *
     * @param group     已通过鉴权的分组
     * @param modelName 请求中的虚拟模型名，会校验是否在该组白名单内
     */
    public List<Provider> resolveProviders(ResourceGroup group, String modelName) {
        if (group == null) {
            throw new RouteException(404, "分组不存在");
        }
        if (!"ACTIVE".equalsIgnoreCase(group.getStatus())) {
            throw new RouteException(403, "分组 '" + group.getGroupKey() + "' 已停用");
        }

        // 虚拟模型白名单校验：为空表示不限制（放行全部）
        List<String> allowed = group.getVirtualModels();
        if (allowed != null && !allowed.isEmpty()
                && modelName != null && !allowed.contains(modelName)) {
            throw new RouteException(404, "模型 '" + modelName + "' 未在分组 '"
                    + group.getGroupKey() + "' 中开放，可用模型: " + allowed);
        }

        List<Provider> result = new ArrayList<>();
        for (GroupMember m : groupStore.getMembersByGroupId(group.getId())) {
            if (m.getEnabled() != null && !m.getEnabled()) continue;
            Optional<Provider> po = fileStore.getProvider(m.getProviderId());
            if (po.isEmpty()) continue;
            Provider p = po.get();
            if (!"ACTIVE".equalsIgnoreCase(p.getStatus())) continue;
            result.add(p);
        }

        if (result.isEmpty()) {
            throw new RouteException(404, "分组 '" + group.getGroupKey()
                    + "' 没有可用的 AI 平台，请在分组管理页绑定平台");
        }

        log.info("[G-ROUTE] 分组={} 虚拟模型={} 命中 {} 个平台: {}",
                group.getGroupKey(), modelName, result.size(),
                result.stream().map(Provider::getName).toList());

        // getMembersByGroupId 已按 orderInGroup 升序，此处保持该顺序
        return result;
    }

    /** 该分组对外暴露的虚拟模型名；未配置时回落到 default 组的配置 */
    public List<String> resolveVirtualModels(ResourceGroup group) {
        if (group.getVirtualModels() != null && !group.getVirtualModels().isEmpty()) {
            return group.getVirtualModels();
        }
        return groupStore.getGroupByKey(GroupStoreService.DEFAULT_GROUP_KEY)
                .map(ResourceGroup::getVirtualModels)
                .orElse(new ArrayList<>());
    }
}
