package com.iris.lite.java.infrastructure.cache;

import com.iris.lite.java.application.cache.SemanticReindexService;
import com.iris.lite.java.cache.embedder.Embedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地 BM25 词法 Embedder：BM25 词项权重 + 特征哈希 + L2 归一化，
 * 零外部依赖、确定性输出、无进程内状态。
 *
 * <p><b>与 Embedder 接口的关系</b>：BM25 本是稀疏检索打分函数，本实现把
 * BM25 权重装进固定维度特征哈希向量——词项权重
 * {@code tf·(k1+1) / (tf + k1·(1-b+b·dl/avgdl))} 取代 raw tf，之后
 * L2 归一化，余弦相似度即退化为 BM25 风格的词法匹配度。
 * 业务层（余弦/Redis HNSW KNN）零改动。
 *
 * <p><b>idf 项的有意省略</b>：标准 BM25 的 idf 需要语料级 df 统计——在线学习
 * df 会让 embed 变成有状态、输出随时间漂移（早先存入的缓存向量与后来查询向量
 * 权重标准不一致），破坏本兜底「同样输入永远同样输出」的确定性 virtue；
 * 无语料时 idf 无从取值，故固定为 1，仅保留 tf 饱和（k1）与长度归一（b）。
 * 词法区分度损失可接受：本实现只是无模型环境的冒烟/开发兜底。
 *
 * <p><b>能力边界（务必理解）</b>：这是<b>词法级</b>匹配——字面越像则分数越高，
 * 不是真正的语义模型。它足以支撑「上海 ≈ 上海市」这类字面近义值命中，
 * 但「手机」与「电话」这种语义相同、字面无关的 case 命中不了。
 * 相比旧哈希近似（raw tf 计数）的改进：高频词项收益饱和、超长短语不再
 * 单靠出现次数压倒正常文本。
 *
 * <p><b>词项（term）定义</b>：CJK 字符取相邻 bigram（保留局部字序——
 * 「上海」与「海上」单字集合相同而 bigram 不同）；ASCII 字母/数字连续段取
 * 整词；单字符文本用单字符兜底，避免产出零向量。
 *
 * <p><b>指纹机制</b>：{@link #fingerprint()} = modelId + 维度。换模型或改维度
 * 指纹必变——语义缓存索引自动切换到新指纹命名空间；旧命名空间条目经
 * {@code SemanticReindexService} 重嵌入迁移，无需手工清空。
 * 微调 k1/b/avgdl 属同族参数：词表与维度不变，余弦仍可比，指纹不变。
 *
 * <p><b>条件装配</b>：与远程实现 {@code RemoteEmbedder} 互斥——
 * {@code iris.embedder.type=bm25}（或缺省）时启用本实现；
 * {@code =remote} 时启用远程实现。两个 Embedder bean 绝不同时存在，
 * 否则 {@code ObjectProvider.getIfAvailable()} 会因歧义抛异常。
 */
@Component
@ConditionalOnProperty(name = "iris.embedder.type", havingValue = "bm25", matchIfMissing = true)
public class Bm25Embedder implements Embedder {

    private static final Logger log = LoggerFactory.getLogger(Bm25Embedder.class);

    /** BM25 tf 饱和参数（经典值 1.2）：越大越接近 raw tf。 */
    private final double k1;
    /** BM25 长度归一参数（经典值 0.75）：0 = 不做长度归一，1 = 完全按长度比例。 */
    private final double b;
    /** 期望文档长度（词项数）：无语料统计时的长度归一锚点，短于它放大权重、长于它衰减。 */
    private final double avgdl;

    private final int dimension;
    private final String modelId;

    public Bm25Embedder(
            @Value("${iris.embedder.bm25.dimension:256}") int dimension,
            @Value("${iris.embedder.bm25.k1:1.2}") double k1,
            @Value("${iris.embedder.bm25.b:0.75}") double b,
            @Value("${iris.embedder.bm25.avgdl:24}") double avgdl) {
        // 下限保护：维度过低会让不同文本的向量频繁碰撞，相似度失去区分度
        this.dimension = Math.max(16, dimension);
        this.k1 = Math.max(0, k1);
        this.b = Math.min(1, Math.max(0, b));
        this.avgdl = Math.max(1, avgdl);
        this.modelId = "bm25-lexical";
        log.info("Bm25Embedder 初始化 modelId={} dimension={} k1={} b={} avgdl={} fingerprint={}",
                modelId, this.dimension, this.k1, this.b, this.avgdl, fingerprint());
    }

    /**
     * 把文本映射为 L2 归一化的 BM25 权重向量。
     *
     * <p><b>算法</b>：
     * <ol>
     *   <li>归一化输入（trim + 小写），切词项：ASCII 连续段整词、CJK 相邻
     *       bigram、单字符兜底；</li>
     *   <li>统计词项 tf，按 BM25 公式（idf=1，见类注释）计算每个词项权重；</li>
     *   <li>词项特征哈希进固定维度桶（floorMod 哈希累加），L2 归一化，
     *       使余弦相似度退化为点积。</li>
     * </ol>
     *
     * <p>空文本返回零向量：调用方看到零向量应跳过相似度匹配
     * （余弦里做了零向量保护，返回 0）。
     */
    @Override
    public float[] embed(String text) {
        float[] v = new float[dimension];
        if (text == null || text.isBlank()) {
            return v;
        }
        String t = text.trim().toLowerCase();
        Map<String, Integer> tf = new HashMap<>();
        StringBuilder word = new StringBuilder();
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (isAsciiAlnum(c)) {
                word.append(c);
                continue;
            }
            flushWord(word, tf);
            // CJK/其他字符：与下一字符组成 bigram 保留局部字序；句尾单字用单字符兜底
            if (i + 1 < n) {
                tf.merge(t.substring(i, i + 2), 1, Integer::sum);
            } else {
                tf.merge(String.valueOf(c), 1, Integer::sum);
            }
        }
        flushWord(word, tf);
        if (tf.isEmpty()) {
            return v;
        }
        // 文档长度 = 词项总数（含重复），BM25 的 dl
        int dl = 0;
        for (int f : tf.values()) {
            dl += f;
        }
        // BM25 词项权重（idf=1）：tf 饱和 + 相对锚点 avgdl 的长度归一
        double norm = 1 - b + b * dl / avgdl;
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            double weight = e.getValue() * (k1 + 1) / (e.getValue() + k1 * norm);
            v[Math.floorMod(e.getKey().hashCode(), dimension)] += (float) weight;
        }
        float sq = 0f;
        for (float x : v) {
            sq += x * x;
        }
        if (sq > 0f) {
            float inv = 1f / (float) Math.sqrt(sq);
            for (int i = 0; i < dimension; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /** ASCII 词收尾：非空则作为整词入 tf 表（词内字序由整词 token 保证）。 */
    private static void flushWord(StringBuilder word, Map<String, Integer> tf) {
        if (!word.isEmpty()) {
            tf.merge(word.toString(), 1, Integer::sum);
            word.setLength(0);
        }
    }

    /** 向量维度。用于校验缓存条目向量与当前 embedder 是否兼容（维度不同余弦不可比）。 */
    @Override
    public int dimension() {
        return dimension;
    }

    /**
     * 向量空间指纹：modelId + 维度。
     *
     * <p>换模型或换维度必须产生不同指纹——不同向量空间的余弦值不可比，
     * 混在一起会得出毫无意义的相似度。k1/b/avgdl 为同族微调参数，不改指纹
     * （见类注释）。
     */
    @Override
    public String fingerprint() {
        return modelId + "-d" + dimension;
    }
}
