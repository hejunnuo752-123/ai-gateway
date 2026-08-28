package com.aigateway.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Provider {
    private Long id;
    private String name;
    private String type;          // NATIVE / OPENAI
    private String baseUrl;
    private String apiKey;
    private String status = "ACTIVE";

    // 用户在平台弹窗中单选的模型，和平台一起持久化
    private String selectedModel;

    // 轮询调用序号，从小到大排序。
    // 刻意不给默认值：ProviderService.save() 靠 null 判断「这是新平台，排到末尾」。
    // 所有排序处（FileStoreService.getAllProviders / ChatForwardService.findProviders）
    // 都用了 Comparator.nullsLast，null 会被排到最后，与语义一致。
    private Integer sortOrder;

    // 健康状态：UNKNOWN=尚未调用, NORMAL=最近调用成功, ERROR=最近调用失败
    private String healthStatus = "UNKNOWN";

    // 最后一次健康检测时间
    private LocalDateTime lastHealthCheck;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
