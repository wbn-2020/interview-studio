package com.wenjie.studio.dataprivacy;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

/** 资料展示和模型输入共享无正文身份守卫；不会靠替换 DTO 中的可读状态重新授予访问。 */
@Component
public final class PrivacyAccess {
    private final PrivacyMapper mapper;
    private final JsonMapper json;

    public PrivacyAccess(PrivacyMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public void requireInput(String owner, Object value) {
        JsonNode tree;
        if (value instanceof String text) {
            try { tree = json.readTree(text); }
            catch (RuntimeException ignored) { tree = json.valueToTree(text); }
        } else tree = json.valueToTree(value);
        if (contains(tree, identities(owner))) throw unavailable();
    }

    public Object filterResponse(String owner, Object value) {
        Set<String> blocked = identities(owner);
        if (blocked.isEmpty() || value == null) return value;
        JsonNode tree = json.valueToTree(value);
        if (ownIdentity(tree, blocked)) throw unavailable();
        return filter(tree, blocked);
    }

    public Object filterResponse(String owner, Object value, String path) {
        return filterResponse(owner, value, null, path);
    }

    public Object filterResponse(String owner, Object value, String method, String path) {
        if (PrivacyPermissionMetadata.isSafeResponse(method, path, value)) return value;
        if (value != null && path != null && path.startsWith("/api/v1/ai/")) {
            // AI 回答与引文是同一混合结果，不能只移除某条引文后继续展示其旁边的改写正文。
            requireInput(owner, value);
        }
        return filterResponse(owner, value);
    }

    public Set<String> identities(String owner) { return new HashSet<>(mapper.unavailableIdentities(owner)); }

    private static boolean contains(JsonNode value, Set<String> ids) {
        if (value == null) return false;
        if (value.isTextual()) return ids.contains(value.asText());
        if (value.isArray() || value.isObject()) for (JsonNode child : value) if (contains(child, ids)) return true;
        return false;
    }

    private static boolean ownIdentity(JsonNode value, Set<String> ids) {
        if (value == null || !value.isObject()) return false;
        for (String field : Set.of("id", "itemId", "cardId", "taskId", "attachmentId", "feedbackId",
                "recollectionId", "sessionId", "versionId", "targetVersionId", "excerptId", "collectionId",
                "actionId", "eventId")) {
            JsonNode id = value.get(field);
            if (id != null && id.isTextual() && ids.contains(id.asText())) return true;
        }
        return false;
    }

    private JsonNode filter(JsonNode value, Set<String> ids) {
        if (value == null || !(value.isArray() || value.isObject())) return value;
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            for (JsonNode child : value) {
                if (ownIdentity(child, ids) || structuralEdge(child, ids)) continue;
                result.add(filter(child, ids));
            }
            return result;
        }
        ObjectNode result = json.createObjectNode();
        for (var property : value.properties()) {
            JsonNode child = property.getValue();
            // 固定 DTO 中的对象不能替换成未知结构；完整响应拒绝后由客户端按 410 重新载入。
            if (ownIdentity(child, ids) || structuralEdge(child, ids)) throw unavailable();
            result.set(property.getKey(), filter(child, ids));
        }
        return result;
    }

    private static boolean structuralEdge(JsonNode value, Set<String> ids) {
        if (value == null || !value.isObject()) return false;
        for (String field : Set.of("sourceId", "targetId", "sourceItemId", "targetItemId",
                "knowledgeItemId", "knowledgeVersionId", "sourceVersionId", "preparationItemId")) {
            JsonNode id = value.get(field);
            if (id != null && id.isTextual() && ids.contains(id.asText())) return true;
        }
        return false;
    }

    private static ApiException unavailable() {
        return new ApiException(410, "PRIVACY_OBJECT_UNAVAILABLE",
                "所需内容已移入回收站、被清除或撤权。请刷新来源清单，不得沿用旧正文或历史回执。");
    }
}
