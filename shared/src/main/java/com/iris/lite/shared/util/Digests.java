package com.iris.lite.shared.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 哈希工具（shared 底层通用能力，无任何业务依赖）。
 *
 * <p>SHA-256 十六进制摘要的唯一出处，各业务模块统一从这里取用。
 * 算法与输出格式一旦散落各模块，就会出现「同一输入不同模块算出不同串」
 * 的静默数据不一致——摘要类逻辑必须集中在单点。
 */
public final class Digests {

    private Digests() {
    }

    /**
     * SHA-256 十六进制摘要（小写，UTF-8 编码输入）。
     *
     * @throws IllegalStateException SHA-256 不可用（JVM 必备算法，理论不可达；
     *                               兜底成运行时异常让问题尽早暴露）
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
