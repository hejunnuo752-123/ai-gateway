package com.aigateway.service;

import com.aigateway.model.AiPlatform;
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

@Service
public class AiPlatformService {

    @Value("${ai-gateway.data-dir:data}")
    private String dataDir;

    private final Map<Long, AiPlatform> cache = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Path dataFile;

    @PostConstruct
    public void init() throws IOException {
        Path dataPath = Paths.get(dataDir);
        Files.createDirectories(dataPath);
        dataFile = dataPath.resolve("ai-platforms.json");
        load();
        if (cache.isEmpty()) {
            seedDefaultData();
        }
    }

    public List<AiPlatform> list() {
        List<AiPlatform> list = new ArrayList<>(cache.values());
        list.sort(Comparator.comparing(AiPlatform::getSortOrder,
                Comparator.nullsLast(Integer::compareTo))
                .thenComparing(AiPlatform::getId));
        return list;
    }

    public Optional<AiPlatform> get(Long id) {
        return Optional.ofNullable(cache.get(id));
    }

    public AiPlatform save(AiPlatform p) {
        if (p.getId() == null) {
            p.setId(idSeq.incrementAndGet());
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
        } else {
            p.setUpdatedAt(LocalDateTime.now());
        }
        cache.put(p.getId(), p);
        persist();
        return p;
    }

    public void delete(Long id) {
        cache.remove(id);
        persist();
    }

    // ================== 持久化 ==================

    private void load() {
        try {
            if (Files.exists(dataFile) && Files.size(dataFile) > 0) {
                List<AiPlatform> list = mapper.readValue(dataFile.toFile(),
                        new TypeReference<List<AiPlatform>>() {});
                for (AiPlatform p : list) {
                    cache.put(p.getId(), p);
                    if (p.getId() > idSeq.get()) {
                        idSeq.set(p.getId());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load ai-platforms.json: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            List<AiPlatform> list = new ArrayList<>(cache.values());
            list.sort(Comparator.comparing(AiPlatform::getId));
            mapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), list);
        } catch (IOException e) {
            System.err.println("Failed to persist ai-platforms.json: " + e.getMessage());
        }
    }

    private void seedDefaultData() {
        seed("Agnes", "需要梯子", "https://agnes-ai.com/campaign", "AI平台官网", 0);
        seed("商汤科技", "日日新大模型平台", "https://platform.sensenova.cn/", "AI平台官网", 1);
        seed("小米 MiMo", "小米大模型平台", "https://platform.xiaomimimo.com/", "AI平台官网", 2);
        seed("Gemini", "Google AI Studio，需要梯子", "https://aistudio.google.com/", "AI平台官网", 3);
        seed("Opencode Zen/GO", "Openrouter，需要梯子", "https://openrouter.ai/", "AI平台官网", 4);
        seed("触站AI", "AI 创作平台", "https://open.czhanai.com/platform", "AI平台官网", 5);
        seed("火山引擎", "字节跳动云服务平台", "https://signin.volcengine.com/", "AI平台官网", 6);
        seed("智谱", "智谱 AI 开放平台", "https://open.bigmodel.cn/", "AI平台官网", 7);
        seed("阿里云百炼", "阿里云大模型平台", "https://bailian.console.aliyun.com/", "AI平台官网", 8);
        seed("英伟达", "NVIDIA AI 平台", "https://build.nvidia.com/", "AI平台官网", 9);
        seed("Nineone AI 网关", "Openrouter / Freemodel", "https://nineone.cyou", "AI平台官网", 10);
        seed("幻城网安公益大模型 API", "聚合网关", "https://api.iamhc.cn/pricing", "AI平台官网", 11);
        seed("DGB 公益站", "跟新疆平台一样", "https://freeapi.dgbmc.top/", "AI平台官网", 12);
    }

    private void seed(String name, String desc, String url, String category, int sortOrder) {
        AiPlatform p = new AiPlatform();
        p.setId(idSeq.incrementAndGet());
        p.setName(name);
        p.setDescription(desc);
        p.setUrl(url);
        p.setCategory(category);
        p.setSortOrder(sortOrder);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        cache.put(p.getId(), p);
    }
}
