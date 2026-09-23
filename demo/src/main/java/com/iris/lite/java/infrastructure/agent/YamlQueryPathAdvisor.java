package com.iris.lite.java.infrastructure.agent;

import com.iris.lite.java.application.agent.QueryPathAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 查询路径配方的 YAML 加载实现。
 *
 * <p><b>存放位置</b>：{@code iris.agent.graph.dir}（默认 deploy/graph/），与实体
 * schema 目录**物理分离**——schema 外部目录只认一层平铺的 {ns}.{entity}.yml，
 * graph 文件混进去会被当实体 Schema 解析报错。
 *
 * <p><b>热加载</b>：按文件 mtime 惰性刷新（每次 {@link #recipesBlock} 调用时
 * stat 一轮目录，文件数个位数，代价可忽略）——改完 yml 即生效，无需重启。
 *
 * <p><b>容错</b>：单文件解析失败降级为「该文件视为不存在」并告警，不影响其余
 * 文件与 Agent 主链路；目录不存在 = 全部 namespace 返回空串。
 */
@Service
public class YamlQueryPathAdvisor implements QueryPathAdvisor {

    private static final Logger log = LoggerFactory.getLogger(YamlQueryPathAdvisor.class);

    private final Path graphDir;

    /** 文件名 → mtime 快照；变了才重新解析该文件。 */
    private final Map<String, Long> mtimes = new HashMap<>();
    /** 文件名 → 已解析条目（namespace + recipes）。 */
    private final Map<String, LoadedGraph> loaded = new HashMap<>();

    /** 单条配方（yml 字段拍平；path 是步骤文本列表，渲染时逐行编号）。 */
    record Recipe(String intent, List<String> match, String anchor,
                  List<String> path, String tools, String hint) {
    }

    record LoadedGraph(String namespace, List<Recipe> recipes) {
    }

    public YamlQueryPathAdvisor(@Value("${iris.agent.graph-dir:}") String graphDir) {
        this.graphDir = graphDir == null || graphDir.isBlank() ? null : Path.of(graphDir);
    }

    /** 启动期诊断：把解析出的目录与就绪状态打出来（配方是惰性加载，运行期第一条日志在首问时）。 */
    @jakarta.annotation.PostConstruct
    void diagnose() {
        log.info("查询路径规划初始化 dir={} 目录存在={}", graphDir,
                graphDir != null && Files.isDirectory(graphDir));
    }

    @Override
    public String recipesBlock(String namespace) {
        if (graphDir == null || namespace == null || namespace.isBlank()) {
            return "";
        }
        List<Recipe> recipes = recipesFor(namespace);
        if (recipes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "【推荐查询路径（接入期预生成；命中意图时按路径直接执行，禁止再 search_entity_tools 探索或逐页拉取）】\n");
        int i = 1;
        for (Recipe r : recipes) {
            sb.append(i++).append(". 意图: ").append(r.intent()).append('\n');
            sb.append("   路径: ").append(r.anchor()).append('\n');
            int j = 1;
            for (String step : r.path()) {
                sb.append("   ").append(j++).append(") ").append(step).append('\n');
            }
            if (r.hint() != null && !r.hint().isBlank()) {
                sb.append("   提示: ").append(r.hint()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 取指定 namespace 的配方（带 mtime 惰性刷新；同步即可，调用频率=每问一次）。 */
    private synchronized List<Recipe> recipesFor(String namespace) {
        if (!Files.isDirectory(graphDir)) {
            return List.of();
        }
        List<Recipe> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(graphDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        try {
                            long mtime = Files.getLastModifiedTime(p).toMillis();
                            Long known = mtimes.get(name);
                            if (known == null || known != mtime) {
                                loaded.put(name, parse(p));
                                mtimes.put(name, mtime);
                                log.info("查询路径配方已加载 file={} ns={}", name, loaded.get(name).namespace());
                            }
                            LoadedGraph g = loaded.get(name);
                            if (g != null && namespace.equals(g.namespace())) {
                                result.addAll(g.recipes());
                            }
                        } catch (Exception e) {
                            // 单文件失败降级为不存在，不拖垮 Agent 链路
                            log.warn("查询路径配方解析失败，忽略 file={} err={}", name, e.getMessage(), e);
                        }
                    });
        } catch (IOException e) {
            log.warn("查询路径配方目录不可读 dir={} err={}", graphDir, e.getMessage(), e);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private LoadedGraph parse(Path p) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(Files.newBufferedReader(p));
        String ns = String.valueOf(root.getOrDefault("namespace", ""));
        Object recipesObj = root.get("recipes");
        List<Recipe> recipes = new ArrayList<>();
        if (recipesObj instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) o;
                recipes.add(new Recipe(
                        str(m.get("intent")),
                        strList(m.get("match")),
                        str(m.get("anchor")),
                        strList(m.get("path")),
                        str(m.get("tools")),
                        str(m.get("hint"))));
            }
        }
        return new LoadedGraph(ns, List.copyOf(recipes));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (e != null) {
                out.add(String.valueOf(e));
            }
        }
        return List.copyOf(out);
    }
}
