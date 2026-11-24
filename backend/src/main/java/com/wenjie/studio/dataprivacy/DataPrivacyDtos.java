package com.wenjie.studio.dataprivacy;

import java.time.LocalDateTime;
import java.util.List;

public final class DataPrivacyDtos {
    private DataPrivacyDtos() { }
    public record InitializeLedger(Boolean acknowledgeCurrentBaseline) { }
    public record SelectObject(String objectKind, String objectId) { }
    public record Command(String objectKind, String objectId, String previewDigest,
                          Boolean confirmWholeMixedDocuments) { }
    public record ObjectInfo(String objectKind, String objectId, String title,
                             String status, long revision, String impact) { }
    public record RowImpact(String table, int rows, List<String> fields, String handling) { }
    public record Preview(String action, String objectKind, String objectId, String digest,
                          List<ObjectInfo> objects, List<RowImpact> storage,
                          List<String> limitations, int ledgerRedactions) { }
    public record Result(String action, List<PrivacyLedger.Subject> objects,
                         long ledgerSequence, String ledgerHash, String state) { }
    public record TrashEntry(String objectKind, String objectId, String state, long revision,
                             String previousStatus, LocalDateTime trashedAt, LocalDateTime updatedAt) { }
    public record TrashPage(List<TrashEntry> items, String nextCursor) { }
    public record PrivacyObjectItem(PrivacyScope.PrivacyActionableKind objectKind, String objectId,
                                    String title, String subtitle, LocalDateTime updatedAt, String privacyState) { }
    public record PrivacyObjectPage(List<PrivacyObjectItem> items, String nextCursor) { }
}
