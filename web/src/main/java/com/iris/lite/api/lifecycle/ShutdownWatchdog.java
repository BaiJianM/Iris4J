package com.iris.lite.api.lifecycle;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 停机看门狗：SIGTERM 后若 JVM 优雅关闭在预算时间内没完成，强制退出。
 *
 * <p><b>为什么需要</b>：JVM 的 shutdown hook（Spring context close）里任何一环
 * 卡住，进程就会僵死——例如大量 CDC 阻塞会话能把 SIGTERM 拖成僵尸进程，
 * 连接悬空数小时、新实例起不来（端口占用）还难排查。CdcConsumer 的
 * 强制断会话护栏已消除最大的嫌疑点，但防不住未来的未知环节；
 * 本类是最终保险丝：关闭流程 10 秒没走完，直接 halt。
 *
 * <p><b>安全性</b>：业务写路径全是同步命令（发出即落 Redis），halt 不丢已确认
 * 数据；CDC 未 XACK 的消息留在 PEL，下次启动由 reclaim 任务重投——
 * 强制退出的代价只是"在途批次重做"，可靠性语义不受影响。
 */
@Component
public class ShutdownWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ShutdownWatchdog.class);

    /** 优雅关闭预算：正常关闭（含 135 路会话断连）为秒级，10s 已很宽裕。 */
    private static final long GRACEFUL_SHUTDOWN_BUDGET_MS = 10_000;

    /**
     * 在 shutdown hook 线程内（@PreDestroy 执行期间）武装看门狗：
     * 起一个 daemon 线程倒计时，超时后 halt。halt 从 daemon 线程发起，
     * 不受"非 daemon 线程阻止退出"影响，也无法被再拦截——这是最后的手段。
     */
    @PreDestroy
    public void arm() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(GRACEFUL_SHUTDOWN_BUDGET_MS);
            } catch (InterruptedException e) {
                // 正常关闭早于预算耗尽：恢复中断标记后静默退出（halt 不再触发）
                Thread.currentThread().interrupt();
                return;
            }
            log.warn("JVM 优雅关闭超过 {}ms 未完成，强制 System.exit(0)（ShutdownWatchdog）",
                    GRACEFUL_SHUTDOWN_BUDGET_MS);
            Runtime.getRuntime().halt(0);
        }, "shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
