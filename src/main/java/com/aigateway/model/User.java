package com.aigateway.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台用户。持久化到 data/users.json。
 *
 * 密码不明文存储：passwordHash = PBKDF2WithHmacSHA256(password, salt, 120000 次, 256 位)。
 * salt 每个用户独立随机生成，与 hash 一同以 Base64 存放。
 */
@Data
public class User {

    private Long id;

    /** 登录名，全局唯一，比较时统一转小写 */
    private String username;

    /** Base64(PBKDF2 派生密钥) */
    private String passwordHash;

    /** Base64(16 字节随机盐) */
    private String salt;

    /** 预留角色字段，当前只有 ADMIN */
    private String role;

    /** 账号状态：ACTIVE / DISABLED */
    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 最近一次登录成功时间，登录页与审计用 */
    private LocalDateTime lastLoginAt;
}
