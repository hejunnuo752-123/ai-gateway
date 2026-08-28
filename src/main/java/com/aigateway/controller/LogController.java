package com.aigateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 实时日志 SSE 推送。
 * 前端连接 /api/logs/forward 后，后端 tail 日志文件并持续推送新增行。
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    private static final String LOG_FILE = "logs/ai-gateway-forward.log";
    private static final long HEARTBEAT_MS = 15_000;
    private static final long POLL_MS = 500;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "log-sse-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    @GetMapping(value = "/forward", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamForwardLogs(@RequestParam(defaultValue = "100") int lastLines) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> tailLog(emitter, LOG_FILE, lastLines));
        return emitter;
    }

    /**
     * 安全发送 SSE 事件，客户端断开时返回 false。
     */
    private boolean safeSend(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
            return true;
        } catch (IOException e) {
            // 客户端断开是正常行为，不需要打 WARN
            return false;
        }
    }

    /**
     * 安全发送 SSE 注释（心跳），断开时返回 false。
     */
    private boolean safeSendComment(SseEmitter emitter, String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void tailLog(SseEmitter emitter, String logFile, int lastLines) {
        Path path = Paths.get(logFile);
        final boolean[] running = {true};
        emitter.onCompletion(() -> running[0] = false);
        emitter.onTimeout(() -> running[0] = false);
        emitter.onError(e -> running[0] = false);

        try {
            // 发送最近 N 行历史日志
            List<String> history = readLastLines(path, lastLines);
            for (String line : history) {
                if (!safeSend(emitter, line)) return; // 客户端断开，停止推送
            }

            long lastSize = Files.exists(path) ? Files.size(path) : 0;
            long lastHeartbeat = System.currentTimeMillis();

            while (running[0]) {
                try {
                    // 心跳：定期发 comment 检测连接是否存活
                    if (System.currentTimeMillis() - lastHeartbeat >= HEARTBEAT_MS) {
                        if (!safeSendComment(emitter, "ping")) break;
                        lastHeartbeat = System.currentTimeMillis();
                    }

                    if (!Files.exists(path)) {
                        Thread.sleep(POLL_MS);
                        continue;
                    }

                    long size = Files.size(path);
                    if (size < lastSize) {
                        // 日志被切割，重新读取
                        lastSize = 0;
                    }

                    if (size > lastSize) {
                        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                            raf.seek(lastSize);
                            String line;
                            while ((line = raf.readLine()) != null) {
                                // readLine 返回 ISO-8859-1，需要重新按 UTF-8 解码
                                String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                                if (!safeSend(emitter, decoded)) return; // 客户端断开
                            }
                            lastSize = raf.getFilePointer();
                        }
                    }

                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    log.debug("日志文件读取异常: {}", e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("日志 SSE 连接异常: {}", e.getMessage());
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private List<String> readLastLines(Path path, int n) {
        List<String> result = new ArrayList<>();
        if (!Files.exists(path)) {
            return result;
        }
        try {
            List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, all.size() - n);
            for (int i = start; i < all.size(); i++) {
                result.add(all.get(i));
            }
        } catch (IOException e) {
            log.warn("读取历史日志失败: {}", e.getMessage());
        }
        return result;
    }
}
