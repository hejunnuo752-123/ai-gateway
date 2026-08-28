package com.aigateway.service;

import com.aigateway.model.Provider;
import com.aigateway.store.FileStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ProviderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

    private final FileStoreService store;

    public ProviderService(FileStoreService store) {
        this.store = store;
    }

    public List<Provider> list() {
        return store.getAllProviders();
    }

    public Optional<Provider> get(Long id) {
        return store.getProvider(id);
    }

    public Provider save(Provider p) {
        // 新增平台且未指定序号时排到末尾，避免默认 0 抢占第一个被调用的位置
        if (p.getId() == null && p.getSortOrder() == null) {
            p.setSortOrder(nextSortOrder());
        }
        return store.saveProvider(p);
    }

    /**
     * 按传入的 id 顺序整表重写 sortOrder = 0..N-1。
     *
     * <p>不做两两交换：现有 providers.json 里的 sortOrder 是历史手填的散值，
     * 一旦出现重复值，交换后顺序不变（点了没反应）。整表重排天然幂等，
     * 每次操作后一定是连续无重复的 0 起序列。
     *
     * <p>ChatForwardService.findProviders() 与 FileStoreService.getAllProviders()
     * 用的是同一套 sortOrder 排序规则，所以这里写完即生效，两者都不用改。
     */
    public List<Provider> reorder(List<Long> ids) {
        Set<Long> seen = new HashSet<>();
        List<Long> ordered = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && seen.add(id)) {
                ordered.add(id);
            }
        }

        int order = 0;
        for (Long id : ordered) {
            Optional<Provider> po = store.getProvider(id);
            if (po.isEmpty()) continue;   // 前端拿的是旧快照，期间可能已被删除
            Provider p = po.get();
            p.setSortOrder(order++);
            store.saveProvider(p);
        }

        // 入参里没提到的（前端快照过期时别处新增的）追加到末尾，
        // 否则它们会残留旧序号，可能反而插到前面
        for (Provider p : store.getAllProviders()) {
            if (!seen.contains(p.getId())) {
                p.setSortOrder(order++);
                store.saveProvider(p);
            }
        }

        List<Provider> result = store.getAllProviders();
        log.info("[PROVIDER] 重排完成，共 {} 个平台，新调用顺序: {}",
                result.size(), result.stream().map(Provider::getName).toList());
        return result;
    }

    private int nextSortOrder() {
        int max = -1;
        for (Provider p : store.getAllProviders()) {
            if (p.getSortOrder() != null && p.getSortOrder() > max) {
                max = p.getSortOrder();
            }
        }
        return max + 1;
    }

    public void delete(Long id) {
        store.deleteProvider(id);
    }
}
