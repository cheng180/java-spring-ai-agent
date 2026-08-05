package org.example.ai.impl.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NeedSignalDetector 单元测试（#39 ticket；#41 程度打分复用同一检测器）。
 * 四类偏好信号：budget 预算 / use 用途 / carType 车型 / energy 能源。
 */
class NeedSignalDetectorTest {

    private final NeedSignalDetector detector = new NeedSignalDetector();

    @Test
    @DisplayName("预算信号：数字+万 / 预算 / 以内 / 左右")
    void budgetSignals() {
        Map<String, String> signals = detector.detect("20万以内的SUV");
        assertThat(signals).containsKey("budget");
        assertThat(signals.get("budget")).contains("20万以内");

        assertThat(detector.detect("预算15万左右").get("budget")).contains("15万左右");
        assertThat(detector.detect("十万以下的有吗").get("budget")).contains("十万以下");
    }

    @Test
    @DisplayName("用途信号：家用 / 通勤 / 代步等")
    void useSignals() {
        assertThat(detector.detect("主要家用").get("use")).isEqualTo("家用");
        assertThat(detector.detect("上下班通勤用").get("use")).isEqualTo("通勤");
        assertThat(detector.detect("平时代步").get("use")).isEqualTo("代步");
    }

    @Test
    @DisplayName("车型信号：SUV / 轿车 / MPV / 七座（大小写不敏感）")
    void carTypeSignals() {
        assertThat(detector.detect("想看suv").get("carType")).isEqualTo("SUV");
        assertThat(detector.detect("有轿车吗").get("carType")).isEqualTo("轿车");
        assertThat(detector.detect("要七座的").get("carType")).isEqualTo("七座");
    }

    @Test
    @DisplayName("能源信号：混动 / 纯电 / 燃油 / 新能源 / 油车电车")
    void energySignals() {
        assertThat(detector.detect("混动的有吗").get("energy")).isEqualTo("混动");
        assertThat(detector.detect("想买纯电").get("energy")).isEqualTo("纯电");
        assertThat(detector.detect("还是油车踏实").get("energy")).isEqualTo("油车");
    }

    @Test
    @DisplayName("多信号并存：一句话同时命中多类")
    void multipleSignals() {
        Map<String, String> signals = detector.detect("20万左右的混动SUV，主要家用");
        assertThat(signals).containsKeys("budget", "energy", "carType", "use");
    }

    @Test
    @DisplayName("无信号 → 空 map")
    void noSignals() {
        assertThat(detector.detect("推荐一款车")).isEmpty();
        assertThat(detector.detect("你好")).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }
}