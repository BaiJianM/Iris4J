package com.iris.lite.application.schema;

import com.iris.lite.context.schema.EntitySchema;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Schema 文件回盘端口（关系映射编辑）。
 *
 * <p><b>为什么是端口</b>：application 层不碰文件系统细节（与 Redis 命令同理的模块边界），
 * 实现在 infrastructure（{@code YamlSchemaWriter}，SnakeYAML 序列化 + 原子写）。
 * 编排服务只声明"把这份 Schema 写回外部目录"，写哪里、怎么写由实现方决定。
 *
 * <p><b>回盘语义</b>：外部目录按 namespace/entity 覆盖 classpath 基线，
 * 回盘文件必须落在 {@code iris.schema.dir} 指向的目录且能被热载轮询扫到
 * （平铺一层 {@code .yml}）。写失败抛 {@link IOException}，由编排层转统一错误码。
 */
public interface SchemaFileWriter {

    /**
     * 把 Schema 序列化写入外部目录。
     *
     * @param schema 待写盘的完整 Schema（业务校验由调用方完成）
     * @param dir    外部 Schema 目录（{@code iris.schema.dir}）
     * @return 写入的目标文件路径
     * @throws IOException 目录创建或文件写入失败
     */
    Path write(EntitySchema schema, String dir) throws IOException;
}
