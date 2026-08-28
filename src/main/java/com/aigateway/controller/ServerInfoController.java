package com.aigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 返回服务器对外可访问的地址信息，
 * 用于管理页面「API KEY 获取」弹窗中显示真正的 Base URL。
 */
@RestController
public class ServerInfoController {

    @GetMapping("/api/server-info")
    public Map<String, Object> serverInfo(HttpServletRequest req) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("host", resolveHost(req));
        info.put("port", req.getServerPort());
        info.put("scheme", req.getScheme());
        info.put("baseUrl", info.get("scheme") + "://" + info.get("host") + ":" + info.get("port"));
        return info;
    }

    /**
     * 优先使用反向代理/负载均衡传入的 X-Forwarded-Host；
     * 否则挑选本机非环回网卡 IP（优先返回站点本地 / 局域网地址）。
     */
    private String resolveHost(HttpServletRequest req) {
        String fwdHost = req.getHeader("X-Forwarded-Host");
        if (fwdHost != null && !fwdHost.isBlank()) {
            return fwdHost.split(",")[0].trim();
        }
        try {
            return NetworkInterface.networkInterfaces()
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(addr -> !addr.isLoopbackAddress()
                            && !addr.getHostAddress().contains(":")
                            && !addr.getHostAddress().startsWith("0.")
                            && !addr.getHostAddress().startsWith("169.254."))
                    .filter(InetAddress::isSiteLocalAddress)
                    .map(InetAddress::getHostAddress)
                    .findFirst()
                    .orElse(req.getServerName());
        } catch (Exception e) {
            return req.getServerName();
        }
    }
}