package com.iris.lite.java.api.mcp;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 动态实体工具索引（on-demand 按需发现模式）。
 *
 * <p><b>并发模型</b>：写方是 {@code @Scheduled} 单线程对账（构建全新快照后
 * 原子替换 volatile 引用）；读方是 MCP 请求线程（无锁读当前快照）。
 * 快照不可变——在途调用持旧快照引用，行为等价于"schema 变更前一瞬间"的
 * 定义，无害。
 */
@Component
public class EntityToolIndex {

    private final EntityToolFactory factory;

    private volatile ToolIndexSnapshot snapshot = ToolIndexSnapshot.EMPTY;

    public EntityToolIndex(EntityToolFactory factory) {
        this.factory = factory;
    }

    /** 用给定的工具定义集构建新快照并原子替换（构建时预计算描述/参数/指纹/检索文本）。 */
    public void replace(Map<String, EntityToolFactory.ToolDef> defs) {
        this.snapshot = build(defs);
    }

    public ToolIndexSnapshot current() {
        return snapshot;
    }

    /** 全量构建不可变快照：每个工具预计算描述/参数/指纹与各维度检索文本。 */
    private ToolIndexSnapshot build(Map<String, EntityToolFactory.ToolDef> defs) {
        Map<String, ToolIndexSnapshot.IndexedTool> indexed = new LinkedHashMap<>();
        for (EntityToolFactory.ToolDef def : defs.values()) {
            String description = factory.buildDescription(def);
            Map<String, Object> parameters = factory.inputProperties(def);
            String fingerprint = factory.fingerprint(def);
            // 检索维度：字段名与字段中文描述逐个收集（Schema 字段顺序保持）。
            // 语义化工具只挂目标字段；通用 query 工具挂全部字段（它什么都可查）
            LinkedHashSet<String> fieldNames = new LinkedHashSet<>();
            LinkedHashSet<String> fieldDescs = new LinkedHashSet<>();
            if (def.field() != null) {
                fieldNames.add(def.field().name());
                if (def.field().description() != null) {
                    fieldDescs.add(def.field().description());
                }
            } else {
                def.schema().fields().forEach(f -> {
                    fieldNames.add(f.name());
                    if (f.description() != null) {
                        fieldDescs.add(f.description());
                    }
                });
            }
            indexed.put(def.name(), new ToolIndexSnapshot.IndexedTool(
                    def,
                    description,
                    parameters,
                    fingerprint,
                    def.name().toLowerCase(),
                    def.schema().entity().toLowerCase(),
                    def.schema().namespace().toLowerCase(),
                    String.join("|", fieldNames).toLowerCase(),
                    String.join("|", fieldDescs).toLowerCase(),
                    description.toLowerCase()));
        }
        // 保序不可变：namespace 概览统计、同名排序 tie-break 都依赖稳定迭代顺序
        return new ToolIndexSnapshot(java.util.Collections.unmodifiableMap(indexed));
    }
}
