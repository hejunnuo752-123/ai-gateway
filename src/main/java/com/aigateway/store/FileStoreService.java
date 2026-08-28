package com.aigateway.store;

import com.aigateway.model.ModelConfig;
import com.aigateway.model.Provider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class FileStoreService {

    @Value("${ai-gateway.data-dir:data}")
    private String dataDir;

    private final Map<Long, Provider> providerCache = new ConcurrentHashMap<>();
    private final Map<Long, ModelConfig> modelCache = new ConcurrentHashMap<>();

    private final AtomicLong providerIdSeq = new AtomicLong(0);
    private final AtomicLong modelIdSeq = new AtomicLong(0);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Path providerFile;
    private Path modelFile;

    @PostConstruct
    public void init() throws IOException {
        Path dataPath = Paths.get(dataDir);
        Files.createDirectories(dataPath);
        providerFile = dataPath.resolve("providers.json");
        modelFile = dataPath.resolve("models.json");
        loadProviders();
        loadModels();
    }

    // ==================== Provider ====================

    public List<Provider> getAllProviders() {
        List<Provider> list = new ArrayList<>(providerCache.values());
        list.sort(Comparator.comparing(Provider::getSortOrder,
                Comparator.nullsLast(Integer::compareTo))
                .thenComparing(Provider::getId));
        return list;
    }

    public Optional<Provider> getProvider(Long id) {
        return Optional.ofNullable(providerCache.get(id));
    }

    public Provider saveProvider(Provider p) {
        if (p.getId() == null) {
            p.setId(providerIdSeq.incrementAndGet());
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
        } else {
            // 保留已有的健康状态（UI 编辑不会传这些字段）
            Provider existing = providerCache.get(p.getId());
            if (existing != null) {
                if (p.getHealthStatus() == null) {
                    p.setHealthStatus(existing.getHealthStatus());
                }
                if (p.getLastHealthCheck() == null) {
                    p.setLastHealthCheck(existing.getLastHealthCheck());
                }
            }
            p.setUpdatedAt(LocalDateTime.now());
        }
        if (p.getStatus() == null) {
            p.setStatus("ACTIVE");
        }
        providerCache.put(p.getId(), p);
        persistProviders();
        return p;
    }

    public void deleteProvider(Long id) {
        providerCache.remove(id);
        // 同时删除该平台下的所有模型
        modelCache.entrySet().removeIf(e -> e.getValue().getProviderId().equals(id));
        persistProviders();
        persistModels();
    }

    // ==================== ModelConfig ====================

    public List<ModelConfig> getModelsByProviderId(Long providerId) {
        return modelCache.values().stream()
                .filter(m -> m.getProviderId().equals(providerId))
                .sorted(Comparator.comparing(ModelConfig::getModelName))
                .collect(Collectors.toList());
    }

    public Optional<ModelConfig> getModel(Long id) {
        return Optional.ofNullable(modelCache.get(id));
    }

    public ModelConfig saveModel(ModelConfig m) {
        if (m.getId() == null) {
            m.setId(modelIdSeq.incrementAndGet());
            m.setCreatedAt(LocalDateTime.now());
            m.setUpdatedAt(LocalDateTime.now());
        } else {
            m.setUpdatedAt(LocalDateTime.now());
        }
        if (m.getContextLength() == null) m.setContextLength(4096);
        if (m.getSupportsVision() == null) m.setSupportsVision(false);
        modelCache.put(m.getId(), m);
        persistModels();
        return m;
    }

    public List<ModelConfig> getAllModels() {
        return new ArrayList<>(modelCache.values());
    }

    public void deleteModel(Long id) {
        modelCache.remove(id);
        persistModels();
    }

    /**
     * 批量保存模型（从外部平台拉取后覆盖式导入）。
     * 先删除该平台所有旧模型，再批量插入新模型。
     */
    public List<ModelConfig> replaceModelsByProvider(Long providerId, List<ModelConfig> newModels) {
        // 删除旧模型
        modelCache.entrySet().removeIf(e -> e.getValue().getProviderId().equals(providerId));
        // 批量插入
        for (ModelConfig m : newModels) {
            m.setId(modelIdSeq.incrementAndGet());
            m.setProviderId(providerId);
            m.setCreatedAt(LocalDateTime.now());
            m.setUpdatedAt(LocalDateTime.now());
            if (m.getContextLength() == null) m.setContextLength(4096);
            if (m.getSupportsVision() == null) m.setSupportsVision(false);
            modelCache.put(m.getId(), m);
        }
        persistModels();
        return getModelsByProviderId(providerId);
    }

    // ==================== 持久化 ====================

    private void loadProviders() {
        try {
            if (Files.exists(providerFile) && Files.size(providerFile) > 0) {
                List<Provider> list = mapper.readValue(providerFile.toFile(),
                        new TypeReference<List<Provider>>() {});
                for (Provider p : list) {
                    providerCache.put(p.getId(), p);
                    if (p.getId() > providerIdSeq.get()) {
                        providerIdSeq.set(p.getId());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load providers.json: " + e.getMessage());
        }
    }

    private void loadModels() {
        try {
            if (Files.exists(modelFile) && Files.size(modelFile) > 0) {
                List<ModelConfig> list = mapper.readValue(modelFile.toFile(),
                        new TypeReference<List<ModelConfig>>() {});
                for (ModelConfig m : list) {
                    modelCache.put(m.getId(), m);
                    if (m.getId() > modelIdSeq.get()) {
                        modelIdSeq.set(m.getId());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load models.json: " + e.getMessage());
        }
    }

    private synchronized void persistProviders() {
        try {
            List<Provider> list = new ArrayList<>(providerCache.values());
            list.sort(Comparator.comparing(Provider::getId));
            mapper.writerWithDefaultPrettyPrinter().writeValue(providerFile.toFile(), list);
        } catch (IOException e) {
            System.err.println("Failed to persist providers.json: " + e.getMessage());
        }
    }

    private synchronized void persistModels() {
        try {
            List<ModelConfig> list = new ArrayList<>(modelCache.values());
            list.sort(Comparator.comparing(ModelConfig::getId));
            mapper.writerWithDefaultPrettyPrinter().writeValue(modelFile.toFile(), list);
        } catch (IOException e) {
            System.err.println("Failed to persist models.json: " + e.getMessage());
        }
    }
}
