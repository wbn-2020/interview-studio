package com.wenjie.studio.ai;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.planning.PlanningRules;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static com.wenjie.studio.ai.AiDtos.PreparationPlanContent;
import static com.wenjie.studio.ai.AiDtos.PreparationPlanInput;

public final class PreparationPlanRules {
    private PreparationPlanRules() {}

    public static void input(PreparationPlanInput value) {
        if (value == null || value.startDate() == null || value.endDate() == null) {
            throw invalid("请明确提供准备日期范围。");
        }
        LocalDate start = PlanningRules.date(value.startDate());
        LocalDate end = PlanningRules.date(value.endDate());
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > 90) throw invalid("准备日期须按先后排列，包含首尾最多 90 天。");
        PlanningRules.zone(value.zoneId());
        if (value.totalMinutes() == null || value.totalMinutes() < 1 || value.totalMinutes() > days * 1440) {
            throw invalid("总工作量须为正整数分钟，且不超过日期数乘以 1440。");
        }
        text(value.gapSummary(), 2000, false, "缺口摘要");
        // 日期是用户确认的固定条件；过去日期仍可审阅，不随今天变化而改写历史授权。
    }

    public static void content(PreparationPlanInput plan, PreparationPlanContent value) {
        input(plan);
        if (value == null) throw invalid("准备安排内容不能为空。");
        text(value.title(), 200, true, "安排标题");
        text(value.summary(), 2000, false, "安排摘要");
        if (value.steps() == null || value.steps().isEmpty() || value.steps().size() > 20) {
            throw invalid("准备安排须包含 1 至 20 个步骤。");
        }
        LocalDate start = PlanningRules.date(plan.startDate());
        LocalDate end = PlanningRules.date(plan.endDate());
        LocalDate previous = null;
        int dailyMinutes = 0, totalMinutes = 0;
        for (var step : value.steps()) {
            if (step == null || step.date() == null) throw invalid("每个准备步骤必须明确提供日期。");
            LocalDate date = PlanningRules.date(step.date());
            if (date.isBefore(start) || date.isAfter(end) || (previous != null && date.isBefore(previous))) {
                throw invalid("步骤日期须在授权范围内，并按日期先后排列。");
            }
            text(step.title(), 200, true, "步骤标题");
            text(step.detail(), 1000, false, "步骤说明");
            if (step.minutes() == null || step.minutes() < 1 || step.minutes() > 1440) {
                throw invalid("每步工作量须为 1 至 1440 的整数分钟。");
            }
            if (!date.equals(previous)) dailyMinutes = 0;
            dailyMinutes += step.minutes();
            totalMinutes += step.minutes();
            // 这些是名义工作量上限，不代表 DST 当日实际时长、日历空闲或已完成学习。
            if (dailyMinutes > 1440 || totalMinutes > plan.totalMinutes()) {
                throw invalid("安排超过当日 1440 分钟或本次总工作量预算。");
            }
            previous = date;
        }
    }

    private static void text(String value, int maximum, boolean nonblank, String field) {
        if (value == null || value.length() > maximum || (nonblank && value.isBlank())) {
            throw invalid(field + "为空或超过 UTF-16 长度上限。");
        }
        if (value.indexOf('\0') >= 0
                || value.codePoints().anyMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw invalid(field + "不能包含 NUL 或不完整的 Unicode 字符。");
        }
        // 校验不裁剪原文，预览、模型输入和最终采用保持用户明确提交的文字。
    }

    private static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
}
