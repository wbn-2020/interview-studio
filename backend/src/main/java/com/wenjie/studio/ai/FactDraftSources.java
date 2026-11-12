package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.material.MaterialDtos.AiExcerpt;
import com.wenjie.studio.material.MaterialSources;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.ai.AiDtos.*;
import static com.wenjie.studio.ai.FactDraftDtos.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class FactDraftSources {
    public static final String VALIDATION_NOTE = "AI 事实候选仅整理所选原文，不证明经历或责任；采用后仍须人工核实。";
    private static final Set<String> SOURCE_ERRORS = Set.of(
            "AI_TRAINING_SOURCE_NOT_FOUND", "AI_SOURCE_CHANGED", "AI_EVALUATION_ACCESS_REQUIRED");
    private final MaterialSources materials;
    private final TrainingDraftSources training;

    public FactDraftSources(MaterialSources materials, TrainingDraftSources training) {
        this.materials = materials;
        this.training = training;
    }

    public Input input(String owner, Selection selection) {
        if (!uuid(owner) || !validSelection(selection)) {
            throw new ApiException(400, "INVALID_REQUEST", "请选择 1–5 个不同资料摘录，或同一确切提交中 1–8 个合法答案片段。");
        }
        Input result;
        if (selection.kind() == Kind.MATERIAL) {
            result = new Input(Kind.MATERIAL, materials.read(owner, selection.excerptIds()), null);
        } else {
            result = new Input(Kind.TRAINING, List.of(), trainingInput(owner, selection.training()));
        }
        // 输入只接受服务端读到的固定来源；持久内容形状损坏不是普通失效，不能静默授权或吞成 false。
        if (!valid(result)) throw new IllegalStateException("事实整理来源的固定内容结构无效");
        return result;
    }

    private TrainingInput trainingInput(String owner, TrainingSelection selection) {
        // 复用历史提交、原字段及选区校验，明确禁用反馈；题面只在适配器内用于既有完整性核对。
        TrainingDraftInput fixed = training.input(owner, new TrainingDraftPreviewRequest(selection.sessionId(),
                selection.questionId(), selection.answerId(), "", selection.fragments(), null));
        return new TrainingInput(fixed.sessionId(), fixed.questionId(), fixed.answerId(), fixed.answerNumber(),
                fixed.answerCondition(), List.copyOf(fixed.fragments()));
    }

    public boolean current(String owner, Input fixed) {
        if (!uuid(owner) || !valid(fixed)) return false;
        if (fixed.kind() == Kind.MATERIAL) return materials.current(owner, fixed.materials());
        TrainingInput submitted = fixed.training();
        try {
            // 只比较真正授权的窄投影；后来提交、会话代次或未外发题面不替换原答案身份与选区。
            return submitted.equals(trainingInput(owner, new TrainingSelection(submitted.sessionId(),
                    submitted.questionId(), submitted.answerId(), submitted.fragments())));
        } catch (ApiException exception) {
            // 仅来源缺失／失效可返回 false；数据库、JSON 及其他业务错误保留原异常供调用方处理。
            if (SOURCE_ERRORS.contains(exception.code())) return false;
            throw exception;
        }
    }

    public static boolean validSelection(Selection selection) {
        if (selection == null || selection.kind() == null || selection.excerptIds() == null) return false;
        if (selection.kind() == Kind.MATERIAL) {
            return selection.training() == null && !selection.excerptIds().isEmpty() && selection.excerptIds().size() <= 5
                    && selection.excerptIds().stream().allMatch(FactDraftSources::uuid)
                    && new HashSet<>(selection.excerptIds()).size() == selection.excerptIds().size();
        }
        TrainingSelection selected = selection.training();
        return selection.excerptIds().isEmpty() && selected != null && uuid(selected.sessionId())
                && uuid(selected.questionId()) && uuid(selected.answerId()) && validFragments(selected.fragments());
    }

    public static boolean valid(Input input) {
        if (input == null || input.kind() == null || input.materials() == null) return false;
        if (input.kind() == Kind.MATERIAL) return input.training() == null && validMaterials(input.materials());
        TrainingInput submitted = input.training();
        return input.materials().isEmpty() && submitted != null && uuid(submitted.sessionId())
                && uuid(submitted.questionId()) && uuid(submitted.answerId())
                && submitted.answerNumber() >= 1 && submitted.answerNumber() <= 100
                && ("LEARNING".equals(submitted.answerCondition()) || "BEFORE_ASSISTANCE".equals(submitted.answerCondition())
                    || "ASSISTED".equals(submitted.answerCondition())) && validFragments(submitted.fragments());
    }

    private static boolean validMaterials(List<AiExcerpt> excerpts) {
        if (excerpts.isEmpty() || excerpts.size() > 5) return false;
        var ids = new HashSet<String>();
        for (AiExcerpt excerpt : excerpts) {
            if (excerpt == null || !uuid(excerpt.excerptId()) || !ids.add(excerpt.excerptId()) || !uuid(excerpt.attachmentId())
                    || !sourceText(excerpt.fileName(), 200) || excerpt.sha256() == null
                    || !excerpt.sha256().matches("[0-9a-f]{64}") || !sourceText(excerpt.locator(), 500)
                    || !sourceText(excerpt.text(), 10_000)) return false;
            if ("MANUAL".equals(excerpt.origin())) {
                if (excerpt.runId() != null || excerpt.segmentId() != null
                        || excerpt.startOffset() != null || excerpt.endOffset() != null) return false;
            } else if (!"PARSED".equals(excerpt.origin()) || !uuid(excerpt.runId()) || !uuid(excerpt.segmentId())
                    || excerpt.startOffset() == null || excerpt.endOffset() == null || excerpt.startOffset() < 0
                    || excerpt.endOffset() <= excerpt.startOffset()
                    || excerpt.endOffset() - excerpt.startOffset() != excerpt.text().length()) return false;
        }
        return true;
    }

    private static boolean validFragments(List<TrainingDraftFragment> fragments) {
        if (fragments == null || fragments.isEmpty() || fragments.size() > 8) return false;
        for (int index = 0; index < fragments.size(); index++) {
            TrainingDraftFragment fragment = fragments.get(index);
            if (fragment == null || !answerField(fragment.field()) || fragment.start() == null || fragment.end() == null
                    || fragment.start() < 0 || fragment.end() <= fragment.start() || !sourceText(fragment.quote(), 8000)
                    || fragment.end() - fragment.start() != fragment.quote().length()
                    || (fragment.field() == TrainingDraftField.ANSWER_TEXT ? fragment.elementId() != null : !uuid(fragment.elementId()))) return false;
            for (int previous = 0; previous < index; previous++) {
                TrainingDraftFragment other = fragments.get(previous);
                if (fragment.field() == other.field() && Objects.equals(fragment.elementId(), other.elementId())
                        && fragment.start() < other.end() && other.start() < fragment.end()) return false;
            }
        }
        return true;
    }

    public static boolean validContent(Content content) {
        if (content == null || !validText(content.title(), 200, true) || !validText(content.scenario(), 10_000, false)
                || !validText(content.problem(), 10_000, false) || !validText(content.action(), 10_000, false)
                || !validText(content.result(), 10_000, false) || !validText(content.technology(), 2000, false)
                || content.tags() == null || content.tags().size() > 20) return false;
        var tags = new HashSet<String>();
        // 采用沿准备域按 strip 后的标签判重；只核验，不改写模型原文或客户端的幂等请求。
        for (String tag : content.tags()) if (!validText(tag, 40, true) || !tags.add(tag.strip())) return false;
        return true;
    }

    public static boolean validCandidate(Input input, FactDraftDtos.Candidate candidate) {
        if (!valid(input) || candidate == null || !validContent(candidate.content())
                || !validText(candidate.limitations(), 2000, true) || !VALIDATION_NOTE.equals(candidate.validationNote())
                || candidate.materialCitations() == null || candidate.trainingCitations() == null) return false;
        // 两种证据身份互斥；不能以另一列表的合法引文填补当前来源的依据或扩展可引用范围。
        var positions = new HashSet<String>();
        if (input.kind() == Kind.MATERIAL) {
            if (!candidate.trainingCitations().isEmpty() || candidate.materialCitations().isEmpty()
                    || candidate.materialCitations().size() > 12) return false;
            for (MaterialCitation citation : candidate.materialCitations()) {
                if (!validMaterialCitation(input, citation)
                        || !positions.add(citation.excerptId() + ":" + citation.start() + ":" + citation.end())) return false;
            }
        } else {
            if (!candidate.materialCitations().isEmpty() || candidate.trainingCitations().isEmpty()
                    || candidate.trainingCitations().size() > 12) return false;
            for (TrainingDraftCitation citation : candidate.trainingCitations()) {
                if (!validTrainingCitation(input, citation)
                        || !positions.add(citation.field() + ":" + citation.elementId() + ":" + citation.start() + ":" + citation.end())) return false;
            }
        }
        return true;
    }

    public static boolean validMaterialCitation(Input input, MaterialCitation citation) {
        if (!valid(input) || input.kind() != Kind.MATERIAL || citation == null
                || !validText(citation.quote(), 1000, true)) return false;
        for (AiExcerpt excerpt : input.materials()) {
            if (excerpt.excerptId().equals(citation.excerptId())) {
                return matches(excerpt.text(), citation.start(), citation.end(), citation.quote());
            }
        }
        return false;
    }

    public static boolean validTrainingCitation(Input input, TrainingDraftCitation citation) {
        if (!valid(input) || input.kind() != Kind.TRAINING || citation == null || !answerField(citation.field())
                || !validText(citation.quote(), 1000, true)) return false;
        for (TrainingDraftFragment fragment : input.training().fragments()) {
            if (fragment.field() == citation.field() && Objects.equals(fragment.elementId(), citation.elementId())
                    && citation.start() >= fragment.start() && citation.end() <= fragment.end()) {
                // 引用保留原字段绝对坐标，只在已授权 quote 内换算校验，不读取或扩大到完整答案。
                return matches(fragment.quote(), citation.start() - fragment.start(),
                        citation.end() - fragment.start(), citation.quote());
            }
        }
        return false;
    }

    public static boolean validText(String value, int maximum, boolean required) {
        return value != null && value.length() <= maximum && value.indexOf('\0') < 0
                && value.codePoints().noneMatch(point -> point >= 0xD800 && point <= 0xDFFF)
                && (!required || value.codePoints().anyMatch(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point)));
    }

    private static boolean sourceText(String value, int maximum) {
        // 既有摘录与答案选区沿用原 isBlank 边界；本批不把合法历史空格语义改判为存储损坏。
        return validText(value, maximum, false) && !value.isBlank();
    }

    private static boolean matches(String original, int start, int end, String quote) {
        return start >= 0 && end > start && end <= original.length()
                && !splitsSurrogate(original, start) && !splitsSurrogate(original, end)
                && quote.equals(original.substring(start, end));
    }

    private static boolean splitsSurrogate(String value, int index) {
        return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }

    private static boolean answerField(TrainingDraftField field) {
        return field == TrainingDraftField.ANSWER_TEXT || field == TrainingDraftField.ANSWER_NODE_LABEL
                || field == TrainingDraftField.ANSWER_NODE_DESCRIPTION || field == TrainingDraftField.ANSWER_EDGE_LABEL;
    }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
