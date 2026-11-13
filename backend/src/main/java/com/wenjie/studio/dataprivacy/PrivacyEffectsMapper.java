package com.wenjie.studio.dataprivacy;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PrivacyEffectsMapper {
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM ai_task WHERE owner_id=#{owner} AND status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED'))+
              (SELECT COUNT(*) FROM material_upload WHERE owner_id=#{owner} AND status='RECEIVING')+
              (SELECT COUNT(*) FROM material_parse_run WHERE owner_id=#{owner} AND status IN ('QUEUED','RUNNING'))+
              (SELECT COUNT(*) FROM data_archive_job WHERE owner_id=#{owner} AND state IN ('QUEUED','RUNNING'))
            """)
    long activeWork(@Param("owner") String owner);

    @Select("""
            SELECT COUNT(*) FROM knowledge_attachment_reference r JOIN knowledge_item i
              ON i.owner_id=r.owner_id AND i.id=r.item_id AND i.current_version_id=r.version_id
            WHERE r.owner_id=#{owner} AND r.attachment_id=#{attachment} AND i.status='ACTIVE'
              AND NOT EXISTS(SELECT 1 FROM privacy_object_state s
                WHERE s.owner_id=i.owner_id AND s.object_kind='KNOWLEDGE' AND s.object_id=i.id AND s.state<>'AVAILABLE')
            """)
    long sharedAttachmentNotes(@Param("owner") String owner, @Param("attachment") String attachment);

    @Select("""
            SELECT COUNT(*) FROM knowledge_board_external_reference r JOIN knowledge_board b
              ON b.owner_id=r.owner_id AND b.id=r.board_id AND b.revision=r.board_revision
            WHERE r.owner_id=#{owner} AND r.reference_kind='ATTACHMENT' AND r.item_id=#{attachment}
              AND NOT EXISTS(SELECT 1 FROM privacy_object_state s
                WHERE s.owner_id=b.owner_id AND s.object_kind='BOARD' AND s.object_id=b.id AND s.state<>'AVAILABLE')
            """)
    long sharedAttachmentBoards(@Param("owner") String owner, @Param("attachment") String attachment);

    @Select("SELECT id FROM material_upload WHERE owner_id=#{owner} AND attachment_id=#{attachment} ORDER BY id")
    List<String> uploads(@Param("owner") String owner, @Param("attachment") String attachment);
    @Select("SELECT id FROM material_parse_run WHERE owner_id=#{owner} AND attachment_id=#{attachment} ORDER BY id")
    List<String> parseRuns(@Param("owner") String owner, @Param("attachment") String attachment);
    @Select("SELECT id FROM data_archive_job WHERE owner_id=#{owner} ORDER BY id")
    List<String> exports(@Param("owner") String owner);
}
