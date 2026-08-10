package org.example.ai.impl.conversation;

import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationGuidanceBuilderTest {

    @Mock
    private EntityResolver entityResolver;

    private ConversationGuidanceBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ConversationGuidanceBuilder(entityResolver);
    }

    @Test
    void inheritsSinglePreviousSeriesForVagueFollowUp() {
        ResolvedEntity series = entity("比亚迪", "宋PLUS DM-i");
        when(entityResolver.resolve(anyString())).thenReturn(List.of(series));

        ConversationGuidance guidance = builder.build("这个太贵了", "上一轮聊宋PLUS DM-i", List.of());

        assertThat(guidance.inheritedEntity()).isTrue();
        assertThat(guidance.entities()).containsExactly(series);
        assertThat(guidance.retrievalQuery()).contains("比亚迪-宋PLUS DM-i");
        assertThat(guidance.intent()).isEqualTo("PRICE_CONCERN");
        assertThat(guidance.prompt()).contains("不要重新罗列全部库存");
    }

    @Test
    void doesNotGuessWhenHistoryHasMultipleSeries() {
        ResolvedEntity first = entity("比亚迪", "宋PLUS DM-i");
        ResolvedEntity second = entity("特斯拉", "Model Y");
        when(entityResolver.resolve(anyString())).thenReturn(List.of(first, second));

        ConversationGuidance guidance = builder.build("这款怎么样", "之前聊过两款车", List.of());

        assertThat(guidance.inheritedEntity()).isFalse();
        assertThat(guidance.entities()).isEmpty();
        assertThat(guidance.clarificationSuggested()).isTrue();
        assertThat(guidance.prompt()).contains("不能自行猜测");
    }

    @Test
    void explicitEntityRemainsTheSourceOfTruth() {
        ResolvedEntity series = entity("宝马", "宝马X3");

        ConversationGuidance guidance = builder.build("宝马X3配置", "历史里还有其他车型", List.of(series));

        assertThat(guidance.inheritedEntity()).isFalse();
        assertThat(guidance.entities()).containsExactly(series);
        assertThat(guidance.retrievalQuery()).isEqualTo("宝马X3配置");
    }

    private static ResolvedEntity entity(String brand, String series) {
        return new ResolvedEntity("entity:" + brand + ":" + series, brand + "-" + series, brand, series);
    }
}