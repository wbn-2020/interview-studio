package com.wenjie.studio.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReviewDraftBindingMapper {
    ReviewDraftBindingRows.Binding binding(@Param("owner") String owner, @Param("taskId") String taskId);
    List<ReviewDraftBindingRows.Binding> bindings(@Param("owner") String owner, @Param("taskIds") List<String> taskIds);
    int insert(@Param("binding") ReviewDraftBindingRows.Binding binding);
}
