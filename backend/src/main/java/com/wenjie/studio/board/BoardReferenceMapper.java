package com.wenjie.studio.board;

import com.wenjie.studio.board.BoardDtos.Reference;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BoardReferenceMapper {
    List<Reference> questions(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<Reference> attachments(@Param("owner") String owner, @Param("ids") List<String> ids);
    int knowledgeVersion(@Param("owner") String owner, @Param("id") String id, @Param("version") String version);
    int questionVersion(@Param("owner") String owner, @Param("id") String id, @Param("version") String version);
    int attachmentRevision(@Param("owner") String owner, @Param("id") String id, @Param("revision") long revision);
    void insert(@Param("owner") String owner, @Param("boardId") String boardId,
                @Param("revision") long revision, @Param("reference") Reference reference);
}
