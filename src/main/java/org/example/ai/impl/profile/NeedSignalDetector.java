package org.example.ai.impl.profile;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求信号检测器（#39 ticket 画像偏好；#41 程度打分复用）。
 *
 * <p>纯逻辑关键词/正则检测，四类信号：budget 预算 / use 用途 / carType 车型 / energy 能源。
 * 值为命中的原始表述（预算取数字区间），供画像 JSON 与引导判断共用。</p>
 */
@Component
public class NeedSignalDetector {

    public static final String BUDGET = "budget";
    public static final String USE = "use";
    public static final String CAR_TYPE = "carType";
    public static final String ENERGY = "energy";

    /** 预算区间：阿拉伯/中文数字 + 万 + 可选范围后缀（"20万以内""十五万左右"） */
    private static final Pattern BUDGET_SPAN = Pattern.compile(
            "([0-9一二三四五六七八九十百]+(?:\\.[0-9]+)?)\\s*万\\s*(以内|左右|以下|上下|到\\s*[0-9一二三四五六七八九十百]+\\s*万)?");

    /** 用途词表（长词在前防被短词截胡） */
    private static final String[] USE_WORDS = {
            "接送孩子", "接孩子", "自驾游", "家用为主", "家用", "通勤", "代步", "越野",
            "长途", "短途", "买菜", "上班", "跑业务", "网约车"
    };

    /** 车型词表：canonical 名 + 别名（大小写归一后匹配） */
    private static final String[][] CAR_TYPE_WORDS = {
            {"SUV", "suv"},
            {"MPV", "mpv"},
            {"轿车", "轿车", "三厢", "两厢"},
            {"七座", "七座", "7座"},
            {"跑车", "跑车"},
            {"皮卡", "皮卡"},
            {"面包车", "面包车", "面包"}
    };

    /** 能源词表：长词在前（"插电混动"先于"混动"） */
    private static final String[] ENERGY_WORDS = {
            "插电混动", "插混", "增程", "混动", "纯电", "电动", "新能源",
            "燃油", "汽油", "柴油", "油车", "电车", "省油"
    };

    /**
     * 检测消息中的需求信号。
     *
     * @return 信号类型 → 命中表述；无信号返回空 map
     */
    public Map<String, String> detect(String message) {
        Map<String, String> signals = new LinkedHashMap<>();
        if (message == null || message.isBlank()) return signals;

        Matcher m = BUDGET_SPAN.matcher(message);
        if (m.find()) {
            signals.put(BUDGET, m.group());
        } else if (message.contains("预算")) {
            signals.put(BUDGET, "预算");
        }

        for (String w : USE_WORDS) {
            if (message.contains(w)) {
                signals.put(USE, w);
                break;
            }
        }

        String lower = message.toLowerCase();
        for (String[] group : CAR_TYPE_WORDS) {
            boolean hit = false;
            for (String alias : group) {
                if (lower.contains(alias.toLowerCase())) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                signals.put(CAR_TYPE, group[0]);
                break;
            }
        }

        for (String w : ENERGY_WORDS) {
            if (message.contains(w)) {
                signals.put(ENERGY, w);
                break;
            }
        }

        return signals;
    }

    /** 信号类型全集（供画像 JSON 键校验等场景） */
    public static Set<String> types() {
        return new LinkedHashSet<>(Set.of(BUDGET, USE, CAR_TYPE, ENERGY));
    }
}