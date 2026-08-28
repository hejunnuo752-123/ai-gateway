package com.aigateway.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 资源池分组。
 *
 * 每个分组对外暴露一条独立路由 /g/{groupKey}/v1/**，
 * 使用该分组自己的 apiKey 鉴权，只轮询绑定到该分组的 Provider。
 *
 * 与旧的 /v1/** 链路完全独立，旧链路行为不受影响。
 */
@Data
public class ResourceGroup {

    private Long id;

    /** 路由标识，仅允许 [a-z0-9-]，全局唯一。例：team-rd */
    private String groupKey;

    /** 显示名。例：研发组 */
    private String name;

    /** 该分组对外访问密钥，客户端用 Bearer 或 x-api-key 传入 */
    private String apiKey;

    /** 该分组 /g/{groupKey}/v1/models 暴露的虚拟模型名列表 */
    private List<String> virtualModels = new ArrayList<>();

    /** ACTIVE / DISABLED */
    private String status = "ACTIVE";

    /** 管理页列表排序 */
    private Integer sortOrder = 0;

    private String remark;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
