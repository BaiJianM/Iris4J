package com.iris.lite.java.infrastructure.memory.strategy;

import com.iris.lite.java.memory.strategy.Candidate;
import com.iris.lite.java.memory.strategy.MemoryStrategy;
import com.iris.lite.java.memory.strategy.StrategyContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * preferences 策略：用户偏好抽取。
 *
 * <p>聚焦六类内容：偏好设置、配置项、个人特征、工作习惯、
 * 沟通偏好、技术偏好。
 *
 * <p><b>克制原则</b>：只抽清晰、可执行的偏好；排除临时状态与一次性决定，
 * 聚焦模式化、重复出现的偏好；没有明确偏好就输出空——
 * 宁可少记不错记，把临时性发言积累成"用户偏好"是垃圾记忆的主要来源。
 */
@Component
@ConditionalOnProperty(name = "iris.memory.extractor.enabled", havingValue = "true")
public class PreferencesMemoryStrategy implements MemoryStrategy {

    private static final String SYSTEM_PROMPT = """
            你是用户偏好抽取器。你的任务是从对话中识别并抽取用户偏好、设置、好恶与个人特征。

            聚焦六类内容：
            1. preferences（明确的偏好）
            2. settings and configurations（设置与配置习惯）
            3. personal characteristics and traits（个人特征）
            4. work patterns and habits（工作模式与习惯）
            5. communication preferences（沟通偏好）
            6. technology preferences（技术偏好）

            重要规则：
            1. 只抽取清晰、可执行的偏好；
            2. 不抽取临时状态或一次性决定；
            3. 聚焦模式化、重复出现的偏好；
            4. 没有明确偏好时输出 {"memories":[]}。
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            当前时间：%s

            从下面的会话文本中抽取用户偏好。每条输出形如：
            {"memories":[{"type":"semantic","text":"一句话第三人称偏好陈述","topics":["类别"],"entities":[]}]}

            会话文本：
            %s""";

    private final LlmStrategyExecutor executor;

    public PreferencesMemoryStrategy(LlmStrategyExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "preferences";
    }

    @Override
    public List<Candidate> extract(String sessionText, StrategyContext context) {
        return executor.execute(SYSTEM_PROMPT, USER_PROMPT_TEMPLATE.formatted(
                context.currentDateTime(), sessionText));
    }
}
