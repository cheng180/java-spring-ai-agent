package org.example.ai.knowledge.update;

import org.example.ai.config.DatabaseInitializer;
import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.impl.search.Bm25Indexer;
import org.example.ai.knowledge.entity.EntityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 内存知识结构刷新器 —— 车源变更广播的"下半场"。
 *
 * <p>{@link SkuVectorUpdater} 负责把变更写进 Chroma 向量库，但问答侧实际消费
 * 四份知识结构：</p>
 * <ol>
 *   <li>Chroma 向量（SkuVectorUpdater 直写，实时）</li>
 *   <li>BM25 内存索引（{@link Bm25Indexer}，启动时构建）</li>
 *   <li>实体别名索引（{@link EntityResolver}，启动时构建）</li>
 *   <li>动态关键词表（{@link DynamicKeywordBuilder}，启动时构建）</li>
 * </ol>
 *
 * <p>修复前 2/3/4 只在启动时构建，广播后不刷新 → 用户感知"知识库没更新，
 * 必须重启服务器"。本组件在每次变更处理完毕后按依赖顺序重建：
 * entity_mapping 表（数据源）→ 实体索引 → 关键词表 → BM25 索引。</p>
 */
@Component
public class InMemoryIndexRefresher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIndexRefresher.class);

    private final DatabaseInitializer databaseInitializer;
    private final EntityResolver entityResolver;
    private final DynamicKeywordBuilder keywordBuilder;
    private final Bm25Indexer bm25Indexer;

    public InMemoryIndexRefresher(DatabaseInitializer databaseInitializer,
                                  EntityResolver entityResolver,
                                  DynamicKeywordBuilder keywordBuilder,
                                  Bm25Indexer bm25Indexer) {
        this.databaseInitializer = databaseInitializer;
        this.entityResolver = entityResolver;
        this.keywordBuilder = keywordBuilder;
        this.bm25Indexer = bm25Indexer;
    }

    /**
     * 按依赖顺序重建全部内存知识结构。
     * entity_mapping 必须先于 EntityResolver/关键词表重建（它们从中读数）。
     */
    public synchronized void refreshAll() {
        long start = System.currentTimeMillis();
        databaseInitializer.rebuildEntityMapping();
        entityResolver.rebuild();
        keywordBuilder.rebuild();
        bm25Indexer.rebuild();
        log.info("InMemoryIndexRefresher: 内存知识结构已刷新（耗时 {} ms）",
                System.currentTimeMillis() - start);
    }
}