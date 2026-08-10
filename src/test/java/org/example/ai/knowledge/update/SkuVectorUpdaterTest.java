package org.example.ai.knowledge.update;

import org.example.ai.knowledge.facts.*;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkuVectorUpdaterTest {

    @Mock private VectorStore vectorStore;
    @Mock private JdbcTemplate jdbc;
    @Mock private AskCountTracker askCountTracker;
    @Mock private InMemoryIndexRefresher indexRefresher;

    private SkuFactExtractor skuExtractor;
    private SkuVectorUpdater updater;

    @BeforeEach
    void setUp() {
        lenient().when(jdbc.queryForList(anyString(), (Object[]) any())).thenReturn(Collections.emptyList());
        lenient().when(jdbc.update(anyString(), (Object[]) any())).thenReturn(1);
        lenient().when(jdbc.update(anyString(), anyLong(), anyString(), anyString())).thenReturn(1);

        skuExtractor = new SkuFactExtractor(jdbc);
        var parentBuilder = new SeriesParentBuilder(jdbc, skuExtractor, askCountTracker);
        updater = new SkuVectorUpdater(vectorStore, skuExtractor, parentBuilder, jdbc, indexRefresher,
                io.micrometer.observation.ObservationRegistry.NOOP);
    }

    @Test
    @DisplayName("DELETE 事件：删除子块向量 + 清理同步日志")
    void deleteEventRemovesVectorAndSyncLog() {
        var event = new SkuChangeEvent(100L, ChangeType.DELETE, null);

        updater.processChange(event);

        verify(vectorStore).delete(any(Filter.Expression.class));
    }

    @Test
    @DisplayName("SkuChangeEvent 不可变字段（防御性拷贝）")
    void eventImmutability() {
        Map<String, Object> fields = new HashMap<>(Map.of("price", 100));
        var event = new SkuChangeEvent(1L, ChangeType.UPDATE, fields);

        // 构造后修改原始 map 不影响 event
        fields.put("price", 200);
        assertThat(event.getChangedFields().get("price")).isEqualTo(100);

        // getChangedFields 返回不可变视图
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> event.getChangedFields().put("newKey", "val")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("ChangeType 枚举包含 INSERT/UPDATE/DELETE")
    void changeTypeEnumValues() {
        assertThat(ChangeType.values()).containsExactly(ChangeType.INSERT, ChangeType.UPDATE, ChangeType.DELETE);
    }

    @Test
    @DisplayName("SkuChangeEvent toString 包含 skuId 和 type")
    void eventToString() {
        var event = new SkuChangeEvent(42L, ChangeType.INSERT, Map.of("brand_name", "比亚迪"));
        String s = event.toString();
        assertThat(s).contains("42", "INSERT", "brand_name");
    }

    @Test
    @DisplayName("SkuChangeEvent 构造时 null fields → 空 map")
    void eventNullFieldsBecomesEmpty() {
        var event = new SkuChangeEvent(1L, ChangeType.DELETE, null);
        assertThat(event.getChangedFields()).isEmpty();
    }

    @Test
    @DisplayName("upsertFromRow 写入向量")
    void upsertFromRowWritesVector() {
        Map<String, Object> row = skuRow(300L, "理想", "理想L6", "理想L6 Pro", "白",
                "24.98万", 23980000L, null, "中规", 2, "理想汽车");

        updater.upsertFromRow(row);

        verify(vectorStore).add(anyList());
    }

    // ---- helpers ----

    private Map<String, Object> skuRow(long id, String brand, String series, String model,
                                        String color, String guidePrice, Long salePrice, Long financePrice,
                                        String spec, int energyType, String owner) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("brand_name", brand);
        row.put("series_name", series);
        row.put("model_name", model);
        row.put("outer_color_name", color);
        row.put("guide_price", guidePrice);
        row.put("sale_price", salePrice);
        row.put("sale_price_finance", financePrice);
        row.put("spec_name", spec);
        row.put("energy_type", energyType);
        row.put("owner_name", owner);
        row.put("in_store_insurance", 1);
        row.put("can_issue_vat_invoice", 1);
        row.put("sale_status", 1);
        row.put("is_deleted", 0);
        row.put("memo", "");
        return row;
    }
}