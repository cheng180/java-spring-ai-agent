package org.example.ai.knowledge.facts;

import java.util.List;

/**
 * 原子事实提取器接口（#1 决策2）。
 * 提供双路径：结构化数据走 SQL+模板，非结构化文档走 LLM 命题提取。
 */
public interface AtomicFactExtractor {

    /**
     * 从源数据提取原子事实列表。
     * @return 可独立检索的 AtomicFact 列表
     */
    List<AtomicFact> extract();
}