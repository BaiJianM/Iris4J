package com.iris4j.shared.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索词项化工具（RAG 词法通道的单一事实源）：把自然语言文本切成「索引词项」序列。
 *
 * <p><b>三处共用</b>：存入侧 {@code lex} 派生字段（Bm25Embedder 的词项权重、
 * FT TEXT 索引的文档内容）、查询侧的词法检索表达式——两侧必须用同一套切词规则，
 * 否则"写进去的词"和"查出来的词"对不上，词法通道整体失效。
 *
 * <p><b>切词规则</b>（语言无关、确定性、零依赖）：
 * <ul>
 *   <li>归一化：trim + 小写；</li>
 *   <li>按「字母/数字连段」切 run（标点/空白都是分隔符，不进词项——
 *       顺带消掉了 FT 查询串的特殊字符转义问题）；</li>
 *   <li>纯 ASCII run → 整词一个词项（英文/数字保词序词形）；</li>
 *   <li>含 CJK 的 run → 相邻字符 bigram（中文无空格分词，bigram 是
 *       无需词典即可用的经典近似，且保留局部字序：「上海」≠「海上」）；</li>
 *   <li>单字符 run → 单字符词项（避免零词项）。</li>
 * </ul>
 *
 * <p><b>为什么不靠 RediSearch 的 chinese 语言分词</b>：依赖 Redis 构建内置
 * Friso 分词器，开源使用方换成其他 Redis 发行版行为可能漂移；bigram 方案
 * 自包含、确定性、任何 Redis 8 都一致。
 */
public final class LexTerms {

    private LexTerms() {
    }

    /**
     * 文本 → 词项序列。null/空白返回空列表。
     *
     * <p>查询侧若只用第一个词项等单边规则会导致召回塌缩；调用方应把
     * 全部词项交给 {@link #orQuery} 构造 OR 检索表达式。
     */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String t = text.trim().toLowerCase();
        int n = t.length();
        int start = -1;
        for (int i = 0; i <= n; i++) {
            boolean wordChar = i < n && isWordChar(t.charAt(i));
            if (wordChar && start < 0) {
                start = i;
            }
            if (!wordChar && start >= 0) {
                emitRun(t.substring(start, i), out);
                start = -1;
            }
        }
        return out;
    }

    /** 词项序列 → 空格连接的派生文本（{@code lex} 字段的存储形态）。 */
    public static String toLexText(String text) {
        return String.join(" ", tokens(text));
    }

    /**
     * 词项序列 → FT 查询的 OR 表达式（{@code tok1|tok2|...}）。
     *
     * <p>OR 而非 AND：词法通道的职责是召回（宁可多召回、不可漏召回），精度由 RRF 排名与
     * rerank兜底——AND 会让「多打一个词」的改写句整条查不到。
     * 词项数截断到 24：超长查询的 OR 表达式对引擎是纯负担，尾部词项
     * 信息量也最低。
     *
     * @return 查询表达式；无可用水词项（空白/纯标点）返回 null，调用方跳过词法通道
     */
    public static String orQuery(String text) {
        List<String> toks = tokens(text);
        if (toks.isEmpty()) {
            return null;
        }
        int capped = Math.min(toks.size(), 24);
        return String.join("|", toks.subList(0, capped));
    }

    /** 词项 run 分发：纯 ASCII 走整词，含 CJK 走 bigram，单字符兜底。 */
    private static void emitRun(String run, List<String> out) {
        boolean hasCjk = false;
        for (int i = 0; i < run.length(); i++) {
            if (run.charAt(i) >= 0x2E80) {
                hasCjk = true;
                break;
            }
        }
        if (!hasCjk || run.length() == 1) {
            out.add(run);
            return;
        }
        for (int i = 0; i < run.length() - 1; i++) {
            out.add(run.substring(i, i + 2));
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
