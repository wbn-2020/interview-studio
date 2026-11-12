package com.wenjie.studio.review;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Objects;

import static com.wenjie.studio.review.StandardReviewDtos.*;
import static com.wenjie.studio.review.StandardReviewRules.*;

@Component
public class StandardReviewProjectionSeal {
    private final StandardReviewProjectionMapper mapper;

    public StandardReviewProjectionSeal(StandardReviewProjectionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sealQuestion(String owner, QuestionVersionRef question, DirectStamp verifiedCurrentQuestion, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("审阅投影校验标记必须加入持有 owner 锁的命令事务");
        }
        uuid(owner); question(question); stamp(verifiedCurrentQuestion); time(now);
        DirectRef source = new DirectRef(DirectKind.QUESTION, question.questionId());
        if (!source.equals(verifiedCurrentQuestion.source())) throw integrity();
        // 调用方必须传同一事务刚完成全文核对的实际指纹，不能用历史记录中的基线代替。
        var row = mapper.question(owner, question.questionId(), question.versionId(), null);
        if (row == null || !owner.equals(row.ownerId()) || !question.questionId().equals(row.id())
                || !question.versionId().equals(row.versionId())
                || !Objects.equals(row.currentVersionId(), verifiedCurrentQuestion.currentVersionId())
                || !Objects.equals(row.status(), verifiedCurrentQuestion.status())) throw integrity();
        hashText(row.sourceContentHash());
        if (row.sealedContentHash() != null || row.fingerprint() != null || row.sealedAt() != null) {
            if (!row.sourceContentHash().equals(row.sealedContentHash())
                    || !verifiedCurrentQuestion.fingerprint().equals(row.fingerprint()) || row.sealedAt() == null) throw integrity();
            time(row.sealedAt());
            return;
        }
        // 每个确切版本仅追加一次；旧记录只在显式保存／查看时建立，普通投影读取不补写。
        var seal = new StandardReviewProjectionRows.QuestionStamp(owner, question.questionId(), question.versionId(),
                row.sourceContentHash(), verifiedCurrentQuestion.fingerprint(), now);
        if (mapper.insertQuestionStamp(seal) != 1) throw integrity();
    }
}
