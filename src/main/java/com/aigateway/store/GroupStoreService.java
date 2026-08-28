package com.aigateway.store;

import com.aigateway.config.VirtualModelProperties;
import com.aigateway.model.GroupMember;
import com.aigateway.model.ResourceGroup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 分组存储服务 —— 独立于 FileStoreService，不修改后者一行代码。
 *
 * 负责 data/groups.json 与 data/group-members.json 的加载与持久化。
 */
@Service
public class GroupStoreService {

    private static final Logger log = LoggerFactory.getLogger(GroupStoreService.class);

    /** 默认分组的路由标识，首次启动时自动创建 */
    public static final String DEFAULT_GROUP_KEY = "default";

    @Value("${ai-gateway.data-dir:data}")
    private String dataDir;

    @Value("${ai-gateway.default-group-api-key:sk-vq5zqQQQxCPN3r}")
    private String defaultGroupApiKey;

    private final VirtualModelProperties virtualModelProps;

    private final Map<Long, ResourceGroup> groupCache = new ConcurrentHashMap<>();
    private final Map<Long, GroupMember> memberCache = new ConcurrentHashMap<>();

    private final AtomicLong groupIdSeq = new AtomicLong(0);
    private final AtomicLong memberIdSeq = new AtomicLong(0);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Path groupFile;
    private Path memberFile;

    public GroupStoreService(VirtualModelProperties virtualModelProps) {
        this.virtualModelProps = virtualModelProps;
    }

    @PostConstruct
    public void init() throws IOException {
        Path dataPath = Paths.get(dataDir);
        Files.createDirectories(dataPath);
        groupFile = dataPath.resolve("groups.json");
        memberFile = dataPath.resolve("group-members.json");
        loadGroups();
        loadMembers();
        ensureDefaultGroup();
    }

    // ==================== ResourceGroup ====================

    public List<ResourceGroup> getAllGroups() {
        List<ResourceGroup> list = new ArrayList<>(groupCache.values());
        list.sort(Comparator.comparing(ResourceGroup::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(ResourceGroup::getId));
        return list;
    }

    public Optional<ResourceGroup> getGroup(Long id) {
        return Optional.ofNullable(groupCache.get(id));
    }

    public Optional<ResourceGroup> getGroupByKey(String groupKey) {
        if (groupKey == null) return Optional.empty();
        String key = groupKey.trim().toLowerCase();
        return groupCache.values().stream()
                .filter(g -> key.equals(g.getGroupKey()))
                .findFirst();
    }

    public ResourceGroup saveGroup(ResourceGroup g) {
        if (g.getId() == null) {
            g.setId(groupIdSeq.incrementAndGet());
            g.setCreatedAt(LocalDateTime.now());
        }
        g.setUpdatedAt(LocalDateTime.now());
        if (g.getStatus() == null) g.setStatus("ACTIVE");
        if (g.getSortOrder() == null) g.setSortOrder(0);
        if (g.getVirtualModels() == null) g.setVirtualModels(new ArrayList<>());
        groupCache.put(g.getId(), g);
        persistGroups();
        return g;
    }

    public void deleteGroup(Long id) {
        groupCache.remove(id);
        memberCache.entrySet().removeIf(e -> id.equals(e.getValue().getGroupId()));
        persistGroups();
        persistMembers();
    }

    // ==================== GroupMember ====================

    public List<GroupMember> getMembersByGroupId(Long groupId) {
        return memberCache.values().stream()
                .filter(m -> groupId.equals(m.getGroupId()))
                .sorted(Comparator.comparing(GroupMember::getOrderInGroup,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(GroupMember::getId))
                .collect(Collectors.toList());
    }

    public List<GroupMember> getMembersByProviderId(Long providerId) {
        return memberCache.values().stream()
                .filter(m -> providerId.equals(m.getProviderId()))
                .collect(Collectors.toList());
    }

    public Optional<GroupMember> getMember(Long id) {
        return Optional.ofNullable(memberCache.get(id));
    }

    public Optional<GroupMember> findMember(Long groupId, Long providerId) {
        return memberCache.values().stream()
                .filter(m -> groupId.equals(m.getGroupId()) && providerId.equals(m.getProviderId()))
                .findFirst();
    }

    public GroupMember saveMember(GroupMember m) {
        if (m.getId() == null) {
            m.setId(memberIdSeq.incrementAndGet());
            m.setCreatedAt(LocalDateTime.now());
        }
        m.setUpdatedAt(LocalDateTime.now());
        if (m.getEnabled() == null) m.setEnabled(true);
        if (m.getOrderInGroup() == null) m.setOrderInGroup(0);
        memberCache.put(m.getId(), m);
        persistMembers();
        return m;
    }

    public void deleteMember(Long id) {
        memberCache.remove(id);
        persistMembers();
    }

    /** 批量替换某个分组的绑定关系，用于「管理绑定平台」弹窗一次性提交 */
    public List<GroupMember> replaceMembers(Long groupId, List<GroupMember> newMembers) {
        memberCache.entrySet().removeIf(e -> groupId.equals(e.getValue().getGroupId()));
        int order = 0;
        for (GroupMember m : newMembers) {
            m.setId(memberIdSeq.incrementAndGet());
            m.setGroupId(groupId);
            if (m.getOrderInGroup() == null) m.setOrderInGroup(order);
            if (m.getEnabled() == null) m.setEnabled(true);
            m.setCreatedAt(LocalDateTime.now());
            m.setUpdatedAt(LocalDateTime.now());
            memberCache.put(m.getId(), m);
            order++;
        }
        persistMembers();
        return getMembersByGroupId(groupId);
    }

    /** 供 Provider 删除后清理悬挂绑定使用 */
    public void deleteMembersByProviderId(Long providerId) {
        boolean changed = memberCache.entrySet()
                .removeIf(e -> providerId.equals(e.getValue().getProviderId()));
        if (changed) persistMembers();
    }

    // ==================== 初始化与持久化 ====================

    /**
     * 首次启动时创建 default 分组：
     * apiKey 取 application.yml 的 default-group-api-key，
     * virtualModels 取 VirtualModelProperties。
     * 不自动绑定任何平台 —— 旧 /v1/** 链路依然走旧逻辑，此处零风险。
     */
    private void ensureDefaultGroup() {
        if (getGroupByKey(DEFAULT_GROUP_KEY).isPresent()) return;

        ResourceGroup g = new ResourceGroup();
        g.setGroupKey(DEFAULT_GROUP_KEY);
        g.setName("默认组");
        g.setApiKey(defaultGroupApiKey);
        g.setStatus("ACTIVE");
        g.setSortOrder(0);
        g.setRemark("首次启动自动创建，虚拟模型与密钥来自 application.yml");
        List<String> vms = virtualModelProps.getVirtualModels().stream()
                .map(VirtualModelProperties.VirtualModel::getModelName)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.toList());
        g.setVirtualModels(vms);
        saveGroup(g);
        log.info("[GROUP] 已初始化默认分组 groupKey={} 虚拟模型={}", DEFAULT_GROUP_KEY, vms);
    }

    private void loadGroups() {
        try {
            if (Files.exists(groupFile) && Files.size(groupFile) > 0) {
                List<ResourceGroup> list = mapper.readValue(groupFile.toFile(),
                        new TypeReference<List<ResourceGroup>>() {});
                for (ResourceGroup g : list) {
                    groupCache.put(g.getId(), g);
                    if (g.getId() > groupIdSeq.get()) groupIdSeq.set(g.getId());
                }
            }
        } catch (IOException e) {
            log.error("Failed to load groups.json: {}", e.getMessage());
        }
    }

    private void loadMembers() {
        try {
            if (Files.exists(memberFile) && Files.size(memberFile) > 0) {
                List<GroupMember> list = mapper.readValue(memberFile.toFile(),
                        new TypeReference<List<GroupMember>>() {});
                for (GroupMember m : list) {
                    memberCache.put(m.getId(), m);
                    if (m.getId() > memberIdSeq.get()) memberIdSeq.set(m.getId());
                }
            }
        } catch (IOException e) {
            log.error("Failed to load group-members.json: {}", e.getMessage());
        }
    }

    private synchronized void persistGroups() {
        List<ResourceGroup> list = new ArrayList<>(groupCache.values());
        list.sort(Comparator.comparing(ResourceGroup::getId));
        writeAtomic(groupFile, list, "groups.json");
    }

    private synchronized void persistMembers() {
        List<GroupMember> list = new ArrayList<>(memberCache.values());
        list.sort(Comparator.comparing(GroupMember::getId));
        writeAtomic(memberFile, list, "group-members.json");
    }

    /** 先写 .tmp 再原子替换，避免写入中断导致 JSON 损坏 */
    private void writeAtomic(Path target, Object data, String label) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to persist {}: {}", label, e.getMessage());
        }
    }
}
