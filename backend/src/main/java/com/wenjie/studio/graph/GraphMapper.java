package com.wenjie.studio.graph;

import com.wenjie.studio.graph.GraphDtos.*;
import com.wenjie.studio.knowledge.KnowledgeDtos.Relation;
import com.wenjie.studio.knowledge.KnowledgeRows.Node;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface GraphMapper {
    Viewport viewport(@Param("owner") String owner);
    void saveViewport(@Param("owner") String owner, @Param("viewport") Viewport viewport);
    List<Node> activeNodes(@Param("owner") String owner, @Param("ids") List<String> ids);
    List<Relation> activeRelations(@Param("owner") String owner, @Param("type") String type,
                                   @Param("limit") int limit);
    void insertHistory(@Param("owner") String owner, @Param("revision") long revision,
                       @Param("beforeJson") String beforeJson, @Param("afterJson") String afterJson,
                       @Param("positionCount") int positionCount, @Param("now") Instant now);
    List<HistoryEntry> history(@Param("owner") String owner, @Param("before") Long before,
                               @Param("limit") int limit);
    HistoryRow historyEntry(@Param("owner") String owner, @Param("revision") long revision);
}
