package com.iris.lite.infrastructure.schema;

import com.iris.lite.application.schema.SchemaFileWriter;
import com.iris.lite.context.schema.EntitySchema;
import com.iris.lite.context.schema.FieldSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema YAML 回盘：把内存中的 {@link EntitySchema} 序列化为 YAML 文件，
 * 供控制台编辑关系映射后写回外部目录热载。
 *
 * <p><b>文件布局</b>：{@code {iris.schema.dir}/{namespace}.{entity}.yml}（平铺）——
 * {@code YamlSchemaProvider.listYamlFiles} 只扫一层不递归，必须与它的扫描规则一致，
 * 文件名里的点分隔同时保证多个 namespace 同名实体不互相覆盖。
 *
 * <p><b>覆盖语义</b>：外部目录按 namespace/entity 覆盖 classpath 基线。
 * 首次编辑来自 classpath 的实体时，这里生成的副本即成为生效来源——
 * 原 YAML 的注释不会保留（机器管理文件，README 已说明）。
 *
 * <p><b>原子性</b>：先写临时文件再 {@code ATOMIC_MOVE}，避免热载轮询读到半截文件。
 * 序列化本身失败不会产生任何残留（临时文件在 try 内清理）。
 */
@Component
public class YamlSchemaWriter implements SchemaFileWriter {

    private static final Logger log = LoggerFactory.getLogger(YamlSchemaWriter.class);

    /**
     * 把 Schema 写入外部目录，返回目标文件路径。
     *
     * @param schema 待写盘的完整 Schema（调用方负责业务校验）
     * @param dir    外部 Schema 目录（{@code iris.schema.dir}）
     * @throws IOException 目录创建或文件写入失败
     */
    @Override
    public synchronized Path write(EntitySchema schema, String dir) throws IOException {
        if (dir == null || dir.isBlank()) {
            throw new IOException("未配置 iris.schema.dir，无法回盘 Schema");
        }
        Path dirPath = Path.of(dir);
        Files.createDirectories(dirPath);
        Path target = dirPath.resolve(schema.namespace() + "." + schema.entity() + ".yml");
        Path tmp = dirPath.resolve(target.getFileName() + ".tmp");

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        String yaml = new Yaml(options).dump(toYamlMap(schema));

        try {
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 部分文件系统不支持跨句柄原子 move，退化为普通替换（仍是先写后改）
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        log.info("Schema 已回盘: {}（实体 {}/{}）", target, schema.namespace(), schema.entity());
        return target;
    }

    /**
     * Schema → 有序 Map（LinkedHashMap 保证 dump 顺序与声明顺序一致）。
     * 空值键一律省略，与 classpath 基线 YAML 的手写风格对齐。
     */
    private Map<String, Object> toYamlMap(EntitySchema schema) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("namespace", schema.namespace());
        root.put("entity", schema.entity());
        root.put("primaryKeys", schema.primaryKeys());
        if (schema.tenantField() != null) {
            root.put("tenantField", schema.tenantField());
        }
        if (schema.accessTagField() != null) {
            root.put("accessTagField", schema.accessTagField());
        }
        if (schema.description() != null) {
            root.put("description", schema.description());
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSchema f : schema.fields()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name());
            fm.put("type", f.type().name());
            if (f.indexed()) {
                fm.put("indexed", true);
            }
            if (!f.tags().isEmpty()) {
                fm.put("tags", f.tags());
            }
            if (f.index() != null) {
                fm.put("index", f.index());
            }
            if (f.description() != null) {
                fm.put("description", f.description());
            }
            if (f.relatedEntity() != null) {
                fm.put("relatedEntity", f.relatedEntity());
            }
            if (f.values() != null) {
                fm.put("values", f.values());
            }
            if (f.carriedFrom() != null) {
                fm.put("carriedFrom", f.carriedFrom());
            }
            fields.add(fm);
        }
        root.put("fields", fields);
        return root;
    }
}
