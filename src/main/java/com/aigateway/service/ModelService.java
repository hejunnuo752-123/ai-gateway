package com.aigateway.service;

import com.aigateway.model.ModelConfig;
import com.aigateway.model.Provider;
import com.aigateway.store.FileStoreService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class ModelService {

    private final FileStoreService store;
    private final ModelFetchService fetchService;
    private final ProviderService providerService;

    public ModelService(FileStoreService store,
                        ModelFetchService fetchService,
                        ProviderService providerService) {
        this.store = store;
        this.fetchService = fetchService;
        this.providerService = providerService;
    }

    public List<ModelConfig> listByProvider(Long providerId) {
        return store.getModelsByProviderId(providerId);
    }

    public Optional<ModelConfig> get(Long id) {
        return store.getModel(id);
    }

    public List<ModelConfig> listAll() {
        return store.getAllModels();
    }

    public ModelConfig save(ModelConfig m) {
        return store.saveModel(m);
    }

    public void delete(Long id) {
        store.deleteModel(id);
    }

    /**
     * 从外部平台拉取模型列表（仅 OPENAI 类型）。
     * 仅预览，不自动导入——用户需手动通过「添加模型」创建虚拟模型映射。
     */
    public List<ModelConfig> fetchModels(Long providerId) throws IOException {
        Provider p = providerService.get(providerId)
                .orElseThrow(() -> new IllegalArgumentException("平台不存在: " + providerId));

        if (!"OPENAI".equalsIgnoreCase(p.getType())) {
            throw new IllegalArgumentException("仅 OPENAI 类型平台支持自动获取模型列表");
        }

        return fetchService.fetchFromOpenAI(p);
    }
}
