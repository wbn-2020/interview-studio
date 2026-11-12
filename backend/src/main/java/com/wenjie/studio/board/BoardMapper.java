package com.wenjie.studio.board;

import com.wenjie.studio.board.BoardDtos.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BoardMapper {
    record Stored(String id, String kind, String title, long revision, Instant updatedAt, String snapshotJson) {}
    List<Summary> list(@Param("owner") String owner, @Param("cursor") String cursor, @Param("limit") int limit);
    Stored find(@Param("owner") String owner, @Param("id") String id);
    String snapshot(@Param("owner") String owner, @Param("id") String id, @Param("revision") long revision);
    List<HistoryEntry> history(@Param("owner") String owner, @Param("id") String id,
                              @Param("before") Long before, @Param("limit") int limit);
    List<Reference> references(@Param("owner") String owner, @Param("ids") List<String> ids);
    void insert(@Param("owner") String owner, @Param("board") Detail board, @Param("json") String json);
    int update(@Param("owner") String owner, @Param("board") Detail board,
               @Param("expected") long expected, @Param("json") String json);
    void insertHistory(@Param("owner") String owner, @Param("board") Detail board, @Param("json") String json);
    void insertReference(@Param("owner") String owner, @Param("boardId") String boardId,
                         @Param("revision") long revision, @Param("reference") Reference reference);
}
