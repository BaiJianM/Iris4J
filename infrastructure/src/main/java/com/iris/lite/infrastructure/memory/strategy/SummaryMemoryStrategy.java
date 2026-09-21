package com.iris.lite.infrastructure.memory.strategy;

import com.iris.lite.memory.strategy.Candidate;
import com.iris.lite.memory.strategy.MemoryStrategy;
import com.iris.lite.memory.strategy.StrategyContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * summary 策略：会话摘要抽取。
 *
 * <p>把一段会话压缩为一条摘要记忆——捕获讨论主题、关键决策、
 * 透露的偏好与重要上下文；type 恒为 semantic（摘要无事件时刻语义）。
 * 上限 500 词。
 */
@Component
@ConditionalOnProperty(name = "iris.memory.extractor.enabled", havingValue = "true")
public class SummaryMemoryStrategy implements MemoryStrategy {

    private static final String SYSTEM_PROMPT = """
            你是会话摘要器。你的任务是为对话创建一份简洁摘要，捕获要点、决策与重要上下文。

            摘要要求：
            1. 捕获讨论的主要主题；
            2. 记录做出的关键决策；
            3. 记录透露的用户偏好或重要信息；
            4. 保留必要的上下文。
            摘要长度不超过 500 词。

            上下文锚定要求：
            - 把所有代词替换为具体名称，应用用户本人一律称为"用户"；
            - 相对时间换算为绝对日期（依据我提供的当前时间）。
            没有值得摘要的内容时输出 {"memories":[]}。
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            当前时间：%s

            为下面的会话文本生成一条摘要记忆。输出形如：
            {"memories":[{"type":"semantic","text":"摘要正文","topics":["主题标签"],"entities":["涉及实体"]}]}

            会话文本：
            %s""";

    private final LlmStrategyExecutor executor;

    public SummaryMemoryStrategy(LlmStrategyExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "summary";
    }

    @Override
    public List<Candidate> extract(String sessionText, StrategyContext context) {
        return executor.execute(SYSTEM_PROMPT, USER_PROMPT_TEMPLATE.formatted(
                context.currentDateTime(), sessionText));
    }
}
