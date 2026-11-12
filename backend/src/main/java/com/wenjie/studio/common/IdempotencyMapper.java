package com.wenjie.studio.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface IdempotencyMapper {
    void ensureWorkspace(@Param("ownerId") String ownerId);

    String lockWorkspace(@Param("ownerId") String ownerId);

    Receipt findReceipt(@Param("ownerId") String ownerId, @Param("key") String key);

    void insertReceipt(@Param("ownerId") String ownerId, @Param("key") String key,
                       @Param("operation") String operation, @Param("requestHash") String requestHash,
                       @Param("responseJson") String responseJson, @Param("createdAt") LocalDateTime createdAt);

    record Receipt(String operation, String requestHash, String responseJson) { }
}
