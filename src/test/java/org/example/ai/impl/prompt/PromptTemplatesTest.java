package org.example.ai.impl.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptTemplates 契约测试（#42 ticket）——锁定三块增补存在 +
 * 既有语义（乔哈里窗口/渐进式披露/幻觉红线/工具纪律）零改写保留。
 */
class PromptTemplatesTest {

    private final String prompt = PromptTemplates.systemPrompt("卖好车汽车销售有限公司");

    @Test
    @DisplayName("#42: 系统提示词含输出纪律、引导消费规则、场景篇幅上限三块")
    void containsThreeNewBlocks() {
        assertThat(prompt).contains("=== 输出纪律（篇幅与形态） ===");
        assertThat(prompt).contains("=== 引导提示的消费规则 ===");
        assertThat(prompt).contains("【5. 篇幅上限】");

        // 输出纪律的篇幅锚点
        assertThat(prompt).contains("3~5 句").contains("150~200 字");
        assertThat(prompt).contains("一轮一个重点");
        assertThat(prompt).contains("挑重点不罗列");
        assertThat(prompt).contains("数据多 ≠ 说得多");
        assertThat(prompt).contains("不堆参数");

        // 引导消费规则：选项上限 + 一次一问 + 不与级别指令打架
        assertThat(prompt).contains("## 引导提示");
        assertThat(prompt).contains("最多 1-2 个");
        assertThat(prompt).contains("反问一次只问一个");
        assertThat(prompt).contains("级别指令优先");

        // 场景篇幅上限三要素
        assertThat(prompt).contains("推荐每款只给一句理由");
        assertThat(prompt).contains("门店信息三行给全");
        assertThat(prompt).contains("只报那一个数字");
    }

    @Test
    @DisplayName("#42: 乔哈里四动作、渐进式披露、幻觉红线、工具纪律语义保留")
    void preservesExistingSemantics() {
        // 乔哈里窗口方法论（四动作）
        assertThat(prompt).contains("乔哈里窗口");
        assertThat(prompt).contains("【问】").contains("【露】").contains("【确认】").contains("【引导】");
        assertThat(prompt).contains("节奏铁律");

        // 渐进式披露层级
        assertThat(prompt).contains("=== 渐进式披露");
        assertThat(prompt).contains("绝不超前释放");

        // 幻觉红线
        assertThat(prompt).contains("=== 绝对禁止（幻觉红线） ===");
        assertThat(prompt).contains("永远不要编造具体的价格数字、地址、电话");

        // 工具纪律
        assertThat(prompt).contains("searchInventory(query)");
        assertThat(prompt).contains("getAllCars()");
        assertThat(prompt).contains("getStoreInfo(city)");
        assertThat(prompt).contains("查到 ≠ 要说");

        // 级别指令与场景规则
        assertThat(prompt).contains("=== 级别指令优先 ===");
        assertThat(prompt).contains("【1. 闲聊】").contains("【2. 问地址/联系方式】");
        assertThat(prompt).contains("【3. 买车相关】").contains("【4. 试驾/到店意向】");
    }

    @Test
    @DisplayName("公司名称注入生效")
    void companyNameInjected() {
        assertThat(prompt).contains("卖好车汽车销售有限公司");
        assertThat(PromptTemplates.systemPrompt("测试公司")).contains("测试公司");
    }
}