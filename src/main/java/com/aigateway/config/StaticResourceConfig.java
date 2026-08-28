package com.aigateway.config;

import com.aigateway.store.SettingsStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 /bg/** 映射到 jar 外部的 data/backgrounds/ 目录。
 *
 * 为什么必须这么做：static/images/ 里的图打进 jar 后运行时不可写，
 * 用户上传的图只能落到 jar 外部，因此需要单独一条访问路径。
 *
 * Spring 的 ResourceHttpRequestHandler 自带路径穿越防护，
 * 加上 BackgroundService 生成的文件名带随机串，这条公开路径的暴露面是可控的。
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceConfig.class);

    private final SettingsStoreService settingsStore;

    public StaticResourceConfig(SettingsStoreService settingsStore) {
        this.settingsStore = settingsStore;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = settingsStore.getBackgroundDir().toAbsolutePath().normalize()
                .toUri().toString();
        registry.addResourceHandler("/bg/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
        log.info("[SETTINGS] /bg/** 已映射到 {}", location);
    }
}
