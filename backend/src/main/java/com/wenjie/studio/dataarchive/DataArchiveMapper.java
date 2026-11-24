package com.wenjie.studio.dataarchive;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DataArchiveMapper {
    record Row(String id, String ownerId, String state, int formatVersion, Instant createdAt, Instant startedAt,
               Instant completedAt, Instant expiresAt, Long byteSize, String sha256, Long rowCount, Integer fileCount,
               String errorCode, Long ledgerSequence, String ledgerSha256) {}

    Row find(@Param("owner") String owner, @Param("id") String id);
    List<Row> list(@Param("owner") String owner);
    int retained(@Param("owner") String owner);
    int active(@Param("owner") String owner);
    void insert(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now, @Param("expires") Instant expires);
    Row queued(@Param("owner") String owner);
    int claim(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now);
    int ready(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now,
              @Param("bytes") long bytes, @Param("sha") String sha, @Param("rows") long rows, @Param("files") int files,
              @Param("sequence") long sequence, @Param("ledgerSha") String ledgerSha);
    int failed(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now, @Param("code") String code);
    int recover(@Param("owner") String owner, @Param("before") Instant before, @Param("now") Instant now);
    List<Row> cleanup(@Param("owner") String owner, @Param("now") Instant now, @Param("limit") int limit);
    int expire(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now);
    int deleteExpired(@Param("owner") String owner, @Param("id") String id);
}
