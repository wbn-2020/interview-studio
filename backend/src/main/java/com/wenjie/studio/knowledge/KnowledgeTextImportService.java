package com.wenjie.studio.knowledge;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.wenjie.studio.knowledge.KnowledgeGuards.*;
import static com.wenjie.studio.knowledge.KnowledgeTextImportDtos.*;

@Service
public class KnowledgeTextImportService {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONTROLS = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]");
    private final KnowledgeTextImportMapper mapper;
    private final KnowledgeService knowledge;
    private final IdempotencyService commands;
    private final OrganizationService organization;

    public KnowledgeTextImportService(KnowledgeTextImportMapper mapper, KnowledgeService knowledge,
                                      IdempotencyService commands, OrganizationService organization) {
        this.mapper = mapper; this.knowledge = knowledge; this.commands = commands;
        this.organization = organization;
    }

    public Saved create(String owner, String key, Create request) {
        SourceIdentity source = validate(request);
        return commands.execute(owner, key, "POST /api/v1/knowledge/text-imports", request, Saved.class,
                () -> createValidated(owner, request, source));
    }

    public SavedWithTags createWithTags(String owner, String key, CreateWithTags request) {
        required(request);
        Create original = new Create(request.fileName(), request.source(), request.title(), request.body(),
                request.topicIds(), request.createCopy());
        SourceIdentity source = validate(original);
        if (request.tags() == null || request.tags().isEmpty() || request.tags().size() > 20) {
            throw invalid("明确采用的标签须为1至20项。");
        }
        for (String tag : request.tags()) {
            utf8(tag, 40);
            if (tag.isEmpty() || tag.strip().isEmpty() || tag.matches("(?s).*[\\x00-\\x1f\\x7f].*")) {
                throw invalid("标签须为1至40字符的非空文本，不能包含控制字符。");
            }
        }
        List<String> tags = List.copyOf(request.tags());
        return commands.execute(owner, key, "POST /api/v1/knowledge/text-imports/with-tags", request,
                SavedWithTags.class, () -> {
                    // 同一owner命令内初始化新笔记标签；失败必须连同首版、导入账本及回执全部回滚。
                    Saved saved = createValidated(owner, original, source);
                    var stored = organization.initializeImportedTagsWithinCommand(owner, saved.item().id(), tags);
                    return new SavedWithTags(saved.item(), saved.record(), stored);
                });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Saved createWithinCommand(String owner, Create request) {
        // 批次决策已持有同一owner命令锁；不另开事务或回执，首版、登记及批次状态一起提交。
        uuid(owner);
        return createValidated(owner, request, validate(request));
    }

    private static SourceIdentity validate(Create request) {
        required(request);
        fileName(request.fileName());
        SourceIdentity source = sourceIdentity(request.source());
        utf8(request.body(), 100_000);
        utf8(request.title(), 200);
        if (request.title().matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw invalid("标题不能包含控制字符。");
        required(request.createCopy());
        return source;
    }

    private Saved createValidated(String owner, Create request, SourceIdentity source) {
        // 原键先恢复回执；新请求才在同一owner锁内查重，避免响应丢失或并发造成重复创建。
        if (!request.createCopy() && mapper.exists(owner, source.sha256())) {
            throw new ApiException(409, "TEXT_IMPORT_EXISTS",
                    "这份原始文本已有导入记录。请检查既有首版，或明确选择新建副本；未覆盖任何内容。");
        }
        var item = knowledge.createWithinCommand(owner, new KnowledgeDtos.CreateItem(
                KnowledgeDtos.Kind.NOTE, request.title(), request.body(), request.topicIds()));
        Entry entry = new Entry(UUID.randomUUID().toString(), request.fileName(), source.sha256(), source.bytes(),
                source.characters(), item.id(), item.currentVersionId(), item.published().title(),
                item.status().name(), request.createCopy(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        // 只保存指纹及确切首版身份，不额外持久化原文件正文，也不将客户端转换声明认证为证据。
        mapper.insert(owner, entry);
        return new Saved(item, entry);
    }

    static void fileName(String value) {
        if (value == null || value.length() > 200 || !value.matches("(?i).*\\.(md|markdown|txt)")
                || value.matches("(?s).*[\\x00-\\x1f\\x7f/\\\\].*")) {
            throw invalid("文件名须为最多200字符的Markdown／TXT名称，不能包含路径或控制字符。");
        }
        utf8(value, 200);
    }

    record SourceIdentity(String sha256, int bytes, int characters) {}

    static SourceIdentity sourceIdentity(String value) {
        byte[] bytes = utf8(value, 100_000);
        if (bytes.length == 0 || bytes.length > 400_000) throw invalid("原始文本须为1至400000字节。");
        return new SourceIdentity(sha256(bytes), bytes.length, value.length());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String owner, String sha256, String cursor, int limit) {
        uuid(owner); pageLimit(limit);
        if (sha256 != null && !HASH.matcher(sha256).matches()) throw invalid("原始文本指纹须为64位小写SHA256。");
        Entry before = cursor == null ? null : mapper.find(owner, uuid(cursor));
        if (cursor != null && (before == null || sha256 != null && !sha256.equals(before.sourceSha256()))) {
            throw invalid("导入记录游标不存在或不属于当前筛选，请重新读取首页。");
        }
        var rows = mapper.list(owner, sha256, before == null ? null : before.id(),
                before == null ? null : before.createdAt(), limit + 1);
        boolean more = rows.size() > limit;
        List<Entry> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new Page(items, more ? items.getLast().id() : null);
    }

    private static byte[] utf8(String value, int maxCharacters) {
        if (value == null || value.length() > maxCharacters || CONTROLS.matcher(value).find()) {
            throw invalid("导入文本为空值、包含不支持的控制字符或超出长度限制。");
        }
        try {
            // Java默认编码会替换未配对代理项；严格拒绝后才能以UTF-8指纹代表这份输入。
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) { throw invalid("导入文本包含无效Unicode代理项。"); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("当前运行时不支持文本摘要", exception); }
    }
}
