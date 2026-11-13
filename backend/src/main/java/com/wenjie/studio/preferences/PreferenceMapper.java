package com.wenjie.studio.preferences;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import static com.wenjie.studio.preferences.PreferenceDtos.*;

@Mapper
public interface PreferenceMapper {
    Preferences find(@Param("owner") String owner);
    void insert(@Param("owner") String owner, @Param("value") Preferences value);
    int update(@Param("owner") String owner, @Param("expected") long expected, @Param("value") Preferences value);
    UsageCounts usage(@Param("owner") String owner);
}
