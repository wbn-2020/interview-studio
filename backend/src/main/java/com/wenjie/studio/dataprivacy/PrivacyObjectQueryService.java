package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.wenjie.studio.dataprivacy.DataPrivacyDtos.PrivacyObjectItem;
import static com.wenjie.studio.dataprivacy.DataPrivacyDtos.PrivacyObjectPage;

@Service
public class PrivacyObjectQueryService {
    private static final DateTimeFormatter MYSQL_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> PRIVACY_STATES = Set.of("AVAILABLE", "TRASHED", "PURGED");
    private final PrivacyObjectMapper mapper;

    public PrivacyObjectQueryService(PrivacyObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public PrivacyObjectPage list(String owner, PrivacyScope.PrivacyActionableKind kind,
                                  String query, String cursor, int limit) {
        PrivacyLedger.uuid(owner);
        if (limit < 1 || limit > 50) throw invalid("每页数量须为 1 至 50。");
        String normalizedQuery = query(query);
        String filterHash = filterHash(kind, normalizedQuery);
        Cursor after = cursor(cursor, kind, filterHash);
        // LIKE 只把 % 和 _ 当通配符；先转义转义符本身，保证查询词始终按字面量匹配。
        String pattern = normalizedQuery == null ? null : "%" + normalizedQuery
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var rows = mapper.objects(owner, kind == null ? null : kind.name(), pattern,
                after == null ? null : after.updatedAt(), after == null ? null : after.kind().name(),
                after == null ? null : after.id(), null, limit + 1);
        if (rows.size() > limit + 1) throw new IllegalStateException("隐私对象查询超过单页上限");
        List<PrivacyObjectItem> items = new ArrayList<>(limit);
        for (PrivacyObjectMapper.Row row : rows.stream().limit(limit).toList()) {
            if (row.updatedAt() == null || !PRIVACY_STATES.contains(row.privacyState())) {
                throw new IllegalStateException("隐私对象目录包含无效状态或排序时间");
            }
            items.add(item(row));
        }
        String next = rows.size() > limit
                ? cursor(items.getLast(), filterHash) : null;
        return new PrivacyObjectPage(List.copyOf(items), next);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public PrivacyObjectPage listByIds(String owner, List<String> ids) {
        PrivacyLedger.uuid(owner);
        if (ids == null || ids.isEmpty() || ids.size() > 50 || new HashSet<>(ids).size() != ids.size())
            throw invalid("对象标识数量无效或重复。");
        ids.forEach(PrivacyObjectQueryService::uuid);
        var rows = mapper.objects(owner, null, null, null, null, null, List.copyOf(ids), ids.size() + 1);
        if (rows.size() > ids.size() + 1) throw new IllegalStateException("隐私对象身份查询超过单页上限");
        List<PrivacyObjectItem> items = rows.stream().limit(ids.size()).map(PrivacyObjectQueryService::item).toList();
        return new PrivacyObjectPage(List.copyOf(items), null);
    }

    private static PrivacyObjectItem item(PrivacyObjectMapper.Row row) {
        if (row.updatedAt() == null || !PRIVACY_STATES.contains(row.privacyState()))
            throw new IllegalStateException("隐私对象目录包含无效状态或排序时间");
        return new PrivacyObjectItem(PrivacyScope.actionableKind(row.objectKind()), row.objectId(),
                row.title(), row.subtitle(), row.updatedAt(), row.privacyState());
    }

    private static Cursor cursor(String value, PrivacyScope.PrivacyActionableKind filter, String filterHash) {
        if (value == null) return null;
        // 游标绑定筛选摘要，避免更换 kind／q 后跳过或重复；末三段保留完整稳定排序键。
        if (value.length() > 192) throw invalid("隐私对象游标无效。");
        String[] parts = value.split("~", -1);
        if (parts.length != 5 || !"v1".equals(parts[0]) || !filterHash.equals(parts[1])) {
            throw invalid("隐私对象游标无效，或与当前筛选条件不一致。");
        }
        LocalDateTime updatedAt = mysqlTime(parts[2]);
        PrivacyScope.PrivacyActionableKind kind = PrivacyScope.actionableKind(parts[3]);
        if (filter != null && filter != kind) throw invalid("隐私对象游标与当前类型筛选不一致。");
        uuid(parts[4]);
        return new Cursor(updatedAt, kind, parts[4]);
    }

    private static String cursor(PrivacyObjectItem item, String filterHash) {
        return "v1~" + filterHash + "~" + MYSQL_TIME.format(item.updatedAt()) + "~"
                + item.objectKind().name() + "~" + item.objectId();
    }

    private static LocalDateTime mysqlTime(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}")) {
            throw invalid("隐私对象游标时间无效。");
        }
        try {
            LocalDateTime time = LocalDateTime.parse(value, MYSQL_TIME);
            if (time.getYear() < 1000 || time.getYear() > 9999 || time.getNano() % 1000 != 0
                    || !MYSQL_TIME.format(time).equals(value)) throw invalid("隐私对象游标时间无效。");
            return time;
        } catch (DateTimeParseException exception) {
            throw invalid("隐私对象游标时间无效。");
        }
    }

    private static String query(String value) {
        if (value != null && value.length() > 100) throw invalid("标题查询词最多 100 个字符。");
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty()) return null;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= normalized.length() || !Character.isLowSurrogate(normalized.charAt(index))) {
                    throw invalid("标题查询词包含无效字符。");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalid("标题查询词包含无效字符。");
            }
        }
        return normalized;
    }

    private static String filterHash(PrivacyScope.PrivacyActionableKind kind, String query) {
        String value = (kind == null ? "" : kind.name()) + "\0" + (query == null ? "" : query);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("服务器缺少隐私游标摘要算法", exception);
        }
    }

    private static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw invalid("隐私对象游标标识须为标准小写 UUID。");
        }
    }

    private record Cursor(LocalDateTime updatedAt, PrivacyScope.PrivacyActionableKind kind, String id) { }

    private static ApiException invalid(String message) {
        return new ApiException(400, "INVALID_PRIVACY_REQUEST", message);
    }
}
