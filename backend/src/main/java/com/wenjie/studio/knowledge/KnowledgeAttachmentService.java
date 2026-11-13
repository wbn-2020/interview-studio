package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.knowledge.OrganizationRows.UnscannedVersion;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.wenjie.studio.knowledge.KnowledgeGuards.uuid;

@Service
public class KnowledgeAttachmentService {
    private static final int MAX_REFERENCES = 200;
    private static final String PARSER_VERSION = "commonmark-0.32.0-material-v1";
    private final Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private final KnowledgeAttachmentMapper mapper;

    public KnowledgeAttachmentService(KnowledgeAttachmentMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int indexVersion(String owner, String itemId, String versionId, int number, String body, boolean strict) {
        requireWriteTransaction();
        uuid(owner); uuid(itemId); uuid(versionId);
        if (number < 1 || body == null) throw new IllegalArgumentException("附件引用的知识版本无效");
        if (mapper.scanExists(owner, versionId)) return 0;
        List<Reference> references = parse(body);
        if (strict && references.size() > MAX_REFERENCES) {
            throw new ApiException(422, "ATTACHMENT_REFERENCE_LIMIT", "每个发布版本最多包含 200 个附件引用。");
        }
        List<String> candidates = references.stream().map(Reference::target)
                .filter(KnowledgeAttachmentService::isUuid).distinct().limit(MAX_REFERENCES).toList();
        Map<String, String> available = new HashMap<>();
        if (!candidates.isEmpty()) {
            for (var attachment : mapper.readyAttachments(owner, candidates)) {
                if (attachment.sha256() == null || !attachment.sha256().matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("附件引用的原件摘要无效");
                }
                available.put(attachment.id(), attachment.sha256());
            }
        }
        int ordinal = 0;
        int unresolved = 0;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (Reference reference : references) {
            String sha256 = available.get(reference.target());
            if (sha256 == null || ordinal >= MAX_REFERENCES) {
                if (strict) throw new ApiException(409, "ATTACHMENT_REFERENCE_UNAVAILABLE", "正文中的附件引用不可用，请重新选择已上传的私有附件。");
                unresolved++;
                continue;
            }
            // 这里只登记正文的结构引用；不创建摘录、内容采用证据或模型读取许可。
            mapper.insertReference(owner, UUID.randomUUID().toString(), itemId, versionId, number,
                    reference.target(), sha256, ++ordinal, reference.kind(), reference.lineStart(), reference.lineEnd(), now);
        }
        // 历史版本按实际扫描结果登记；未扫描版本没有标记，无法访问的旧目标只记数量。
        mapper.markScanned(owner, itemId, versionId, ordinal, unresolved, PARSER_VERSION, now);
        return unresolved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UnscannedVersion> unscannedVersions(String owner, int limit) {
        requireWriteTransaction();
        uuid(owner);
        if (limit < 1 || limit > 101) throw new IllegalArgumentException("附件引用扫描批量无效");
        return mapper.unscannedVersions(owner, limit);
    }

    private List<Reference> parse(String body) {
        ArrayDeque<Node> nodes = new ArrayDeque<>();
        nodes.add(parser.parse(body));
        List<Reference> references = new ArrayList<>();
        while (!nodes.isEmpty()) {
            Node node = nodes.removeLast();
            if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) continue;
            String destination = node instanceof Link link ? link.getDestination()
                    : node instanceof Image image ? image.getDestination() : null;
            if (destination != null && destination.regionMatches(true, 0, "material:", 0, 9)) {
                List<SourceSpan> spans = node.getSourceSpans();
                if (spans.isEmpty()) {
                    throw new ApiException(422, "ATTACHMENT_REFERENCE_LOCATION_UNAVAILABLE", "无法定位附件引用的原文行，请调整 Markdown 后重试。");
                }
                int firstLine = spans.stream().mapToInt(SourceSpan::getLineIndex).min().orElseThrow() + 1;
                int lastLine = spans.stream().mapToInt(SourceSpan::getLineIndex).max().orElseThrow() + 1;
                references.add(new Reference(destination.substring(9), node instanceof Image ? "IMAGE" : "LINK", firstLine, lastLine));
            }
            // 图片替代文字中的标记不代表独立附件链接。
            if (node instanceof Image) continue;
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) nodes.add(child);
        }
        return references;
    }

    private static boolean isUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("附件引用必须与知识发布在同一写事务中登记");
        }
    }

    private record Reference(String target, String kind, int lineStart, int lineEnd) {}
}
