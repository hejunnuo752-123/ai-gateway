package com.aigateway.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局应用设置，持久化到 data/settings.json。
 *
 * 当前只有背景图一项，字段刻意留得宽松，后续加主题色 / 站点名之类不用改结构。
 * 设置是全局共享的（不区分用户）—— 系统目前只有一个 admin 账号，做成每用户偏好收益为零。
 */
@Data
public class AppSettings {

    /** 当前生效的背景图 URL，形如 /images/platform-bg.jpg 或 /bg/bg-20260827-a3f21b9c.jpg */
    private String backgroundUrl;

    /** 最后修改时间 */
    private LocalDateTime updatedAt;

    /** 最后修改人（登录用户名） */
    private String updatedBy;
}
