package com.aigateway.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ModelConfig {
    private Long id;
    private Long providerId;
    private String modelName;

    /**
     * 上游平台实际使用的模型名。
     * 如果为空，则直接使用 modelName（虚拟模型名）转发。
     */
    private String upstreamModelName;

    private Integer contextLength;
    private Boolean supportsVision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
