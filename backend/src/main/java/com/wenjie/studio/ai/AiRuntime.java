package com.wenjie.studio.ai;

import com.wenjie.studio.security.OwnerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(AiRuntime.class);
    private final AiService service;
    private final ModelGateway gateway;
    private final AiDraftBuffer drafts;
    private final String owner;

    public AiRuntime(AiService service, ModelGateway gateway, OwnerProperties owner, AiDraftBuffer drafts) {
        this.service = service;
        this.gateway = gateway;
        this.drafts = drafts;
        this.owner = owner.ownerId();
    }

    @Scheduled(scheduler = "aiScheduler", fixedDelay = 3000, initialDelay = 1000)
    public synchronized void tick() {
        AiService.Claim claim;
        try {
            // 即使关闭外发，也恢复旧进程的未决记录；claim 内部在恢复后检查启用状态。
            claim = service.claim(owner);
            if (claim == null || !service.maySend(claim)) return;
        } catch (RuntimeException exception) {
            LOG.warn("event=ai_claim_unavailable exceptionType={}", exception.getClass().getSimpleName());
            return;
        }
        try {
            ModelGateway.Result result;
            try {
                if (Boolean.TRUE.equals(claim.manifest().configuration().streaming())) {
                    // 缓冲仅属于这次执行；浏览器订阅不会持有网络调用，也不能取消或重发供应商请求。
                    drafts.begin(claim);
                    result = gateway.generate(claim.manifest(), text -> drafts.append(claim, text));
                } else result = gateway.generate(claim.manifest());
            } catch (RuntimeException exception) {
                // 不能由异常推断请求未到达第三方；结束本次执行，保留费用未知且不自动重发。
                result = null;
            }
            try { service.finish(claim, result); }
            catch (RuntimeException exception) {
                LOG.warn("event=ai_result_uncommitted exceptionType={}", exception.getClass().getSimpleName());
            }
        } finally {
            // 成功、失败和落账异常都清除暂态正文；最终结果只从持久任务读取。
            drafts.clear(claim);
        }
    }
}
