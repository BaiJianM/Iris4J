package com.iris4j.shared.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量数学与序列化工具（shared 底层通用能力，无任何业务依赖）。
 *
 * <p>全项目余弦相似度的唯一出处，各业务模块统一从这里取用。
 * 余弦是全项目向量比对的统一标准——这里改了标准，所有比对方同步生效，不会出现
 * 「缓存层与去重层对同一对向量给出不同相似度」的隐性分叉。
 */
public final class Vectors {

    private Vectors() {
    }

    /**
     * 余弦相似度：按通用公式计算（含模长除法），<b>不依赖输入已 L2 归一化</b>——
     * 归一化只是 Embedder 的约定，本方法不依赖该约定，换 Embedder 也不出错。
     *
     * <p>防御约定：任一侧 null、维度不一致、模长为 0（空文本 embed 出零向量）
     * 一律返回 0——零相似度意味着"绝不匹配"，安全方向的默认值。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * 向量 → float32 小端序原始字节（Redis HNSW 索引 {@code vec} 字段的存储格式，
     * 见 RedisKeyPatterns.MEMORY_VECTOR 注释）。查询向量与写入向量必须走同一序列化，
     * 否则 KNN 距离全错。
     */
    public static byte[] toFloat32Bytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer
                .allocate(vector.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        // asFloatBuffer 视图写入，底层字节即 float32 小端序
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }
}
