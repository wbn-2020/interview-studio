-- 同题复练沿用自身的确切题库来源或历史追问题面，不复制原训练的追问父链。
ALTER TABLE training_question
    DROP CHECK ck_training_question_origin,
    ADD CONSTRAINT ck_training_question_origin_v29 CHECK (
        (question_kind='MAIN' AND source_question_id IS NOT NULL AND source_version_id IS NOT NULL
            AND root_question_id IS NULL AND parent_question_id IS NULL AND parent_answer_id IS NULL AND ai_task_id IS NULL)
        OR (question_kind='FOLLOW_UP' AND source_question_id IS NULL AND source_version_id IS NULL
            AND root_question_id IS NOT NULL AND parent_question_id IS NOT NULL AND parent_answer_id IS NOT NULL
            AND ai_task_id IS NOT NULL AND id<>root_question_id AND id<>parent_question_id)
        OR (question_kind='REPLAY'
            AND ((source_question_id IS NULL AND source_version_id IS NULL)
                OR (source_question_id IS NOT NULL AND source_version_id IS NOT NULL))
            AND root_question_id IS NULL AND parent_question_id IS NULL AND parent_answer_id IS NULL AND ai_task_id IS NULL));
