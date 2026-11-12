package com.wenjie.studio.graph;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.graph.GraphDtos.Viewport;
import com.wenjie.studio.knowledge.KnowledgeDtos.Position;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

final class GraphRules {
    static final long MAX_REVISION = 9_007_199_254_740_991L;
    private GraphRules() {}

    static String uuid(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalid("对象标识必须为标准小写 UUID。");
        }
    }

    static void revision(Long value) {
        if (value == null || value < 0 || value > MAX_REVISION) throw invalid("布局修订号无效。");
    }

    static void positions(List<Position> values) {
        if (values == null || values.size() > 200) throw invalid("一次最多保存 200 个节点。");
        var ids = new HashSet<String>();
        for (Position value : values) {
            if (value == null || !ids.add(uuid(value.id()))) throw invalid("布局节点不能重复或为空。");
            coordinate(value.x());
            coordinate(value.y());
        }
    }

    static void viewport(Viewport value) {
        if (value == null) throw invalid("请提供星图视口。");
        coordinate(value.x());
        coordinate(value.y());
        if (value.zoom() == null || !Double.isFinite(value.zoom()) || value.zoom() < 0.05 || value.zoom() > 10) {
            throw invalid("视口缩放必须介于 0.05 和 10 之间。");
        }
    }

    private static void coordinate(Double value) {
        if (value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000) {
            throw invalid("坐标必须为 ±1000000 范围内的有限数字。");
        }
    }

    static ApiException invalid(String detail) { return new ApiException(400, "INVALID_REQUEST", detail); }
}
