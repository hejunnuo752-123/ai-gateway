package com.aigateway.service;

import com.aigateway.model.ModelConfig;
import com.aigateway.model.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ModelFetchService {

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 从 OpenAI 兼容平台获取模型列表。
     * 调用 GET {baseUrl}/models，解析返回的 data[].id。
     */
    public List<ModelConfig> fetchFromOpenAI(Provider provider) throws IOException {
        String url = provider.getBaseUrl();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url += "/models";

        Request.Builder builder = new Request.Builder()
                .url(url)
                .get();

        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + provider.getApiKey());
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }

            String body = response.body() != null ? response.body().string() : "";
            JsonNode root = mapper.readTree(body);

            List<ModelConfig> models = new ArrayList<>();
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode node : data) {
                    String modelId = node.get("id").asText();
                    ModelConfig mc = new ModelConfig();
                    mc.setModelName(modelId);
                    mc.setContextLength(4096);
                    mc.setSupportsVision(false);
                    mc.setProviderId(provider.getId());
                    models.add(mc);
                }
            }

            // 也尝试从 {"object":"list","data":[...]} 之外的扁平格式解析
            if (models.isEmpty() && root.isArray()) {
                for (JsonNode node : root) {
                    if (node.has("id")) {
                        ModelConfig mc = new ModelConfig();
                        mc.setModelName(node.get("id").asText());
                        mc.setContextLength(4096);
                        mc.setSupportsVision(false);
                        mc.setProviderId(provider.getId());
                        models.add(mc);
                    }
                }
            }

            return models;
        }
    }
}
