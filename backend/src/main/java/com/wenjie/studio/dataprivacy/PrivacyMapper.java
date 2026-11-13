package com.wenjie.studio.dataprivacy;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PrivacyMapper {
    record Checkpoint(long ledgerSequence, String ledgerHash) { }
    record ObjectState(String objectKind, String objectId, String state, long revision,
                       String latestEventId, long ledgerSequence, String previousStatus,
                       LocalDateTime trashedAt, LocalDateTime updatedAt) { }

    Checkpoint checkpoint(@Param("owner") String owner);
    int insertCheckpoint(@Param("owner") String owner, @Param("sequence") long sequence,
                         @Param("hash") String hash, @Param("now") LocalDateTime now);
    int advanceCheckpoint(@Param("owner") String owner, @Param("expectedSequence") long expectedSequence,
                          @Param("expectedHash") String expectedHash, @Param("sequence") long sequence,
                          @Param("hash") String hash, @Param("now") LocalDateTime now);
    ObjectState objectState(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id);
    List<ObjectState> states(@Param("owner") String owner, @Param("state") String state,
                             @Param("afterKind") String afterKind, @Param("afterId") String afterId,
                             @Param("limit") int limit);
    void insertState(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id,
                     @Param("state") String state, @Param("eventId") String eventId,
                     @Param("sequence") long sequence, @Param("previousStatus") String previousStatus,
                     @Param("trashedAt") LocalDateTime trashedAt, @Param("now") LocalDateTime now);
    int changeState(@Param("owner") String owner, @Param("kind") String kind, @Param("id") String id,
                    @Param("expectedRevision") long expectedRevision, @Param("state") String state,
                    @Param("eventId") String eventId, @Param("sequence") long sequence,
                    @Param("previousStatus") String previousStatus, @Param("trashedAt") LocalDateTime trashedAt,
                    @Param("now") LocalDateTime now);
    long businessObjectCount(@Param("owner") String owner);
    List<String> unavailableIdentities(@Param("owner") String owner);
}
