package com.iris4j.cache.embedder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文本向量化端口（语义缓存/语义检索共用）。
 *
 * <p>实现层负责模型细节（远程 API / 本地 BM25 词法近似），返回 L2 归一化后的向量，
 * 使余弦相似度退化为点积。业务层不感知具体模型与维度。
 */
public interface Embedder {

    /**
     * 把文本映射为 L2 归一化向量；空文本返回零向量（调用方应跳过相似度匹配）。
     */
    float[] embed(String text);

    /** 向量维度，用于校验缓存条目与当前 embedder 是否兼容。 */
    int dimension();

    /**
     * 向量空间指纹（模型标识 + 维度），如 {@code bm25-lexical-d256}。
     *
     * <p><b>换模型或换维度必须产生不同指纹</b>——不同向量空间的余弦值不可比，
     * 混在一起会得出毫无意义的相似度。
     * 语义缓存索引以该指纹做版本隔离（双索引切换）。
     */
    String fingerprint();

    /**
     * 指纹的短哈希（sha256 前 8 位十六进制），用作缓存 key 版本段。
     *
     * <p>用短哈希而非原始指纹：指纹含模型名与维度，直接放进 key 会让 key 又长又难读，
     * 且模型名里的特殊字符可能破坏 key 的分段解析（本项目的缓存 key 按冒号分段）。
     */
    default String fingerprintHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(fingerprint().getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
