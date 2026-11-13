package com.wenjie.studio.preferences;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.common.IdempotencyService;
import com.wenjie.studio.material.MaterialProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.wenjie.studio.preferences.PreferenceDtos.*;

@Service
public class PreferenceService {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private final PreferenceMapper mapper;
    private final IdempotencyService commands;
    private final MaterialProperties materials;

    public PreferenceService(PreferenceMapper mapper, IdempotencyService commands, MaterialProperties materials) {
        this.mapper = mapper; this.commands = commands; this.materials = materials;
    }

    @Transactional(readOnly = true, timeout = 10)
    public Preferences get(String owner) {
        Preferences value = mapper.find(owner);
        // 读取默认值没有写入副作用；首次保存与后续保存共用修订比较。
        return value == null ? new Preferences(0, Theme.SYSTEM, 14, 1280, Motion.SYSTEM, "UTC", true, null) : checked(value);
    }

    public Preferences save(String owner, String key, Save request) {
        validate(request);
        return commands.execute(owner, key, "PUT /api/v1/preferences", request, Preferences.class, () -> {
            Preferences before = mapper.find(owner);
            long revision = before == null ? 0 : checked(before).revision();
            if (request.expectedRevision() != revision) throw conflict(revision);
            if (revision == MAX_REVISION)
                throw new ApiException(422, "PREFERENCES_REVISION_LIMIT", "个人设置修订已达到上限，无法继续保存。", revision);
            Preferences saved = new Preferences(revision + 1, request.theme(), request.fontSize(), request.contentWidth(),
                    request.motion(), request.timeZone(), request.saveRecoveryNotice(), Instant.now().truncatedTo(ChronoUnit.MICROS));
            // owner 写锁由幂等事务持有；首次创建及更新都与回执原子提交。
            if (before == null) mapper.insert(owner, saved);
            else if (mapper.update(owner, revision, saved) != 1) throw conflict(revision);
            return saved;
        });
    }

    @Transactional(readOnly = true, timeout = 10)
    public Usage usage(String owner) {
        UsageCounts value = mapper.usage(owner);
        if (value == null || List.of(value.notes(), value.concepts(), value.knowledgeVersions(), value.boards(),
                value.preparationItems(), value.questions(), value.trainingSessions(), value.submittedAnswers(),
                value.memoryCards(), value.attachments(), value.aiTasks(), value.materialReservedBytes())
                .stream().anyMatch(number -> number < 0 || number > MAX_REVISION))
            throw new ApiException(503, "PREFERENCES_USAGE_UNAVAILABLE", "用量统计暂不可用，请稍后重新读取。");
        return new Usage(value.notes(), value.concepts(), value.knowledgeVersions(), value.boards(),
                value.preparationItems(), value.questions(), value.trainingSessions(), value.submittedAnswers(),
                value.memoryCards(), value.attachments(), value.aiTasks(), value.materialReservedBytes(),
                materials.quotaBytes(), materials.enabled(), Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static void validate(Save request) {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0
                || request.expectedRevision() > MAX_REVISION || request.theme() == null || request.motion() == null
                || request.fontSize() == null || request.fontSize() < 12 || request.fontSize() > 24
                || request.contentWidth() == null || request.contentWidth() < 720 || request.contentWidth() > 1600
                || request.saveRecoveryNotice() == null || !validZone(request.timeZone()))
            throw invalid("请核对个人设置：字号为12至24，内容宽度为720至1600，并填写有效时区。");
    }

    private static Preferences checked(Preferences value) {
        if (value.revision() <= 0 || value.revision() > MAX_REVISION || value.updatedAt() == null)
            throw new ApiException(503, "PREFERENCES_UNAVAILABLE", "服务器设置修订异常，请稍后重新读取。");
        try {
            validate(new Save(value.revision(), value.theme(), value.fontSize(), value.contentWidth(),
                    value.motion(), value.timeZone(), value.saveRecoveryNotice()));
        } catch (ApiException exception) {
            throw new ApiException(503, "PREFERENCES_UNAVAILABLE", "服务器设置字段异常，请稍后重新读取。");
        }
        return value;
    }

    private static boolean validZone(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_+./:-]{1,64}")) return false;
        try { ZoneId.of(value); return true; }
        catch (DateTimeException exception) { return false; }
    }

    static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    private static ApiException conflict(long revision) {
        return new ApiException(409, "PREFERENCES_CONFLICT", "个人设置已被修改。请保留输入并核对服务器设置。", revision);
    }
}
