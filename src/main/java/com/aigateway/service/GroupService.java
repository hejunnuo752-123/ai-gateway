package com.aigateway.service;

import com.aigateway.model.GroupMember;
import com.aigateway.model.Provider;
import com.aigateway.model.ResourceGroup;
import com.aigateway.store.FileStoreService;
import com.aigateway.store.GroupStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 分组管理服务：CRUD、Key 生成、groupKey 校验、平台绑定。
 */
@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    private static final String KEY_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 与一级路径冲突的保留字，禁止用作 groupKey */
    private static final List<String> RESERVED_KEYS =
            List.of("api", "v1", "g", "groups", "ai-platforms", "static", "actuator");

    private final GroupStoreService groupStore;
    private final FileStoreService fileStore;

    public GroupService(GroupStoreService groupStore, FileStoreService fileStore) {
        this.groupStore = groupStore;
        this.fileStore = fileStore;
    }

    // ==================== 查询 ====================

    /** 列表视图：附带成员数量与在线平台数，供管理页左栏渲染 */
    public List<Map<String, Object>> listGroupViews() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ResourceGroup g : groupStore.getAllGroups()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", g.getId());
            view.put("groupKey", g.getGroupKey());
            view.put("name", g.getName());
            view.put("apiKey", g.getApiKey());
            view.put("maskedApiKey", maskKey(g.getApiKey()));
            view.put("virtualModels", g.getVirtualModels());
            view.put("status", g.getStatus());
            view.put("sortOrder", g.getSortOrder());
            view.put("remark", g.getRemark());
            List<Map<String, Object>> members = listMemberViews(g.getId());
            view.put("members", members);
            view.put("memberCount", members.size());
            long healthy = members.stream()
                    .filter(m -> "NORMAL".equals(m.get("healthStatus")))
                    .count();
            view.put("healthyCount", healthy);
            result.add(view);
        }
        return result;
    }

    /** 组内平台视图：GroupMember 关联字段 + Provider 基本信息 */
    public List<Map<String, Object>> listMemberViews(Long groupId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupMember m : groupStore.getMembersByGroupId(groupId)) {
            Optional<Provider> po = fileStore.getProvider(m.getProviderId());
            if (po.isEmpty()) continue;   // 平台已删除，跳过悬挂绑定
            Provider p = po.get();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("memberId", m.getId());
            item.put("providerId", p.getId());
            item.put("providerName", p.getName());
            item.put("baseUrl", p.getBaseUrl());
            item.put("selectedModel", p.getSelectedModel());
            item.put("providerStatus", p.getStatus());
            item.put("healthStatus", p.getHealthStatus());
            item.put("lastHealthCheck", p.getLastHealthCheck());
            item.put("orderInGroup", m.getOrderInGroup());
            item.put("enabled", m.getEnabled());
            result.add(item);
        }
        return result;
    }

    /** 全部平台 + 各自已归属的分组名，供绑定弹窗渲染 */
    public List<Map<String, Object>> listProviderCandidates() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Provider p : fileStore.getAllProviders()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("providerId", p.getId());
            item.put("providerName", p.getName());
            item.put("baseUrl", p.getBaseUrl());
            item.put("selectedModel", p.getSelectedModel());
            item.put("providerStatus", p.getStatus());
            item.put("healthStatus", p.getHealthStatus());
            List<String> belongs = new ArrayList<>();
            for (GroupMember m : groupStore.getMembersByProviderId(p.getId())) {
                groupStore.getGroup(m.getGroupId())
                        .ifPresent(g -> belongs.add(g.getGroupKey()));
            }
            item.put("belongsTo", belongs);
            result.add(item);
        }
        return result;
    }

    // ==================== 写入 ====================

    public ResourceGroup createGroup(ResourceGroup input) {
        input.setId(null);
        normalizeAndValidate(input, null);
        if (input.getApiKey() == null || input.getApiKey().isBlank()) {
            input.setApiKey(generateApiKey(input.getGroupKey()));
        }
        ResourceGroup saved = groupStore.saveGroup(input);
        log.info("[GROUP] 新建分组 id={} groupKey={} name={}",
                saved.getId(), saved.getGroupKey(), saved.getName());
        return saved;
    }

    public ResourceGroup updateGroup(Long id, ResourceGroup input) {
        ResourceGroup existing = groupStore.getGroup(id)
                .orElseThrow(() -> new IllegalArgumentException("分组不存在: id=" + id));
        input.setId(id);
        normalizeAndValidate(input, id);
        // apiKey 留空表示不修改
        if (input.getApiKey() == null || input.getApiKey().isBlank()) {
            input.setApiKey(existing.getApiKey());
        }
        input.setCreatedAt(existing.getCreatedAt());
        ResourceGroup saved = groupStore.saveGroup(input);
        log.info("[GROUP] 更新分组 id={} groupKey={}", saved.getId(), saved.getGroupKey());
        return saved;
    }

    public void deleteGroup(Long id) {
        ResourceGroup g = groupStore.getGroup(id)
                .orElseThrow(() -> new IllegalArgumentException("分组不存在: id=" + id));
        if (GroupStoreService.DEFAULT_GROUP_KEY.equals(g.getGroupKey())) {
            throw new IllegalArgumentException("默认分组不允许删除");
        }
        groupStore.deleteGroup(id);
        log.info("[GROUP] 删除分组 id={} groupKey={}", id, g.getGroupKey());
    }

    /** 重新生成该分组的访问密钥 */
    public ResourceGroup regenerateApiKey(Long id) {
        ResourceGroup g = groupStore.getGroup(id)
                .orElseThrow(() -> new IllegalArgumentException("分组不存在: id=" + id));
        g.setApiKey(generateApiKey(g.getGroupKey()));
        ResourceGroup saved = groupStore.saveGroup(g);
        log.info("[GROUP] 重新生成密钥 groupKey={}", saved.getGroupKey());
        return saved;
    }

    /**
     * 批量替换分组绑定的平台。
     * providerIds 的数组顺序即组内轮询顺序（orderInGroup 从 0 递增）。
     */
    public List<Map<String, Object>> replaceMembers(Long groupId, List<Long> providerIds) {
        groupStore.getGroup(groupId)
                .orElseThrow(() -> new IllegalArgumentException("分组不存在: id=" + groupId));
        List<GroupMember> members = new ArrayList<>();
        int order = 0;
        for (Long pid : providerIds) {
            if (fileStore.getProvider(pid).isEmpty()) {
                log.warn("[GROUP] 忽略不存在的平台 id={}", pid);
                continue;
            }
            GroupMember m = new GroupMember();
            m.setGroupId(groupId);
            m.setProviderId(pid);
            m.setOrderInGroup(order++);
            m.setEnabled(true);
            members.add(m);
        }
        groupStore.replaceMembers(groupId, members);
        log.info("[GROUP] 分组 id={} 绑定平台更新为 {}", groupId, providerIds);
        return listMemberViews(groupId);
    }

    /** 组内单独启用/禁用某个平台 */
    public Map<String, Object> toggleMember(Long groupId, Long providerId, boolean enabled) {
        GroupMember m = groupStore.findMember(groupId, providerId)
                .orElseThrow(() -> new IllegalArgumentException("该平台未绑定到此分组"));
        m.setEnabled(enabled);
        groupStore.saveMember(m);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupId", groupId);
        result.put("providerId", providerId);
        result.put("enabled", enabled);
        return result;
    }

    /** 调整组内轮询顺序：传入按目标顺序排列的 providerId 列表 */
    public List<Map<String, Object>> reorderMembers(Long groupId, List<Long> providerIds) {
        int order = 0;
        for (Long pid : providerIds) {
            Optional<GroupMember> mo = groupStore.findMember(groupId, pid);
            if (mo.isEmpty()) continue;
            GroupMember m = mo.get();
            m.setOrderInGroup(order++);
            groupStore.saveMember(m);
        }
        return listMemberViews(groupId);
    }

    // ==================== 内部工具 ====================

    private void normalizeAndValidate(ResourceGroup g, Long selfId) {
        if (g.getGroupKey() == null || g.getGroupKey().isBlank()) {
            throw new IllegalArgumentException("groupKey 不能为空");
        }
        String key = g.getGroupKey().trim().toLowerCase();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("groupKey 只允许小写字母、数字和连字符，且需以字母或数字开头，长度 1-32");
        }
        if (RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("groupKey '" + key + "' 是保留字，请换一个");
        }
        Optional<ResourceGroup> dup = groupStore.getGroupByKey(key);
        if (dup.isPresent() && !dup.get().getId().equals(selfId)) {
            throw new IllegalArgumentException("groupKey '" + key + "' 已被其他分组占用");
        }
        g.setGroupKey(key);

        if (g.getName() == null || g.getName().isBlank()) {
            g.setName(key);
        }
        if (g.getStatus() == null || g.getStatus().isBlank()) {
            g.setStatus("ACTIVE");
        }
        if (g.getVirtualModels() == null) {
            g.setVirtualModels(new ArrayList<>());
        } else {
            g.getVirtualModels().removeIf(s -> s == null || s.isBlank());
        }
        // 默认分组不允许改 groupKey
        if (selfId != null) {
            groupStore.getGroup(selfId).ifPresent(old -> {
                if (GroupStoreService.DEFAULT_GROUP_KEY.equals(old.getGroupKey())
                        && !GroupStoreService.DEFAULT_GROUP_KEY.equals(g.getGroupKey())) {
                    throw new IllegalArgumentException("默认分组的 groupKey 不允许修改");
                }
            });
        }
    }

    private String generateApiKey(String groupKey) {
        StringBuilder sb = new StringBuilder("sk-");
        if (groupKey != null && !groupKey.isBlank()) {
            String prefix = groupKey.length() > 8 ? groupKey.substring(0, 8) : groupKey;
            sb.append(prefix).append('-');
        }
        for (int i = 0; i < 24; i++) {
            sb.append(KEY_CHARS.charAt(RANDOM.nextInt(KEY_CHARS.length())));
        }
        return sb.toString();
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 10) return "••••••";
        return key.substring(0, 6) + "••••" + key.substring(key.length() - 4);
    }
}
