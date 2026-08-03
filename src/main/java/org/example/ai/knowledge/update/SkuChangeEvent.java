package org.example.ai.knowledge.update;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * SKU 变更事件（#3 决策8）。
 * MQ 广播到达时构造，包含变更的 SKU ID、变更类型和变更字段。
 */
public class SkuChangeEvent {

    private final Long skuId;
    private final ChangeType type;
    private final Map<String, Object> changedFields;

    public SkuChangeEvent(Long skuId, ChangeType type, Map<String, Object> changedFields) {
        this.skuId = skuId;
        this.type = type;
        this.changedFields = changedFields == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(changedFields));
    }

    public Long getSkuId() { return skuId; }
    public ChangeType getType() { return type; }
    public Map<String, Object> getChangedFields() { return changedFields; }

    @Override
    public String toString() {
        return "SkuChangeEvent{skuId=" + skuId + ", type=" + type + ", fields=" + changedFields.keySet() + "}";
    }
}