package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.knowledge.OrganizationDtos.EmptyRequest;
import com.wenjie.studio.knowledge.OrganizationDtos.ReferencesRebuildResult;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.knowledge.KnowledgeGuards.required;

@Service
public class KnowledgeReferenceService {
    private static final String PARSER_VERSION = "commonmark-0.32.0-v1";
    private final Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private final OrganizationMapper mapper;
    private final IdempotencyService commands;
    private final KnowledgeAttachmentService attachments;

    public KnowledgeReferenceService(OrganizationMapper mapper, IdempotencyService commands,
                                     KnowledgeAttachmentService attachments) {
        this.mapper = mapper;
        this.commands = commands;
        this.attachments = attachments;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void indexPublishedVersion(String owner, String itemId, String versionId, int number, String body) {
        index(owner, itemId, versionId, number, body, true);
        attachments.indexVersion(owner, itemId, versionId, number, body, true);
    }

    public ReferencesRebuildResult rebuild(String owner, String key, EmptyRequest request) {
        required(request);
        return commands.execute(owner, key, "POST /api/v1/knowledge/references/rebuild", request,
                ReferencesRebuildResult.class, () -> {
                    var versions = mapper.unscannedVersions(owner, 101);
                    int count = Math.min(versions.size(), 100);
                    int unresolved = 0;
                    for (var version : versions.subList(0, count)) {
                        unresolved += index(owner, version.itemId(), version.id(), version.number(), version.body(), false);
                        unresolved += attachments.indexVersion(owner, version.itemId(), version.id(), version.number(), version.body(), false);
                    }
                    // 旧知识链接已扫描的版本仍可能缺少附件扫描；两类索引共用每批 100 个版本的上限。
                    int remaining = 100 - count;
                    var attachmentVersions = attachments.unscannedVersions(owner, remaining + 1);
                    int attachmentCount = Math.min(attachmentVersions.size(), remaining);
                    for (var version : attachmentVersions.subList(0, attachmentCount)) {
                        unresolved += attachments.indexVersion(owner, version.itemId(), version.id(), version.number(), version.body(), false);
                    }
                    return new ReferencesRebuildResult(count + attachmentCount,
                            versions.size() > 100 || attachmentVersions.size() > remaining, unresolved);
                });
    }

    private int index(String owner, String itemId, String versionId, int number, String body, boolean strict) {
        if (mapper.referenceScanExists(owner, versionId)) return 0;
        List<Reference> parsed = parse(body);
        if (strict && parsed.size() > 200) throw new ApiException(422, "REFERENCE_LIMIT", "每个发布版本最多包含 200 个知识引用。");
        List<String> candidates = parsed.stream().map(Reference::target).filter(KnowledgeReferenceService::isUuid)
                .distinct().limit(200).toList();
        Set<String> available = candidates.isEmpty() ? Set.of() : new HashSet<>(mapper.availableTargetIds(owner, candidates));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int ordinal = 0;
        int unresolved = 0;
        for (Reference reference : parsed) {
            boolean usable = available.contains(reference.target()) && ordinal < 200;
            if (!usable) {
                if (strict) throw new ApiException(409, "REFERENCE_UNAVAILABLE", "正文中的知识引用不可用，请重新选择可访问的目标。");
                unresolved++;
                continue;
            }
            mapper.insertReference(owner, UUID.randomUUID().toString(), itemId, versionId, number,
                    reference.target(), ++ordinal, reference.lineStart(), reference.lineEnd(), reference.excerpt(), now);
        }
        // 旧版本不能被重写；无法解析的历史引用只记数量，不泄露目标存在性，也不阻断后续版本。
        mapper.markReferencesScanned(owner, itemId, versionId, unresolved, PARSER_VERSION, now);
        return unresolved;
    }

    private List<Reference> parse(String body) {
        Node root = parser.parse(body);
        ArrayDeque<Node> nodes = new ArrayDeque<>();
        nodes.add(root);
        List<Reference> references = new ArrayList<>();
        while (!nodes.isEmpty()) {
            Node node = nodes.removeLast();
            // 代码和图片子树中的文字不是可导航知识链接；只读取 CommonMark 识别的真实 Link。
            if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof Image) continue;
            if (node instanceof Link link && link.getDestination().regionMatches(true, 0, "knowledge:", 0, 10)) {
                Node paragraph = node;
                for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent instanceof Paragraph) { paragraph = parent; break; }
                }
                List<SourceSpan> spans = paragraph.getSourceSpans();
                if (spans.isEmpty()) spans = node.getSourceSpans();
                if (spans.isEmpty()) throw new ApiException(422, "REFERENCE_LOCATION_UNAVAILABLE", "无法定位知识引用的原文行，请调整 Markdown 后重试。");
                int firstLine = spans.stream().mapToInt(SourceSpan::getLineIndex).min().orElseThrow() + 1;
                int lastLine = spans.stream().mapToInt(SourceSpan::getLineIndex).max().orElseThrow() + 1;
                int first = spans.stream().mapToInt(SourceSpan::getInputIndex).min().orElseThrow();
                int end = spans.stream().mapToInt(span -> span.getInputIndex() + span.getLength()).max().orElseThrow();
                int clipped = Math.min(end, first + 300);
                if (clipped < end && clipped > first && Character.isHighSurrogate(body.charAt(clipped - 1))) clipped--;
                references.add(new Reference(link.getDestination().substring(10), firstLine, lastLine, body.substring(first, clipped)));
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) nodes.add(child);
        }
        return references;
    }

    private static boolean isUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private record Reference(String target, int lineStart, int lineEnd, String excerpt) {}
}
