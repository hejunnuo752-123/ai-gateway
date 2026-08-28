package com.aigateway.controller;

import com.aigateway.store.SettingsStoreService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    private final SettingsStoreService settingsStore;

    public PageController(SettingsStoreService settingsStore) {
        this.settingsStore = settingsStore;
    }

    @GetMapping("/")
    public String index(HttpServletResponse response, Model model) {
        setNoCache(response);
        injectBg(model);
        return "providers";
    }

    @GetMapping("/ai-platforms")
    public String aiPlatforms(HttpServletResponse response, Model model) {
        setNoCache(response);
        injectBg(model);
        return "ai-platforms";
    }

    @GetMapping("/groups")
    public String groups(HttpServletResponse response, Model model) {
        setNoCache(response);
        injectBg(model);
        return "groups";
    }

    /** 登录页 —— 在 AdminAuthFilter 白名单内，未登录可访问 */
    @GetMapping("/login")
    public String login(HttpServletResponse response, Model model) {
        setNoCache(response);
        injectBg(model);
        return "login";
    }

    /**
     * 背景图走服务端渲染注入，而不是前端 JS 拉接口再改 src。
     * 这样页面首帧就是正确的背景，不会出现「先闪默认图再切换」的抖动。
     * 带上时间戳做 cache buster —— 图片 URL 变了才变，同一张图仍走浏览器缓存。
     */
    private void injectBg(Model model) {
        String url = settingsStore.getBackgroundUrl();
        model.addAttribute("bgUrl", url);
    }

    private void setNoCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
    }
}
