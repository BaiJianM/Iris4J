package com.iris.lite.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 安全配置模型，前缀 {@code iris.security}（access tags）。
 *
 * <p>YAML 形态：
 * <pre>
 * iris:
 *   security:
 *     api-key: ${IRIS_API_KEY:}        # 旧单 key（兼容），agentId=legacy、全可见
 *     agents:
 *       - agent-id: analyst
 *         key: ${IRIS_AGENT_ANALYST_KEY:}
 *         tags: [general]
 *       - agent-id: hr-agent
 *         key: ${IRIS_AGENT_HR_KEY:}
 *         tags: [general, hr]
 * </pre>
 *
 * <p><b>多把 key 与轮换</b>：agents 是列表，同一 agent 可配多把 key
 * （旧 key 保留 + 新 key 先发），轮换时先加新再删旧，零停机。
 *
 * @param apiKey 旧单 key 兼容配置；空则不启用 legacy 身份
 * @param agents per-agent key 清单；key 为空串的条目忽略
 */
@ConfigurationProperties(prefix = "iris.security")
public record SecurityProperties(String apiKey, List<AgentKeyDef> agents) {

    /** 单个 Agent 的 key 定义。 */
    public record AgentKeyDef(String agentId, String key, List<String> tags) {
        public AgentKeyDef {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** 紧凑构造器：null 归一化。 */
    public SecurityProperties {
        agents = agents == null ? List.of() : agents;
    }
}
