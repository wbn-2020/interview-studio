package com.wenjie.studio.training;

import com.wenjie.studio.bank.QuestionDtos.KnowledgeLink;
import com.wenjie.studio.bank.QuestionDtos.QuestionPrompt;
import com.wenjie.studio.bank.QuestionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static com.wenjie.studio.training.TrainingDtos.*;
import static com.wenjie.studio.training.TrainingInput.uuid;

@Service
public class TrainingKnowledgeReferences {
    private final TrainingMapper mapper;
    private final QuestionMapper bank;
    private final JsonMapper json;

    public TrainingKnowledgeReferences(TrainingMapper mapper, QuestionMapper bank, JsonMapper json) {
        this.mapper = mapper;
        this.bank = bank;
        this.json = json;
    }

    public void question(String owner, String sessionId, String questionId, QuestionSnapshot snapshot) {
        writing(owner, sessionId, questionId);
        if (snapshot == null || snapshot.content() == null || snapshot.title() == null
                || !snapshot.title().equals(snapshot.content().title())
                || snapshot.content().knowledgeLinks() == null || snapshot.content().knowledgeLinks().size() > 20) {
            throw inconsistent();
        }
        var links = snapshot.content().knowledgeLinks();
        var distinct = new HashSet<KnowledgeLink>();
        for (KnowledgeLink link : links) {
            if (link == null) throw inconsistent();
            uuid(link.itemId()); uuid(link.versionId());
            if (!distinct.add(link)) throw inconsistent();
        }
        if (snapshot.sourceQuestionId() == null) {
            if (snapshot.sourceVersionId() != null || snapshot.number() != 0 || !links.isEmpty()) throw inconsistent();
        } else {
            uuid(snapshot.sourceQuestionId()); uuid(snapshot.sourceVersionId());
            var source = bank.version(owner, snapshot.sourceQuestionId(), snapshot.sourceVersionId());
            // 登记题面本身的固定引用，不能用题库绑定表替换已保存但不一致的题面。
            if (source == null || source.versionNumber() != snapshot.number() || !source.title().equals(snapshot.title())
                    || !snapshot.content().equals(json.readValue(source.promptJson(), QuestionPrompt.class))
                    || !links.equals(bank.knowledgeLinks(owner, snapshot.sourceVersionId()))) throw inconsistent();
        }
        for (KnowledgeLink link : links) {
            inserted(mapper.insertQuestionKnowledgeSnapshot(owner, sessionId, questionId, link.itemId(), link.versionId()));
        }
    }

    public void draft(String owner, String sessionId, String questionId, AnswerContent content) {
        writing(owner, sessionId, questionId);
        Set<KnowledgeLink> links = diagramLinks(content);
        mapper.clearDraftKnowledgeReferences(owner, sessionId, questionId);
        for (KnowledgeLink link : links) {
            inserted(mapper.insertDraftKnowledgeReference(owner, sessionId, questionId, link.itemId(), link.versionId()));
        }
    }

    public void answer(String owner, String sessionId, String questionId, String answerId, AnswerContent content) {
        writing(owner, sessionId, questionId); uuid(answerId);
        for (KnowledgeLink link : diagramLinks(content)) {
            inserted(mapper.insertAnswerKnowledgeReference(owner, sessionId, questionId, answerId, link.itemId(), link.versionId()));
        }
    }

    public void clearDraft(String owner, String sessionId, String questionId) {
        writing(owner, sessionId, questionId);
        mapper.clearDraftKnowledgeReferences(owner, sessionId, questionId);
    }

    private Set<KnowledgeLink> diagramLinks(AnswerContent content) {
        if (content == null || content.diagram() == null || content.diagram().nodes() == null
                || content.diagram().nodes().size() > 80) throw inconsistent();
        var links = new LinkedHashSet<KnowledgeLink>();
        var nodeIds = new HashSet<String>();
        for (DiagramNode node : content.diagram().nodes()) {
            if (node == null) throw inconsistent();
            uuid(node.id());
            if (!nodeIds.add(node.id())) throw inconsistent();
            DiagramReference reference = node.reference();
            if (reference == null) continue;
            if (reference.kind() == null || reference.title() == null || reference.number() < 1) throw inconsistent();
            uuid(reference.itemId()); uuid(reference.versionId());
            // 多个节点可引用同一确切版本；同一知识的不同版本仍须分别保留。
            if (reference.kind() == LinkKind.KNOWLEDGE) {
                links.add(new KnowledgeLink(reference.itemId(), reference.versionId()));
            }
        }
        return links;
    }

    private void writing(String owner, String sessionId, String questionId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("训练知识引用登记必须处于 owner 锁写事务");
        }
        uuid(owner); uuid(sessionId); uuid(questionId);
    }

    private void inserted(int count) {
        if (count != 1) throw inconsistent();
    }

    private IllegalStateException inconsistent() {
        return new IllegalStateException("训练知识引用与固定内容不一致");
    }
}
