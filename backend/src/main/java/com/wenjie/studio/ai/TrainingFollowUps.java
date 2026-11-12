package com.wenjie.studio.ai;

import com.wenjie.studio.bank.QuestionDtos;
import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.prepare.PreparationDtos;
import com.wenjie.studio.training.TrainingDtos;
import com.wenjie.studio.training.TrainingMapper;
import com.wenjie.studio.training.TrainingKnowledgeReferences;
import com.wenjie.studio.training.TrainingRows;
import com.wenjie.studio.training.TrainingSources;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.wenjie.studio.ai.AiDtos.*;

@Service
public class TrainingFollowUps {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private final TrainingMapper training;
    private final TrainingSources sources;
    private final TrainingKnowledgeReferences knowledgeReferences;
    private final AiMapper ai;
    private final JsonMapper json;

    public TrainingFollowUps(TrainingMapper training, TrainingSources sources, AiMapper ai, JsonMapper json,
                            TrainingKnowledgeReferences knowledgeReferences) {
        this.training = training;
        this.sources = sources;
        this.knowledgeReferences = knowledgeReferences;
        this.ai = ai;
        this.json = json;
    }

    public TrainingRows.SessionRow session(String owner, String id) {
        uuid(owner); uuid(id);
        var row = training.session(owner, id);
        if (row == null) throw missing();
        return row;
    }

    public record CheckedInput(FollowUpInput input, List<TrainingDtos.ContextCheck> contextChecks) {}

    public CheckedInput input(String owner, String sessionId, String questionId, String answerId) {
        var session = session(owner, sessionId);
        uuid(questionId); uuid(answerId);
        if (!"RUNNING".equals(session.status())) throw changed();
        var question = training.question(owner, sessionId, questionId);
        var answer = training.answer(owner, sessionId, questionId, answerId);
        if (question == null || answer == null) throw missing();
        if (!"MAIN".equals(question.kind()) && !"FOLLOW_UP".equals(question.kind())) throw changed();
        String rootId = "MAIN".equals(question.kind()) ? question.id() : question.rootQuestionId();
        var root = training.question(owner, sessionId, rootId);
        if (root == null || !"MAIN".equals(root.kind())) throw changed();
        var snapshot = json.readValue(question.snapshotJson(), TrainingDtos.QuestionSnapshot.class).content();
        var submitted = json.readValue(answer.contentJson(), TrainingDtos.AnswerContent.class);
        var nodes = submitted.diagram().nodes().stream()
                .map(node -> new FollowUpNode(node.id(), node.label(), node.description())).toList();
        var edges = submitted.diagram().edges().stream()
                .map(edge -> new FollowUpEdge(edge.id(), edge.source(), edge.target(), edge.label())).toList();
        var context = new ArrayList<FollowUpContext>();
        var snapshots = Arrays.asList(json.readValue(session.contextJson(), TrainingDtos.ContextSnapshot[].class));
        var checks = sources.contextChecks(owner, snapshots);
        // 只带入仍可使用的原快照；排除原因留在本地清单，不能展开或替换为最新正文。
        for (int index = 0; index < snapshots.size(); index++) {
            if (checks.get(index).state() != PreparationDtos.SourceState.CURRENT) continue;
            var snapshotContext = snapshots.get(index);
            context.add(new FollowUpContext(snapshotContext.kind().name(), snapshotContext.itemId(), snapshotContext.versionId(),
                    snapshotContext.number(), snapshotContext.title(), projectContext(snapshotContext)));
        }
        var input = new FollowUpInput(sessionId, questionId, answerId, rootId, session.generation(), answer.versionNumber(),
                answer.condition(), new FollowUpQuestion(snapshot.title(), snapshot.prompt(), snapshot.conditions(),
                snapshot.technology(), snapshot.scenario(), snapshot.type()),
                new FollowUpAnswer(submitted.text(), nodes, edges), List.copyOf(context));
        return new CheckedInput(input, checks);
    }

    private String projectContext(TrainingDtos.ContextSnapshot source) {
        JsonNode content = source.content();
        var projected = json.createObjectNode();
        List<String> fields = switch (source.kind()) {
            case TARGET -> List.of("company", "jd");
            case PROJECT -> List.of("overview", "technology");
            case FACT -> List.of("scenario", "problem", "action", "result", "technology", "responsibility");
        };
        for (String field : fields) projected.set(field, content.path(field).deepCopy());
        if (source.kind() == TrainingDtos.ContextKind.TARGET) {
            var requirements = projected.putArray("requirements");
            for (JsonNode requirement : content.path("requirements")) {
                var item = requirements.addObject();
                for (String field : List.of("text", "dimensions", "priority")) item.set(field, requirement.path(field).deepCopy());
            }
        }
        // 只投影开始时已选正文；来源地址、组织关系和图引用不触发额外读取或外发。
        return json.writeValueAsString(projected);
    }

    public boolean current(String owner, FollowUpInput input, List<TrainingDtos.ContextCheck> checks) {
        // 缺失检查属于旧授权；撤回后恢复同一正文也不能沿用此前的来源代次。
        if (input == null || checks == null) return false;
        try {
            var current = input(owner, input.sessionId(), input.questionId(), input.answerId());
            return input.equals(current.input()) && checks.equals(current.contextChecks());
        }
        catch (ApiException exception) { return false; }
    }

    public boolean lifecycleCurrent(String owner, FollowUpInput input) {
        if (input == null) return false;
        try {
            var session = session(owner, input.sessionId());
            if (!"RUNNING".equals(session.status()) || session.generation() != input.generation()) return false;
            var question = training.question(owner, session.id(), input.questionId());
            var answer = training.answer(owner, session.id(), input.questionId(), input.answerId());
            if (question == null || answer == null) return false;
            if (!"MAIN".equals(question.kind()) && !"FOLLOW_UP".equals(question.kind())) return false;
            String rootId = "MAIN".equals(question.kind()) ? question.id() : question.rootQuestionId();
            var root = training.question(owner, session.id(), rootId);
            return root != null && "MAIN".equals(root.kind()) && root.id().equals(input.rootQuestionId())
                    && answer.versionNumber() == input.answerNumber() && answer.condition().equals(input.answerCondition());
        } catch (ApiException exception) { return false; }
    }

    public String availability(String owner, FollowUpInput input, String excludedTaskId) {
        // 生命周期与额度单独判定，来源失效由清单核对归为 AI_SOURCE_CHANGED。
        if (!lifecycleCurrent(owner, input)) return "AI_FOLLOW_UP_STATE_CHANGED";
        var session = session(owner, input.sessionId());
        var config = json.readValue(session.configJson(), TrainingDtos.SessionConfig.class);
        long count = training.questions(owner, session.id()).stream()
                .filter(question -> input.rootQuestionId().equals(question.rootQuestionId())).count();
        if (count >= config.followUpLimit() || ai.pendingFollowUps(owner, session.id(), input.rootQuestionId(), excludedTaskId) > 0) {
            return "AI_FOLLOW_UP_LIMIT";
        }
        if (session.questionCount() >= 60 || session.eventCount() >= 1999 || session.revision() >= MAX_SAFE) {
            return "AI_FOLLOW_UP_LIMIT";
        }
        return null;
    }

    public void requireAvailable(String owner, FollowUpInput input, String excludedTaskId) {
        String error = availability(owner, input, excludedTaskId);
        if (error != null) throw new ApiException(409, error,
                "本场训练已变化、根主问追问次数已满或仍有未决任务，请重新读取训练与任务。");
    }

    public String append(String owner, AiRows.FollowUpRow binding, FollowUpInput input,
                         FollowUpCandidate candidate, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("追问接纳必须处于 owner 锁写事务");
        }
        requireAvailable(owner, input, binding.taskId());
        if (binding.generatedQuestionId() != null) throw changed();
        var session = session(owner, input.sessionId());
        var parent = training.question(owner, session.id(), input.questionId());
        var previous = json.readValue(parent.snapshotJson(), TrainingDtos.QuestionSnapshot.class);
        var original = previous.content();
        String id = UUID.randomUUID().toString();
        var prompt = new QuestionDtos.QuestionPrompt(candidate.title(), candidate.prompt(), candidate.conditions(),
                original.technology(), original.scenario(), original.type(), original.difficulty(), original.dimensions(),
                List.of(), List.of(), List.of(), new QuestionDtos.Source("", "", ""));
        var snapshot = new TrainingDtos.QuestionSnapshot(null, null, candidate.title(), 0, parent.stage(), prompt,
                List.of("AI 追问；结构与原答引用位置已核对，题意与技术质量仍需人工判断。"));
        int position = training.questions(owner, session.id()).stream().mapToInt(TrainingRows.QuestionRow::positionIndex).max().orElse(-1) + 1;
        training.insertQuestion(new TrainingRows.QuestionRow(id, owner, session.id(), null, null, parent.stage(),
                candidate.title(), "PENDING", 0, json.writeValueAsString(snapshot), null, null, null, 0,
                json.writeValueAsString(new TrainingDtos.PriorExposure(0, 0, null, 0L)), position, "FOLLOW_UP",
                input.rootQuestionId(), input.questionId(), input.answerId(), binding.taskId()));
        knowledgeReferences.question(owner, session.id(), id, snapshot);
        changedRow(training.incrementQuestionCount(owner, session.id(), session.revision(), now));
        training.insertEvent(owner, session.id(), new TrainingDtos.Event(UUID.randomUUID().toString(), session.eventCount() + 1,
                TrainingDtos.EventKind.FOLLOW_UP_ADDED, id, "已根据父题确切提交加入一道追问。", null, now));
        changedRow(training.incrementEventCount(owner, session.id(), session.eventCount()));
        changedRow(ai.setGeneratedQuestion(owner, binding.taskId(), id));
        return id;
    }

    private static void changedRow(int count) {
        if (count != 1) throw new IllegalStateException("追问事务中的受锁记录发生变化");
    }
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException exception) { throw new ApiException(400, "INVALID_REQUEST", "对象标识必须为标准小写 UUID。"); }
    }
    private static ApiException missing() { return new ApiException(404, "AI_FOLLOW_UP_NOT_FOUND", "训练、父题或确切提交不存在或不可访问。"); }
    private static ApiException changed() { return new ApiException(409, "AI_FOLLOW_UP_STATE_CHANGED", "追问所绑定的训练条件已变化，请重新预览授权。"); }
}
