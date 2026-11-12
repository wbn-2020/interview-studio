package com.wenjie.studio.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import static com.wenjie.studio.knowledge.RelationSupplementRows.*;

@Mapper
public interface RelationSupplementMapper {
    RelationRow relation(@Param("owner") String owner, @Param("id") String id);
    RelationRow match(@Param("owner") String owner, @Param("sourceId") String sourceId,
                      @Param("targetId") String targetId, @Param("type") String type);
    SourceVersion sourceVersion(@Param("owner") String owner, @Param("itemId") String itemId,
                                @Param("versionId") String versionId);
    EntryRow entry(@Param("owner") String owner, @Param("id") String id);
    boolean hasHistory(@Param("owner") String owner, @Param("relationId") String relationId);
    List<EntryRow> entries(@Param("owner") String owner, @Param("relationId") String relationId,
                           @Param("cursor") String cursor, @Param("limit") int limit);
    List<SourceRow> sources(@Param("owner") String owner, @Param("supplementId") String supplementId);
    int insertEntry(@Param("row") EntryRow row);
    int insertSource(@Param("owner") String owner, @Param("supplementId") String supplementId,
                     @Param("ordinal") int ordinal, @Param("source") RelationSupplementDtos.Source source);
}
