package org.example.ai.impl.context;

import org.example.ai.impl.search.HybridRetriever;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RetrievalContextAssembler 黄金锚点测试（#23 ticket，预重构）。
 *
 * <p>锁定迁移前 CarSalesAgent.retrieveContext 的现状行为——未受限路径
 * （主路径全量展开 / 回退泛检索）必须字节级一致。后续分层检索（#24/#25/#26）
 * 新增级别分支时，这些用例是"现状零回归"的守卫。</p>
 */
@ExtendWith(MockitoExtension.class)
class RetrievalContextAssemblerTest {

    @Mock private HybridRetriever hybridRetriever;
    @Mock private VectorStore vectorStore;

    private RetrievalContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new RetrievalContextAssembler(hybridRetriever, vectorStore);
    }

    // ---- 现状锚点 1：主路径（命中车系 → 父块 + 子块全量展开） ----

    @Test
    @DisplayName("主路径：父块召回命中 → 注入父块 + 子块全量展开，无百科/话术")
    void mainPathInjectsParentsAndAllChildren() {
        Document parent = doc("【比亚迪 宋PLUS DM-i】车系信息\n在售车型：2款",
                Map.of("series_id", "比亚迪-宋PLUS DM-i", "level", "parent"));
        when(hybridRetriever.search(anyString(), eq(3), eq(0.6), any(Filter.Expression.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(parent)));

        Document child1 = doc("宋PLUS DM-i 旗舰，白色，全款15.88万",
                Map.of("sku_id", 1L, "level", "child"));
        Document child2 = doc("宋PLUS DM-i 尊贵，灰色，全款16.88万",
                Map.of("sku_id", 2L, "level", "child"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(child1, child2));

        String ctx = assembler.retrieveContext("有比亚迪宋吗", List.of());

        // 字节级黄金输出：段标题、顺序、前缀格式全部锁定
        assertThat(ctx).isEqualTo(
                "## 匹配车系\n"
                + "【比亚迪 宋PLUS DM-i】车系信息\n"
                + "在售车型：2款\n"
                + "\n"
                + "## 在售车型\n"
                + "- 宋PLUS DM-i 旗舰，白色，全款15.88万\n"
                + "- 宋PLUS DM-i 尊贵，灰色，全款16.88万\n");
    }

    @Test
    @DisplayName("主路径：子块按 sku_id 去重")
    void mainPathDeduplicatesChildrenBySkuId() {
        Document parent = doc("父块文本", Map.of("series_id", "比亚迪-宋PLUS DM-i"));
        when(hybridRetriever.search(anyString(), eq(3), eq(0.6), any(Filter.Expression.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(parent)));

        Document dup1 = doc("同一车源", Map.of("sku_id", 1L));
        Document dup2 = doc("同一车源（重复召回）", Map.of("sku_id", 1L));
        Document other = doc("另一车源", Map.of("sku_id", 2L));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(dup1, dup2, other));

        String ctx = assembler.retrieveContext("宋PLUS", List.of());

        assertThat(ctx).contains("同一车源").doesNotContain("重复召回");
        assertThat(ctx).contains("另一车源");
    }

    // ---- 现状锚点 2：回退路径（无命中 → 子块泛检索 + 百科/话术） ----

    @Test
    @DisplayName("回退路径：无任何车系命中 → 子块泛检索 + 百科话术，无父块段")
    void fallbackPathInjectsChildrenAndKnowledge() {
        when(hybridRetriever.search(anyString(), eq(3), eq(0.6), any(Filter.Expression.class)))
                .thenReturn(new java.util.ArrayList<>());

        Document child = doc("某车源子块", Map.of("level", "child"));
        Document kb = doc("购车话术段落", Map.of("type", "话术"));
        // 第一次调用=子块泛检索，第二次=百科/话术
        when(hybridRetriever.search(anyString(), eq(5), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of(child));
        when(hybridRetriever.search(anyString(), eq(3), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of(kb));

        String ctx = assembler.retrieveContext("买车要注意什么", List.of());

        // 现状行为：无父块段时上下文以换行开头（字节级保留）
        assertThat(ctx).isEqualTo(
                "\n## 在售车型\n"
                + "- 某车源子块\n"
                + "\n"
                + "## 相关知识\n"
                + "- [话术] 购车话术段落\n");
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    // ---- 现状锚点 3：EntityResolver 命中车系的父块补充 ----

    @Test
    @DisplayName("EntityResolver 命中但父块检索未召回 → 按 series_id 补充父块")
    void entityHitSupplementsParent() {
        when(hybridRetriever.search(anyString(), eq(3), eq(0.6), any(Filter.Expression.class)))
                .thenReturn(new java.util.ArrayList<>()); // 相似度没搜到

        ResolvedEntity re = new ResolvedEntity(
                "entity:car:比亚迪:汉ev", "比亚迪-汉EV", "比亚迪", "汉EV");
        Document parent = doc("【比亚迪 汉EV】车系信息", Map.of("series_id", "比亚迪-汉EV"));
        Document child = doc("汉EV 车源", Map.of("sku_id", 9L));
        // 补充父块查询 + 子块展开查询
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(parent))
                .thenReturn(List.of(child));

        String ctx = assembler.retrieveContext("汉EV", List.of(re));

        assertThat(ctx).contains("## 匹配车系").contains("【比亚迪 汉EV】车系信息");
        assertThat(ctx).contains("## 在售车型").contains("- 汉EV 车源");
    }

    // ---- helpers ----

    private static Document doc(String text, Map<String, Object> meta) {
        return new Document(text, new java.util.HashMap<>(meta));
    }
}