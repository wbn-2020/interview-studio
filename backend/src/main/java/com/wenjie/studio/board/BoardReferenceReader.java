package com.wenjie.studio.board;

import com.wenjie.studio.board.BoardDtos.*;
import com.wenjie.studio.common.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BoardReferenceReader {
    private final BoardMapper knowledge;
    private final BoardReferenceMapper external;

    public BoardReferenceReader(BoardMapper knowledge, BoardReferenceMapper external) {
        this.knowledge = knowledge;
        this.external = external;
    }

    public List<Reference> current(String owner, List<Node> nodes) {
        List<Reference> result = new ArrayList<>();
        for (ReferenceKind kind : ReferenceKind.values()) {
            List<String> ids = nodes.stream().filter(node -> node.type() == NodeType.REFERENCE
                            && node.referenceKind() == kind).map(Node::itemId).distinct().sorted().toList();
            if (ids.isEmpty()) continue;
            List<Reference> values = switch (kind) {
                case KNOWLEDGE -> knowledge.references(owner, ids);
                case QUESTION -> external.questions(owner, ids);
                case ATTACHMENT -> external.attachments(owner, ids);
            };
            if (values.size() != ids.size()) throw unavailable();
            result.addAll(values);
        }
        return List.copyOf(result);
    }

    public void requireHistoricalAccess(String owner, Detail board) {
        Map<String, Reference> references = new HashMap<>();
        for (Reference reference : board.references()) {
            if (reference == null || references.put(key(reference.referenceKind(), reference.itemId()), reference) != null) {
                throw unavailable();
            }
        }
        var checked = new HashSet<String>();
        for (Node node : board.nodes()) {
            if (node.type() != NodeType.REFERENCE) continue;
            String key = key(node.referenceKind(), node.itemId());
            if (!checked.add(key)) continue;
            Reference reference = references.get(key);
            if (reference == null) throw unavailable();
            // 历史只返回原快照，当前检查仅决定能否读取，不能用新版本替换旧证据。
            boolean accessible = switch (reference.referenceKind()) {
                case KNOWLEDGE -> reference.versionId() != null
                        && external.knowledgeVersion(owner, reference.itemId(), reference.versionId()) == 1;
                case QUESTION -> reference.versionId() != null
                        && external.questionVersion(owner, reference.itemId(), reference.versionId()) == 1;
                case ATTACHMENT -> reference.attachmentRevision() != null
                        && external.attachmentRevision(owner, reference.itemId(), reference.attachmentRevision()) == 1;
            };
            if (!accessible) throw unavailable();
        }
        if (checked.size() != references.size()) throw unavailable();
    }

    public void record(String owner, Detail board) {
        for (Reference reference : board.references()) {
            if (reference.referenceKind() == ReferenceKind.KNOWLEDGE) {
                knowledge.insertReference(owner, board.id(), board.revision(), reference);
            } else {
                external.insert(owner, board.id(), board.revision(), reference);
            }
        }
    }

    private static String key(ReferenceKind kind, String id) { return kind + ":" + id; }
    private static ApiException unavailable() {
        return new ApiException(409, "REFERENCE_UNAVAILABLE",
                "画布包含不存在或不可访问的知识、题目或附件引用，请保留本地内容并核对来源。");
    }
}
