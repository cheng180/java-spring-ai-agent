package org.example.ai.knowledge.facts;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 原子事实 —— 知识库的最小语义单元（#1 决策2）。
 * 每条事实自包含、可独立检索、携带完整的溯源和时效性元数据。
 */
public class AtomicFact {

    /** 来源文档/表 + 序号，如 "sku-100"、"encyc-byd-songplus-003" */
    private final String factId;

    /** 一条可独立理解的陈述文本 */
    private final String content;

    /** 归一化实体ID（#1 决策3），如 "entity:car:byd:song-plus" */
    private final String entityId;

    /** 时序类型 */
    private final TemporalType temporalType;

    /** 来源标识（文件名或 "car_sku表"） */
    private final String sourceDoc;

    /** 内容哈希（SHA-256），用于增量变更检测 */
    private final String sourceHash;

    /** 扩展元数据（检索时透传） */
    private final Map<String, Object> metadata;

    private AtomicFact(Builder builder) {
        this.factId = builder.factId;
        this.content = builder.content;
        this.entityId = builder.entityId;
        this.temporalType = builder.temporalType;
        this.sourceDoc = builder.sourceDoc;
        this.sourceHash = builder.sourceHash;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
    }

    // ---- getters ----

    public String getFactId() { return factId; }
    public String getContent() { return content; }
    public String getEntityId() { return entityId; }
    public TemporalType getTemporalType() { return temporalType; }
    public String getSourceDoc() { return sourceDoc; }
    public String getSourceHash() { return sourceHash; }
    public Map<String, Object> getMetadata() { return metadata; }

    // ---- builder ----

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String factId;
        private String content;
        private String entityId;
        private TemporalType temporalType;
        private String sourceDoc;
        private String sourceHash;
        private final Map<String, Object> metadata = new HashMap<>();

        public Builder factId(String factId) { this.factId = factId; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder entityId(String entityId) { this.entityId = entityId; return this; }
        public Builder temporalType(TemporalType temporalType) { this.temporalType = temporalType; return this; }
        public Builder sourceDoc(String sourceDoc) { this.sourceDoc = sourceDoc; return this; }
        public Builder sourceHash(String sourceHash) { this.sourceHash = sourceHash; return this; }
        public Builder metadata(String key, Object value) { this.metadata.put(key, value); return this; }
        public Builder metadataAll(Map<String, Object> map) { this.metadata.putAll(map); return this; }

        public AtomicFact build() {
            if (factId == null || factId.isBlank()) throw new IllegalArgumentException("factId is required");
            if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
            if (temporalType == null) throw new IllegalArgumentException("temporalType is required");
            return new AtomicFact(this);
        }
    }

    @Override
    public String toString() {
        return "AtomicFact{factId='" + factId + "', entityId='" + entityId
                + "', temporal=" + temporalType + ", hash=" + sourceHash + "}";
    }
}