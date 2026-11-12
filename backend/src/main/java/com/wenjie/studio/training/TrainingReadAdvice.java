package com.wenjie.studio.training;

import com.wenjie.studio.common.ApiProblem;
import com.wenjie.studio.common.CurrentOwner;
import com.wenjie.studio.ai.AiDtos;
import com.wenjie.studio.today.TodayPlanDtos;
import com.wenjie.studio.schedule.SchedulePreparationDtos;
import com.wenjie.studio.growth.GrowthDtos;
import com.wenjie.studio.review.StandardReviewDtos;
import com.wenjie.studio.review.ReviewedObservationDtos;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(basePackages = "com.wenjie.studio")
public class TrainingReadAdvice implements ResponseBodyAdvice<Object> {
    private final TrainingAccessGuard guard;
    private final CurrentOwner owner;

    public TrainingReadAdvice(TrainingAccessGuard guard, CurrentOwner owner) {
        this.guard = guard;
        this.owner = owner;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 读取或无副作用预览期间另一标签可能开始训练，序列化前再核对；写回执由事务内守卫处理。
        if (!(body instanceof ApiProblem) && request instanceof ServletServerHttpRequest servlet
                && ("GET".equals(servlet.getServletRequest().getMethod()) || body instanceof AiDtos.Preview
                    || body instanceof TodayPlanDtos.SuggestionPreview
                    || body instanceof SchedulePreparationDtos.ReschedulePlanPreview
                    || body instanceof GrowthDtos.UsePreview
                    || body instanceof StandardReviewDtos.StandardReviewPreview
                    || body instanceof ReviewedObservationDtos.ObservationPreview)) {
            TrainingAccessConfiguration.check(servlet.getServletRequest(), guard, owner);
        }
        return body;
    }
}
