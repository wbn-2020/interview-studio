package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.knowledge.KnowledgeGuards.*;
import static com.wenjie.studio.knowledge.KnowledgePackageDtos.*;
import static com.wenjie.studio.knowledge.KnowledgePackageMapper.*;

@Service
public class KnowledgePackageService {
    private static final String PREVIEW_ID = "00000000-0000-4000-8000-000000000000";
    private static final int MAX_LINKS = 200;
    private final Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private final KnowledgePackageMapper mapper;
    private final KnowledgeMapper knowledgeMapper;
    private final OrganizationMapper references;
    private final KnowledgeAttachmentMapper attachments;
    private final KnowledgeService knowledge;
    private final OrganizationService organization;
    private final IdempotencyService commands;
    private final JsonMapper json;

    public KnowledgePackageService(KnowledgePackageMapper mapper, KnowledgeMapper knowledgeMapper,
                                   OrganizationMapper references, KnowledgeAttachmentMapper attachments,
                                   KnowledgeService knowledge, OrganizationService organization,
                                   IdempotencyService commands, JsonMapper json) {
        this.mapper = mapper; this.knowledgeMapper = knowledgeMapper; this.references = references;
        this.attachments = attachments; this.knowledge = knowledge; this.organization = organization;
        this.commands = commands;
        this.json = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(String owner, Plan request) {
        uuid(owner);
        Prepared plan = prepare(request);
        List<Issue> issues = inspect(owner, plan);
        PackageRow existing = mapper.original(owner, plan.packageKey());
        return new Preview(plan.packageKey(), plan.planSha256(), existing == null ? null : existing.id(),
                plan.importCount(), plan.items().size() - plan.importCount(), issues);
    }

    public Detail register(String owner, String key, Register request) {
        uuid(owner); required(request); required(request.createCopy()); hash(request.packageKey());
        Prepared plan = prepare(new Plan(request.items(), request.sourceAttachments()));
        if (!plan.packageKey().equals(request.packageKey())) throw invalid("知识包标识须与服务器预览重算结果一致。");
        return commands.execute(owner, key, "POST /api/v1/knowledge/packages", request, Detail.class, () -> {
            if (!request.createCopy()) {
                PackageRow existing = mapper.original(owner, plan.packageKey());
                // 同包默认复用不可变映射；不同选择不覆盖原计划，用户可明确创建副本。
                if (existing != null) return snapshot(owner, existing);
            }
            requireValid(inspect(owner, plan));
            Instant now = now();
            PackageRow row = new PackageRow(UUID.randomUUID().toString(), 0, "REGISTERED", plan.packageKey(),
                    plan.planSha256(), request.createCopy(), plan.importCount(), plan.items().size() - plan.importCount(),
                    now, null);
            mapper.insert(owner, row);
            int ordinal = 0;
            for (PreparedItem entry : plan.items()) {
                SourceItem item = entry.item();
                boolean imported = item.decision() == Decision.IMPORT;
                mapper.insertEntry(owner, row.id(), new EntryRow(++ordinal, item.sourceId(), entry.identity().sha256(),
                        entry.identity().bytes(), entry.identity().characters(), item.kind().name(), item.decision().name(),
                        item.title(), json.writeValueAsString(item.tags()), json.writeValueAsString(item.aliases()),
                        json.writeValueAsString(item.topicIds()), imported ? UUID.randomUUID().toString() : null,
                        imported ? UUID.randomUUID().toString() : null));
            }
            ordinal = 0;
            for (SourceAttachment attachment : plan.attachments()) {
                mapper.insertAttachment(owner, row.id(), new AttachmentRow(++ordinal, attachment.sourceId(),
                        attachment.attachmentId(), attachment.sourceSha256()));
            }
            return snapshot(owner, row);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String owner, String id) {
        uuid(owner); uuid(id);
        return snapshot(owner, find(owner, id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String owner, String cursor, int limit) {
        uuid(owner); pageLimit(limit);
        PackageRow before = cursor == null ? null : mapper.find(owner, uuid(cursor));
        if (cursor != null && before == null) throw invalid("知识包游标不存在或不属于当前工作区，请重新读取首页。");
        List<PackageRow> rows = mapper.list(owner, before == null ? null : before.id(),
                before == null ? null : before.createdAt(), limit + 1);
        List<Summary> items = rows.subList(0, Math.min(rows.size(), limit)).stream().map(row ->
                new Summary(row.id(), row.revision(), row.state(), row.packageKey(), row.createCopy(),
                        row.importCount(), row.skipCount(), row.createdAt(), row.committedAt())).toList();
        return new Page(items, rows.size() > limit ? items.getLast().id() : null);
    }

    public Detail commit(String owner, String key, String id, Commit request) {
        uuid(owner); uuid(id); required(request); revision(request.expectedRevision()); hash(request.expectedPlanSha256());
        Prepared plan = prepare(new Plan(request.items(), request.sourceAttachments()));
        if (!plan.planSha256().equals(request.expectedPlanSha256())) {
            throw new ApiException(409, "PACKAGE_PLAN_MISMATCH", "提交内容与明确确认的计划摘要不同，请重新预览。");
        }
        return commands.execute(owner, key, "POST /api/v1/knowledge/packages/" + id + "/commit", request, Detail.class, () -> {
            PackageRow row = find(owner, id);
            if (!row.packageKey().equals(plan.packageKey()) || !row.planSha256().equals(plan.planSha256())) {
                throw new ApiException(409, "PACKAGE_PLAN_MISMATCH", "重新选择的原文或最终决定与登记计划不一致，未导入任何项。");
            }
            if (row.revision() != request.expectedRevision()) {
                throw new ApiException(409, "REVISION_CONFLICT", "知识包状态已变化，请读取已有映射后继续。", row.revision());
            }
            if (!"REGISTERED".equals(row.state())) {
                throw new ApiException(409, "PACKAGE_ALREADY_COMMITTED", "此知识包已经导入，请使用已有映射或明确新建副本。");
            }
            requireValid(inspect(owner, plan));
            Detail stored = snapshot(owner, row);
            Map<String, String> targets = new HashMap<>();
            for (Mapping mapping : stored.mappings()) if (mapping.decision() == Decision.IMPORT) {
                targets.put(mapping.sourceId(), mapping.itemId());
            }
            Map<String, String> attachmentTargets = new HashMap<>();
            for (SourceAttachment attachment : stored.sourceAttachments()) {
                attachmentTargets.put(attachment.sourceId(), attachment.attachmentId());
            }
            Map<String, SourceItem> source = new HashMap<>();
            for (PreparedItem entry : plan.items()) source.put(entry.item().sourceId(), entry.item());
            List<KnowledgeService.PackageCreateItem> pending = new ArrayList<>();
            for (Mapping mapping : stored.mappings()) {
                if (mapping.decision() != Decision.IMPORT) continue;
                SourceItem item = source.get(mapping.sourceId());
                List<Issue> issues = new ArrayList<>();
                String converted = rewrite(item, targets, attachmentTargets, issues);
                requireValid(issues);
                pending.add(new KnowledgeService.PackageCreateItem(mapping.itemId(), mapping.versionId(),
                        new KnowledgeDtos.CreateItem(item.kind(), item.title(), converted, item.topicIds())));
            }
            knowledge.createPackageWithinCommand(owner, pending);
            for (Mapping mapping : stored.mappings()) {
                if (mapping.decision() != Decision.IMPORT || mapping.tags().isEmpty() && mapping.aliases().isEmpty()) continue;
                organization.saveWithinCommand(owner, mapping.itemId(), new OrganizationDtos.OrganizationRequest(
                        0L, mapping.aliases(), mapping.tags(), false, List.of(), null));
            }
            Instant now = now();
            if (mapper.commit(owner, id, row.revision(), now) != 1) {
                throw new ApiException(409, "REVISION_CONFLICT", "知识包状态已变化，本次内容未提交。");
            }
            return snapshot(owner, new PackageRow(row.id(), row.revision() + 1, "COMMITTED", row.packageKey(),
                    row.planSha256(), row.createCopy(), row.importCount(), row.skipCount(), row.createdAt(), now));
        });
    }

    private Prepared prepare(Plan request) {
        required(request);
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 20
                || request.sourceAttachments() == null || request.sourceAttachments().size() > 20) {
            throw invalid("知识包须包含1至20项文本和至多20项附件映射。");
        }
        Set<String> paths = new HashSet<>();
        List<PreparedItem> items = new ArrayList<>();
        int importCount = 0;
        long textBytes = 0;
        for (SourceItem item : request.items()) {
            required(item); sourceId(item.sourceId());
            if (!paths.add(item.sourceId())) throw invalid("知识包中的原始相对路径不能重复。");
            var identity = KnowledgeTextImportService.sourceIdentity(item.source());
            String title = text(item.title(), 200, false, false).strip();
            if (title.isEmpty()) throw invalid("知识包标题不能为空。");
            String body = text(item.body(), 100_000, true, true);
            required(item.kind()); required(item.decision());
            List<String> tags = labels(item.tags(), 40);
            List<String> aliases = labels(item.aliases(), 100);
            List<String> topicIds = ids(item.topicIds());
            if (item.kind() == KnowledgeDtos.Kind.NOTE && !aliases.isEmpty()) throw invalid("笔记不能设置概念别名。");
            if (item.decision() == Decision.IMPORT) importCount++;
            SourceItem normalized = new SourceItem(item.sourceId(), item.source(), title, body, item.kind(),
                    item.decision(), tags, aliases, topicIds);
            items.add(new PreparedItem(normalized, identity));
            textBytes += identity.bytes() + body.getBytes(StandardCharsets.UTF_8).length;
        }
        if (importCount == 0) throw invalid("至少明确选择导入一项；全部跳过时无需登记知识包。");
        if (textBytes > 1_048_576) throw invalid("知识包原文与最终正文总量超过1MiB，请缩小本次文件范围。");
        List<SourceAttachment> attachmentItems = new ArrayList<>();
        for (SourceAttachment attachment : request.sourceAttachments()) {
            required(attachment); sourceId(attachment.sourceId()); uuid(attachment.attachmentId()); hash(attachment.sourceSha256());
            if (!paths.add(attachment.sourceId())) throw invalid("文本与附件的原始相对路径不能重复。");
            attachmentItems.add(attachment);
        }
        items.sort(Comparator.comparing(entry -> entry.item().sourceId()));
        attachmentItems.sort(Comparator.comparing(SourceAttachment::sourceId));
        List<IdentitySeal> identities = new ArrayList<>();
        List<ItemSeal> selections = new ArrayList<>();
        for (PreparedItem entry : items) {
            SourceItem item = entry.item();
            identities.add(new IdentitySeal("TEXT", item.sourceId(), entry.identity().sha256()));
            selections.add(new ItemSeal(item.sourceId(), entry.identity().sha256(), item.title(), item.body(),
                    item.kind(), item.decision(), item.tags(), item.aliases(), item.topicIds()));
        }
        for (SourceAttachment attachment : attachmentItems) {
            identities.add(new IdentitySeal("ATTACHMENT", attachment.sourceId(), attachment.sourceSha256()));
        }
        identities.sort(Comparator.comparing(IdentitySeal::sourceId).thenComparing(IdentitySeal::type));
        String packageKey = digest(json.writeValueAsString(identities));
        String planSha256 = digest(json.writeValueAsString(new PlanSeal(selections, attachmentItems)));
        return new Prepared(List.copyOf(items), List.copyOf(attachmentItems), packageKey, planSha256, importCount);
    }

    private List<Issue> inspect(String owner, Prepared plan) {
        List<Issue> issues = new ArrayList<>();
        Map<String, String> targets = new HashMap<>(), attachmentTargets = new HashMap<>();
        Set<String> topicIds = new HashSet<>();
        for (PreparedItem entry : plan.items()) if (entry.item().decision() == Decision.IMPORT) {
            targets.put(entry.item().sourceId(), PREVIEW_ID);
            topicIds.addAll(entry.item().topicIds());
        }
        Set<String> availableTopics = topicIds.isEmpty() ? Set.of()
                : new HashSet<>(knowledgeMapper.ownedTopicIds(owner, List.copyOf(topicIds)));
        for (PreparedItem entry : plan.items()) if (entry.item().decision() == Decision.IMPORT
                && !availableTopics.containsAll(entry.item().topicIds())) {
            issues.add(new Issue(entry.item().sourceId(), "TOPIC_UNAVAILABLE", "明确选择的主题不可用，请重新选择当前工作区主题。"));
        }
        Set<String> attachmentIds = new HashSet<>();
        for (SourceAttachment attachment : plan.attachments()) attachmentIds.add(attachment.attachmentId());
        Map<String, String> ready = ready(owner, attachmentIds);
        for (SourceAttachment attachment : plan.attachments()) {
            if (!attachment.sourceSha256().equals(ready.get(attachment.attachmentId()))) {
                issues.add(new Issue(attachment.sourceId(), "ATTACHMENT_UNAVAILABLE", "附件须先上传完成，且READY附件摘要与包内原件摘要一致。"));
            } else attachmentTargets.put(attachment.sourceId(), attachment.attachmentId());
        }
        for (PreparedItem entry : plan.items()) if (entry.item().decision() == Decision.IMPORT) {
            if (issues.size() >= 100) break;
            SourceItem item = entry.item();
            rewrite(item, targets, attachmentTargets, issues);
            inspectExistingReferences(owner, item, issues);
        }
        if (issues.size() > 100) {
            return List.copyOf(issues.subList(0, 100));
        }
        return List.copyOf(issues);
    }

    private void inspectExistingReferences(String owner, SourceItem item, List<Issue> issues) {
        Set<String> knowledgeIds = new HashSet<>(), materialIds = new HashSet<>();
        int knowledgeCount = 0, attachmentCount = 0;
        for (Node node : links(item.body())) {
            if (issues.size() >= 100) return;
            String destination = destination(node);
            if (starts(destination, "knowledge:")) {
                knowledgeCount++;
                String target = destination.substring(10);
                if (isUuid(target)) knowledgeIds.add(target);
                else issues.add(new Issue(item.sourceId(), "KNOWLEDGE_REFERENCE_INVALID", "现有知识链接须指向标准小写UUID。"));
            } else if (starts(destination, "material:")) {
                attachmentCount++;
                String target = destination.substring(9);
                if (isUuid(target)) materialIds.add(target);
                else issues.add(new Issue(item.sourceId(), "ATTACHMENT_REFERENCE_INVALID", "现有附件链接须指向标准小写UUID。"));
            } else if (starts(destination, "package:")) knowledgeCount++;
            else if (starts(destination, "package-attachment:")) attachmentCount++;
        }
        if (knowledgeCount > MAX_LINKS || attachmentCount > MAX_LINKS) {
            issues.add(new Issue(item.sourceId(), "REFERENCE_LIMIT", "每份正文最多包含200个知识引用及200个附件引用。"));
            return;
        }
        if (!knowledgeIds.isEmpty()) {
            Set<String> available = new HashSet<>(references.availableTargetIds(owner, List.copyOf(knowledgeIds)));
            if (!available.containsAll(knowledgeIds)) {
                issues.add(new Issue(item.sourceId(), "KNOWLEDGE_REFERENCE_UNAVAILABLE", "正文存在当前工作区无法访问的知识链接。"));
            }
        }
        if (!materialIds.isEmpty() && !ready(owner, materialIds).keySet().containsAll(materialIds)) {
            issues.add(new Issue(item.sourceId(), "ATTACHMENT_REFERENCE_UNAVAILABLE", "正文存在尚未上传完成或无法访问的附件链接。"));
        }
    }

    private Map<String, String> ready(String owner, Set<String> ids) {
        Map<String, String> result = new HashMap<>();
        if (!ids.isEmpty()) for (var row : attachments.readyAttachments(owner, List.copyOf(ids))) {
            result.put(row.id(), row.sha256());
        }
        return result;
    }

    private String rewrite(SourceItem item, Map<String, String> targets, Map<String, String> attachmentTargets,
                           List<Issue> issues) {
        List<Replacement> replacements = new ArrayList<>();
        int packageKnowledgeCount = 0, packageAttachmentCount = 0;
        for (Node node : links(item.body())) {
            if (issues.size() >= 100) break;
            String destination = destination(node);
            boolean knowledgeLink = starts(destination, "package:");
            boolean attachmentLink = starts(destination, "package-attachment:");
            if (!knowledgeLink && !attachmentLink) continue;
            if (knowledgeLink) packageKnowledgeCount++; else packageAttachmentCount++;
            if (packageKnowledgeCount > MAX_LINKS || packageAttachmentCount > MAX_LINKS) {
                issues.add(new Issue(item.sourceId(), "REFERENCE_LIMIT", "每份正文最多包含200个知识引用及200个附件引用。"));
                break;
            }
            if (knowledgeLink && node instanceof Image) {
                issues.add(new Issue(item.sourceId(), "PACKAGE_KNOWLEDGE_IMAGE", "知识项只能作为链接引用；图片请映射为包内附件。"));
                continue;
            }
            String target;
            try { target = decodePath(destination.substring(knowledgeLink ? 8 : 19)); }
            catch (ApiException exception) {
                issues.add(new Issue(item.sourceId(), "PACKAGE_REFERENCE_INVALID", exception.getMessage()));
                continue;
            }
            String mapped = (knowledgeLink ? targets : attachmentTargets).get(target);
            if (mapped == null) {
                issues.add(new Issue(item.sourceId(), "PACKAGE_REFERENCE_UNRESOLVED",
                        "包内目标“" + target + "”缺失、已跳过或附件尚未明确映射；不会按标题猜测。"));
                continue;
            }
            Replacement replacement = locate(item.body(), node, destination, (knowledgeLink ? "knowledge:" : "material:") + mapped);
            if (replacement == null) issues.add(new Issue(item.sourceId(), "PACKAGE_REFERENCE_LOCATION",
                    "包内引用须使用可定位的内联Markdown链接或自动链接，请先转换引用式链接。"));
            else replacements.add(replacement);
        }
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        StringBuilder converted = new StringBuilder(item.body());
        int boundary = item.body().length();
        for (Replacement replacement : replacements) {
            if (replacement.end() > boundary) {
                issues.add(new Issue(item.sourceId(), "PACKAGE_REFERENCE_OVERLAP", "包内引用范围重叠，请调整Markdown后重新预览。"));
                return item.body();
            }
            converted.replace(replacement.start(), replacement.end(), replacement.value());
            boundary = replacement.start();
        }
        if (converted.length() > 100_000) issues.add(new Issue(item.sourceId(), "PACKAGE_BODY_LIMIT",
                "转换为本地链接后的正文超过100000字符，请缩小本次内容。"));
        return converted.toString();
    }

    private List<Node> links(String body) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        List<Node> result = new ArrayList<>();
        queue.add(parser.parse(body));
        while (!queue.isEmpty()) {
            Node node = queue.removeLast();
            if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) continue;
            if (node instanceof Link || node instanceof Image) result.add(node);
            if (node instanceof Image) continue;
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) queue.add(child);
        }
        return result;
    }

    private static Replacement locate(String body, Node node, String destination, String replacement) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) return null;
        int first = spans.stream().mapToInt(SourceSpan::getInputIndex).min().orElseThrow();
        int end = spans.stream().mapToInt(span -> span.getInputIndex() + span.getLength()).max().orElseThrow();
        if (first < 0 || end > body.length() || end <= first) return null;
        String raw = body.substring(first, end);
        if (raw.equals("<" + destination + ">")) return new Replacement(first + 1, end - 1, replacement);
        Replacement found = null;
        int boundary = -1;
        while ((boundary = raw.indexOf("](", boundary + 1)) >= 0) {
            int start = boundary + 2;
            while (start < raw.length() && Character.isWhitespace(raw.charAt(start))) start++;
            boolean wrapped = start < raw.length() && raw.charAt(start) == '<';
            if (wrapped) start++;
            int finish = start + destination.length();
            if (finish >= raw.length() || !raw.startsWith(destination, start)) continue;
            char next = raw.charAt(finish);
            if (wrapped ? next != '>' : next != ')' && !Character.isWhitespace(next)) continue;
            if (found != null) return null;
            found = new Replacement(first + start, first + finish, replacement);
        }
        return found;
    }

    private Detail snapshot(String owner, PackageRow row) {
        List<EntryRow> entries = mapper.entries(owner, row.id());
        if (entries.size() != row.importCount() + row.skipCount() || entries.isEmpty() || entries.size() > 20) {
            throw new IllegalStateException("知识包登记清单与项数不一致");
        }
        List<Mapping> mappings = new ArrayList<>();
        int ordinal = 0, imported = 0;
        Set<String> sources = new HashSet<>();
        for (EntryRow entry : entries) {
            if (entry.ordinal() != ++ordinal || !sources.add(entry.sourceId())) throw new IllegalStateException("知识包清单顺序或路径异常");
            Decision decision = Decision.valueOf(entry.decision());
            if (decision == Decision.IMPORT) {
                uuid(entry.itemId()); uuid(entry.versionId()); imported++;
            } else if (entry.itemId() != null || entry.versionId() != null) {
                throw new IllegalStateException("跳过项不能持有导入身份");
            }
            mappings.add(new Mapping(entry.sourceId(), entry.sourceSha256(), entry.sourceBytes(), entry.sourceCharacters(),
                    KnowledgeDtos.Kind.valueOf(entry.kind()), decision, entry.title(), strings(entry.tagsJson()),
                    strings(entry.aliasesJson()), strings(entry.topicIdsJson()), entry.itemId(), entry.versionId()));
        }
        if (imported != row.importCount()) throw new IllegalStateException("知识包导入决定数量异常");
        List<SourceAttachment> attachmentMappings = new ArrayList<>();
        ordinal = 0;
        for (AttachmentRow attachment : mapper.attachments(owner, row.id())) {
            if (attachment.ordinal() != ++ordinal || ordinal > 20 || !sources.add(attachment.sourceId())) {
                throw new IllegalStateException("知识包附件映射顺序或路径异常");
            }
            attachmentMappings.add(new SourceAttachment(attachment.sourceId(), attachment.attachmentId(), attachment.sourceSha256()));
        }
        return new Detail(row.id(), row.revision(), row.state(), row.packageKey(), row.planSha256(), row.createCopy(),
                row.createdAt(), row.committedAt(), List.copyOf(mappings), List.copyOf(attachmentMappings));
    }

    private PackageRow find(String owner, String id) {
        PackageRow row = mapper.find(owner, id);
        if (row == null) throw new ApiException(404, "KNOWLEDGE_PACKAGE_NOT_FOUND", "知识包不存在或不属于当前工作区。");
        return row;
    }

    private List<String> strings(String raw) { return List.of(json.readValue(raw, String[].class)); }

    private static String sourceId(String value) {
        text(value, 600, false, false);
        if (value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) throw invalid("原始标识须为相对路径，不能含反斜杠或冒号。");
        String[] segments = value.split("/", -1);
        if (segments.length > 32) throw invalid("原始相对路径最多32段。");
        for (String segment : segments) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw invalid("原始相对路径不能含空段、当前目录或上级目录。");
        }
        return value;
    }

    private static List<String> labels(List<String> values, int maxLength) {
        if (values == null || values.size() > 20) throw invalid("标签与别名须为最多20项的明确数组。");
        Map<String, String> result = new LinkedHashMap<>();
        for (String value : values) {
            String label = text(value, maxLength, false, false).strip();
            if (label.isEmpty()) throw invalid("标签和别名不能为空白。");
            result.putIfAbsent(label.toLowerCase(Locale.ROOT), label);
        }
        return List.copyOf(result.values());
    }

    private static List<String> ids(List<String> values) {
        if (values == null || values.size() > 20) throw invalid("主题须为最多20项的明确数组。");
        Set<String> result = new HashSet<>();
        for (String value : values) if (!result.add(uuid(value))) throw invalid("主题标识不能重复。");
        return values.stream().sorted().toList();
    }

    private static String text(String value, int max, boolean empty, boolean multiline) {
        if (value == null || value.length() > max || !empty && value.isEmpty()
                || value.codePoints().anyMatch(point -> Character.isISOControl(point)
                && !(multiline && (point == 9 || point == 10 || point == 13)))) throw invalid("文本为空、超长或包含不支持的控制字符。");
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException exception) { throw invalid("文本包含无效Unicode代理项。"); }
        return value;
    }

    private static String decodePath(String encoded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < encoded.length();) {
            char current = encoded.charAt(index);
            if (current == '%') {
                if (index + 2 >= encoded.length()) throw invalid("包内链接存在不完整的百分号编码。");
                int high = Character.digit(encoded.charAt(index + 1), 16), low = Character.digit(encoded.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw invalid("包内链接存在无效的百分号编码。");
                bytes.write(high * 16 + low); index += 3;
            } else {
                if (current > 127 || !unreserved(current) && current != '/') {
                    throw invalid("包内链接路径须按UTF-8逐段进行百分号编码，保留路径分隔符/。");
                }
                bytes.write(current); index++;
            }
        }
        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException exception) { throw invalid("包内链接不是有效UTF-8路径。"); }
        sourceId(decoded);
        return decoded;
    }

    private static boolean unreserved(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~';
    }
    private static boolean starts(String value, String prefix) { return value.regionMatches(true, 0, prefix, 0, prefix.length()); }
    private static String destination(Node node) { return node instanceof Link link ? link.getDestination() : ((Image) node).getDestination(); }
    private static boolean isUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid("摘要须为64位小写SHA256。");
    }
    private static void requireValid(List<Issue> issues) {
        if (!issues.isEmpty()) throw new ApiException(409, "KNOWLEDGE_PACKAGE_PREVIEW_REQUIRED",
                "知识包仍有" + issues.size() + "项未解决问题：" + issues.getFirst().message());
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("当前运行时不支持知识包摘要", exception); }
    }
    private record PreparedItem(SourceItem item, KnowledgeTextImportService.SourceIdentity identity) {}
    private record Prepared(List<PreparedItem> items, List<SourceAttachment> attachments, String packageKey,
                            String planSha256, int importCount) {}
    private record IdentitySeal(String type, String sourceId, String sourceSha256) {}
    private record ItemSeal(String sourceId, String sourceSha256, String title, String body, KnowledgeDtos.Kind kind,
                            Decision decision, List<String> tags, List<String> aliases, List<String> topicIds) {}
    private record PlanSeal(List<ItemSeal> items, List<SourceAttachment> sourceAttachments) {}
    private record Replacement(int start, int end, String value) {}
}
