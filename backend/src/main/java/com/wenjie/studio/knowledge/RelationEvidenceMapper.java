package com.wenjie.studio.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static com.wenjie.studio.knowledge.RelationEvidenceRows.*;

@Mapper
public interface RelationEvidenceMapper {
    RelationSupplementRows.RelationRow relation(@Param("owner") String owner, @Param("id") String id);
    EntryRow entry(@Param("owner") String owner, @Param("id") String id);
    boolean hasHistory(@Param("owner") String owner, @Param("relationId") String relationId);
    List<EntryRow> entries(@Param("owner") String owner, @Param("relationId") String relationId,
                           @Param("cursor") String cursor, @Param("limit") int limit);
    SnapshotRow snapshot(@Param("owner") String owner, @Param("id") String id);
    List<MaterialBindingRow> materials(@Param("owner") String owner, @Param("evidenceId") String evidenceId);
    int insertEntry(@Param("row") EntryRow row, @Param("snapshotJson") String snapshotJson);
    int insertMaterial(@Param("row") MaterialBindingRow row);
}
