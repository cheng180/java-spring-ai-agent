package org.example.ai.impl.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdleChatGate 测试（#29 ticket）——闲聊判定反转为黑名单制。
 *
 * <p>只有命中"明确闲聊黑名单"的消息才判闲聊；其余一切消息（含未知话术的购车意图）
 * 默认非闲聊，交给业务管线——fail-safe 方向倒向业务侧。</p>
 */
class IdleChatGateTest {

    private final IdleChatGate gate = new IdleChatGate();

    // ---- 购车意图/业务消息：绝不判闲聊（本次事故的核心回归断言） ----

    @ParameterizedTest
    @ValueSource(strings = {
            "我想买x3",
            "帮我搞一台电车",
            "我要买20万的SUV",
            "想要台省油的车",
            "嗯嗯",
            "哈哈",
            "666",
            "你们有什么车",
            "你好，我想看看SUV",   // 寒暄开头但带业务意图——长度守卫放行
            "谢谢，那我再看看别的车"
    })
    @DisplayName("购车意图/业务/模糊短句 → 非闲聊（默认进业务管线）")
    void businessMessagesAreNotIdle(String message) {
        assertThat(gate.isIdleChat(message)).as(message).isFalse();
    }

    // ---- 明确闲聊：命中黑名单 ----

    @ParameterizedTest
    @ValueSource(strings = {
            "你好",
            "您好",
            "在吗",
            "嗨",
            "Hello",
            "早上好",
            "谢谢",
            "多谢了",
            "再见",
            "拜拜",
            "今天天气不错",
            "天气怎么样",
            "讲个笑话",
            "你是谁",
            "你是机器人吗",
            "你叫什么名字"
    })
    @DisplayName("问候/感谢/道别/天气/笑话/问身份 → 闲聊")
    void obviousChitChatIsIdle(String message) {
        assertThat(gate.isIdleChat(message)).as(message).isTrue();
    }

    @Test
    @DisplayName("空消息 → 闲聊（与旧行为一致）")
    void blankIsIdle() {
        assertThat(gate.isIdleChat(null)).isTrue();
        assertThat(gate.isIdleChat("")).isTrue();
        assertThat(gate.isIdleChat("   ")).isTrue();
    }

    @Test
    @DisplayName("寒暄词出现在长业务句中段 → 不判闲聊（phatic 长度守卫）")
    void greetingEmbeddedInLongBusinessMessageIsNotIdle() {
        assertThat(gate.isIdleChat("你好你好，我想问一下你们那个新车什么时候能到")).isFalse();
    }
}