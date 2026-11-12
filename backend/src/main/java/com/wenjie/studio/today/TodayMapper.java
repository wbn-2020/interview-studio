package com.wenjie.studio.today;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static com.wenjie.studio.today.TodayRows.*;

/** 跨域只读投影；源对象的所有命令仍由原领域执行。 */
@Mapper
public interface TodayMapper {
    boolean targetExists(@Param("owner") String owner, @Param("id") String id);
    boolean sourceExists(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id);
    KnowledgeRow knowledge(@Param("owner") String owner, @Param("id") String id);
    List<KnowledgeRow> knowledgePage(@Param("q") Query query);
    TrainingRow training(@Param("owner") String owner, @Param("id") String id);
    List<TrainingRow> trainingPage(@Param("q") Query query);
    List<QuestionRow> trainingQuestions(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<TargetRow> trainingTargets(@Param("owner") String owner, @Param("ids") List<String> ids);
    TaskRow task(@Param("owner") String owner, @Param("id") String id);
    List<TaskRow> taskPage(@Param("q") Query query);
    ActionRow action(@Param("owner") String owner, @Param("id") String id);
    List<ActionRow> actionPage(@Param("q") Query query);
    InterviewRow interview(@Param("owner") String owner, @Param("id") String id);
    List<InterviewRow> interviewPage(@Param("q") Query query);
}
