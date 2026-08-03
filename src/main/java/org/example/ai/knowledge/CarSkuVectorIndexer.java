package org.example.ai.knowledge;

import org.example.ai.knowledge.update.SkuVectorUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 车源向量索引器 —— 父子文档结构（#2 决策5/6/7）+ 哈希增量同步（#3 决策10）。
 *
 * 分块策略：
 * - 子块：一条 SKU = 一个原子事实 → embedding
 * - 父块：一个车系 = 一个聚合文档 → embedding
 * - 检索时只返回子块，父块通过 metadata 的 parent_series_id 按需加载
 *
 * 启动时通过哈希对比识别变更行，只重建变化的向量（不再全量删光重建）。
 *
 * @Order(3)：必须在 DatabaseInitializer(1)、KnowledgeBaseInitializer(2) 之后
 */
@Component
@Order(3)
public class CarSkuVectorIndexer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CarSkuVectorIndexer.class);

    private final SkuVectorUpdater updater;

    public CarSkuVectorIndexer(SkuVectorUpdater updater) {
        this.updater = updater;
    }

    @Override
    public void run(String... args) {
        log.info("=== 开始同步车源向量（哈希增量模式） ===");
        Map<String, Integer> stats = updater.syncChangedSkus();
        log.info("=== 车源向量同步完成：变更 {} 条，未变 {} 条，清理 {} 条，父块 {} 个 ===",
                stats.get("changed"), stats.get("unchanged"),
                stats.get("deleted"), stats.get("parentsBuilt"));
    }
}