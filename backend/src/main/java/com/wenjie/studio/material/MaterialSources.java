package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.material.MaterialDtos.AiExcerpt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class MaterialSources {
    private final MaterialMapper mapper;
    private final MaterialProperties properties;
    private final MaterialLifecycleService lifecycle;

    public MaterialSources(MaterialMapper mapper, MaterialProperties properties, MaterialLifecycleService lifecycle) {
        this.mapper = mapper;
        this.properties = properties;
        this.lifecycle = lifecycle;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
    public List<AiExcerpt> read(String owner, List<String> ids) {
        if (!properties.enabled()) throw new ApiException(503, "MATERIALS_DISABLED", "服务器尚未启用附件资料。");
        MaterialGuards.uuid(owner);
        if (ids == null || ids.isEmpty() || ids.size() > 5 || new HashSet<>(ids).size() != ids.size()) {
            throw MaterialGuards.invalid("每次请选择 1–5 条不同的资料摘录。");
        }
        ids.forEach(MaterialGuards::uuid);
        var sources = new ArrayList<AiExcerpt>(ids.size());
        for (String id : ids) {
            AiExcerpt source = load(owner, id);
            if (source == null) throw MaterialGuards.missing();
            sources.add(source);
        }
        return List.copyOf(sources);
    }

    public boolean current(String owner, List<AiExcerpt> snapshots) {
        if (!properties.enabled() || !uuid(owner) || snapshots == null || snapshots.isEmpty() || snapshots.size() > 5) return false;
        var seen = new HashSet<String>();
        for (AiExcerpt snapshot : snapshots) {
            if (snapshot == null || !uuid(snapshot.excerptId()) || !seen.add(snapshot.excerptId())) return false;
            // 完整快照参与比较；描述和新解析不改写原摘录，数据库故障则继续向调用方传播。
            try { if (!snapshot.equals(load(owner, snapshot.excerptId()))) return false; }
            catch (ApiException exception) {
                if ("MATERIAL_NOT_ACTIVE".equals(exception.code())) return false;
                throw exception;
            }
        }
        return true;
    }

    private AiExcerpt load(String owner, String id) {
        var excerpt = mapper.excerpt(owner, id);
        if (excerpt == null || !owner.equals(excerpt.ownerId()) || !id.equals(excerpt.id())) return null;
        lifecycle.requireActive(owner, id);
        var attachment = mapper.attachment(owner, excerpt.attachmentId());
        if (attachment == null || !owner.equals(attachment.ownerId()) || !excerpt.attachmentId().equals(attachment.id())
                || !"READY".equals(attachment.status()) || !attachment.sha256().equals(excerpt.attachmentSha256())) return null;
        if ("MANUAL".equals(excerpt.origin())) {
            if (excerpt.runId() != null || excerpt.segmentId() != null
                    || excerpt.startOffset() != null || excerpt.endOffset() != null) return null;
        } else if (!"PARSED".equals(excerpt.origin()) || !parsedSource(owner, excerpt, attachment.sha256())) {
            return null;
        }
        return new AiExcerpt(excerpt.id(), attachment.id(), attachment.fileName(), attachment.sha256(), excerpt.origin(),
                excerpt.runId(), excerpt.segmentId(), excerpt.locator(), excerpt.text(), excerpt.startOffset(), excerpt.endOffset());
    }

    private boolean parsedSource(String owner, MaterialRows.Excerpt excerpt, String sha256) {
        if (!uuid(excerpt.runId()) || !uuid(excerpt.segmentId()) || excerpt.startOffset() == null || excerpt.endOffset() == null) return false;
        // 只核对摘录固定的执行和片段，不能把旧引用替换为最新解析结果。
        var run = mapper.run(owner, excerpt.runId());
        if (run == null || !owner.equals(run.ownerId()) || !excerpt.runId().equals(run.id())
                || !excerpt.attachmentId().equals(run.attachmentId()) || !"SUCCEEDED".equals(run.status())
                || !sha256.equals(run.attachmentSha256())) return false;
        var segment = mapper.segment(owner, excerpt.segmentId());
        if (segment == null || !excerpt.segmentId().equals(segment.id()) || !excerpt.runId().equals(segment.runId())
                || !excerpt.attachmentId().equals(segment.attachmentId()) || !excerpt.locator().equals(segment.locator())) return false;
        int start = excerpt.startOffset(), end = excerpt.endOffset();
        String text = segment.text();
        return start >= 0 && end > start && end <= text.length()
                && !splitsSurrogate(text, start) && !splitsSurrogate(text, end)
                && excerpt.text().equals(text.substring(start, end));
    }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static boolean splitsSurrogate(String text, int index) {
        return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index));
    }
}
