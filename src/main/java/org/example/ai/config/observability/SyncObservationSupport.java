package org.example.ai.config.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * 知识库同步的观测包裹工具。
 *
 * <p>背景：知识库同步（语料向量化、车源增量同步、SKU 变更）发生在请求上下文之外，
 * 其中每次 embedding / chroma 操作各自成为一条根 trace——一次启动产生几百条碎片
 * trace，淹没真正的对话链路。用本工具把整次同步包进<strong>一条</strong>
 * {@code knowledge-sync} 观测后，所有子操作（embedding、chroma query/add/delete、
 * 事实提取 LLM 调用）都挂在同一条 trace 下。</p>
 *
 * <p>松耦合：ObservationRegistry 由 Boot 可观测自动装配提供；没有 Langfuse 导出器时
 * 观测无消费者，包裹本身近乎零开销。</p>
 */
public final class SyncObservationSupport {

    /** 同步 trace 的根观测名（Langfuse 中按此名聚合所有知识库同步） */
    public static final String KNOWLEDGE_SYNC = "knowledge-sync";

    private SyncObservationSupport() {
    }

    /** 允许抛受检异常的同步工作体 */
    @FunctionalInterface
    public interface SyncWork {
        void run() throws Exception;
    }

    /** 带返回值的同步工作体 */
    @FunctionalInterface
    public interface SyncCallable<T> {
        T call() throws Exception;
    }

    /**
     * 在一条 {@code knowledge-sync} 观测内执行同步工作。
     *
     * @param registry  观测注册表（Boot 自动装配注入）
     * @param source    同步来源（低基数标签，如 corpus-docs / car-sku / sku-change）
     * @param work      实际同步逻辑
     */
    public static void trace(ObservationRegistry registry, String source, SyncWork work) throws Exception {
        call(registry, source, () -> {
            work.run();
            return null;
        });
    }

    /** {@link #trace} 的带返回值版本 */
    public static <T> T call(ObservationRegistry registry, String source, SyncCallable<T> work) throws Exception {
        Observation observation = Observation.createNotStarted(KNOWLEDGE_SYNC, registry)
                .lowCardinalityKeyValue("knowledge.sync.source", source);
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            return work.call();
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}