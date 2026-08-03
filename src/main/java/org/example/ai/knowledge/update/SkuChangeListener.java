package org.example.ai.knowledge.update;

/**
 * SKU 变更监听器接口（#3 决策8）。
 *
 * 首次实现为 Mock（通过 REST 接口手动触发模拟 MQ 消息）。
 * MQ 消息格式后续对接时替换为真正的消息消费实现。
 */
public interface SkuChangeListener {

    /**
     * 接收 MQ 车源变更通知。
     * @param event 包含 skuId、变更类型（INSERT/UPDATE/DELETE）、变更字段
     */
    void onSkuChanged(SkuChangeEvent event);
}