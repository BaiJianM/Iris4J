package com.iris4j.infrastructure.memory.strategy;

import com.iris4j.memory.strategy.Candidate;
import com.iris4j.memory.strategy.MemoryStrategy;
import com.iris4j.memory.strategy.StrategyContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * discrete 策略：离散事实抽取（缺省策略）。
 *
 * <p>抽两类记忆：<b>episodic</b>（特定时间发生的事件，必带绝对日期 event_date）
 * 与 <b>semantic</b>（用户偏好 + 训练数据外的一般知识）。
 *
 * <p><b>上下文锚定是本策略的灵魂</b>：
 * <ul>
 *   <li>代词全部解析为具体人名；<b>应用用户一律称"用户"</b>——存真实姓名，
 *       用户日后改名记忆就错了；</li>
 *   <li>相对时间锚定为绝对日期（"昨天"→具体日期，基准是注入的当前时间）；</li>
 *   <li>指代解析（"那里"→具体地点、"那个会"→具体会议名）。</li>
 * </ul>
 * 不抽：过程性知识（系统工具负责）、LLM 训练数据已知的常识。
 */
@Component
@ConditionalOnProperty(name = "iris.memory.extractor.enabled", havingValue = "true")
public class DiscreteMemoryStrategy implements MemoryStrategy {

    private static final String SYSTEM_PROMPT = """
            你是长期记忆管理器。你的任务是分析文本，抽取在未来对话中可能用到的信息。
            每条记忆分两类：
            1. episodic（情景记忆）：特定时间点发生的事件；
            2. semantic（语义记忆）：用户偏好，以及你训练数据之外的一般知识。

            上下文锚定要求（必须全部满足）：
            - 把所有代词（他/她/他们/它/这个/那个）替换为具体的名称；
              应用用户本人一律称为"用户"——绝不存储用户的真实姓名来指代用户本人；
            - 相对时间必须换算为绝对日期（依据我提供的当前时间），
              如"昨天"→具体日期、"去年"→具体年份、"下周"→具体日期区间；
            - 指代表达必须解析为具体对象（"那里"→具体地点，"那个会"→具体会议名）。
            绝不留下未锚定的指代。

            重要规则：
            - 不要抽取过程性知识（操作步骤由系统工具负责）；
            - 不要抽取你已经知道的常识；
            - 没有可抽取内容时输出 {"memories":[]}。
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            当前时间：%s

            分析下面的会话文本，抽取值得长期记住的离散事实。
            每条输出形如：
            {"type":"episodic或semantic","text":"一句话第三人称陈述（上下文已锚定）","topics":["主题标签，最多%d个"],"entities":["涉及的人名/事物"],"event_date":"episodic 的 ISO 8601 日期，semantic 填 null"}

            会话文本：
            %s""";

    private final LlmStrategyExecutor executor;

    public DiscreteMemoryStrategy(LlmStrategyExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "discrete";
    }

    @Override
    public List<Candidate> extract(String sessionText, StrategyContext context) {
        return executor.execute(SYSTEM_PROMPT, USER_PROMPT_TEMPLATE.formatted(
                context.currentDateTime(), context.topKTopics(), sessionText));
    }
}
