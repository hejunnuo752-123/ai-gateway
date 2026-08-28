package com.aigateway.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分组与 AI 平台的绑定关系（多对多）。
 *
 * 一个 Provider 可以同时属于多个分组，各分组内的轮询序号相互独立
 * （orderInGroup 与 Provider.sortOrder 无关），也可以在某个分组内
 * 单独禁用而不影响其他分组。
 */
@Data
public class GroupMember {

    private Long id;

    private Long groupId;

    private Long providerId;

    /** 组内轮询序号，从小到大。与 Provider.sortOrder 完全独立 */
    private Integer orderInGroup = 0;

    /** 组内启用开关，false 时该分组不会轮询此平台 */
    private Boolean enabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
