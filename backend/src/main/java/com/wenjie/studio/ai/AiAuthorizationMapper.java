package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AiAuthorizationMapper {
    AiAuthorizationRows.EntryRow entry(@Param("owner") String owner, @Param("id") String id);
    List<AiAuthorizationRows.EntryRow> entries(@Param("owner") String owner, @Param("cursor") String cursor,
                                             @Param("limit") int limit);
    int activeCount(@Param("owner") String owner, @Param("epoch") String epoch, @Param("now") Instant now);
    List<AiAuthorizationRows.EntryRow> matching(@Param("owner") String owner, @Param("purpose") String purpose,
                                              @Param("scopeHash") String scopeHash, @Param("fingerprint") String fingerprint,
                                              @Param("epoch") String epoch, @Param("now") Instant now);
    int insertEntry(@Param("entry") AiAuthorizationRows.EntryRow entry);
    int revoke(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
               @Param("revokedAt") Instant revokedAt);
}
