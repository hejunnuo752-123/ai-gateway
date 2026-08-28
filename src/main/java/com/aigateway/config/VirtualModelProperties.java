package com.aigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟模型配置 — 从 application.yml 读取，打进 JAR 内，不依赖外部文件。
 *
 * 配置示例：
 * ai-gateway:
 *   virtual-models:
 *     - model-name: claude-sonnet-4-6
 *       context-length: 200000
 *       supports-vision: true
 */
@Configuration
@ConfigurationProperties(prefix = "ai-gateway")
public class VirtualModelProperties {

    private List<VirtualModel> virtualModels = new ArrayList<>();

    public List<VirtualModel> getVirtualModels() {
        return virtualModels;
    }

    public void setVirtualModels(List<VirtualModel> virtualModels) {
        this.virtualModels = virtualModels;
    }

    public static class VirtualModel {
        private String modelName;
        private Integer contextLength = 4096;
        private Boolean supportsVision = false;

        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }

        public Integer getContextLength() { return contextLength; }
        public void setContextLength(Integer contextLength) { this.contextLength = contextLength; }

        public Boolean getSupportsVision() { return supportsVision; }
        public void setSupportsVision(Boolean supportsVision) { this.supportsVision = supportsVision; }
    }
}
