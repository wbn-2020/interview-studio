package com.wenjie.studio.prepare;

import com.wenjie.studio.material.MaterialLifecycleService;
import com.wenjie.studio.prepare.PreparationDtos.AiVersion;
import com.wenjie.studio.prepare.PreparationDtos.ExpressionCheck;
import com.wenjie.studio.prepare.PreparationDtos.ExpressionRead;
import com.wenjie.studio.prepare.PreparationDtos.Kind;
import com.wenjie.studio.prepare.PreparationDtos.SourceCheck;
import com.wenjie.studio.prepare.PreparationDtos.SourceState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PreparationSources {
    private final PreparationMapper mapper;
    private final PreparationMaterialEligibilityMapper materialEligibility;
    private final MaterialLifecycleService materialLifecycle;
    private final JsonMapper json;

    public PreparationSources(PreparationMapper mapper, JsonMapper json,
                              PreparationMaterialEligibilityMapper materialEligibility,
                              MaterialLifecycleService materialLifecycle) {
        this.mapper = mapper;
        this.materialEligibility = materialEligibility;
        this.materialLifecycle = materialLifecycle;
        this.json = json.rebuild().disable(SerializationFeature.INDENT_OUTPUT).build();
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RuntimeException.class)
    public SourceCheck check(String owner, Kind kind, String itemId, String versionId) {
        if (!uuid(owner) || !uuid(itemId) || !uuid(versionId) || kind == null || kind == Kind.EXPRESSION) {
            return unavailable(kind, itemId, versionId);
        }
        // 独立训练只核对固定来源的状态与代次，不能顺带读取新版正文或扩展关联材料。
        SourceCheck checked = mapper.sourceCheck(owner, kind.name(), itemId, versionId);
        return checked == null ? unavailable(kind, itemId, versionId) : checked;
    }

    public AiVersion read(String owner, Kind kind, String itemId, String versionId) {
        SourceCheck checked = check(owner, kind, itemId, versionId);
        if (checked.state() != SourceState.CURRENT || checked.sourceGeneration() == null
                || (kind == Kind.FACT && checked.projectGeneration() == null)
                || !aiAllowed(owner, kind, itemId, versionId)) return null;
        var version = mapper.version(owner, itemId, versionId);
        if (version == null) return null;
        JsonNode content = json.readTree(version.contentJson());
        if (content == null || !content.isObject()) throw incompleteContent();
        if (kind == Kind.FACT) {
            JsonNode responsibility = content.get("responsibility");
            if (responsibility == null || !responsibility.isTextual()
                    || !List.of("OWNER", "PARTICIPANT", "FAMILIAR").contains(responsibility.stringValue())) return null;
        }

        // 只投影显式选择版本的白名单；字段顺序和原值共同确定授权摘要及引用区间。
        var projected = json.createObjectNode();
        List<String> fields = switch (kind) {
            case TARGET -> List.of("company", "jd");
            case PROJECT -> List.of("overview", "technology");
            case FACT -> List.of("scenario", "problem", "action", "result", "technology", "responsibility");
            case EXPRESSION -> throw new IllegalStateException("表达素材不属于准备来源读取范围");
        };
        for (String field : fields) projected.set(field, textNode(content, field).deepCopy());
        if (kind == Kind.TARGET) {
            JsonNode requirements = content.get("requirements");
            if (requirements == null || !requirements.isArray()) throw incompleteContent();
            var projectedRequirements = projected.putArray("requirements");
            for (JsonNode requirement : requirements) {
                var item = projectedRequirements.addObject();
                item.set("text", textNode(requirement, "text").deepCopy());
                JsonNode dimensions = requirement.get("dimensions"), priority = requirement.get("priority");
                if (dimensions == null || !dimensions.isArray() || priority == null || !priority.isIntegralNumber()) {
                    throw incompleteContent();
                }
                for (JsonNode dimension : dimensions) if (!dimension.isTextual()) throw incompleteContent();
                item.set("dimensions", dimensions.deepCopy());
                item.set("priority", priority.deepCopy());
            }
        }
        return new AiVersion(version.itemId(), version.id(), version.versionNumber(), kind,
                version.title(), json.writeValueAsString(projected), checked);
    }

    public AiVersion readJd(String owner, String itemId, String versionId) {
        SourceCheck checked = check(owner, Kind.TARGET, itemId, versionId);
        if (!currentWithGeneration(checked) || !aiAllowed(owner, Kind.TARGET, itemId, versionId)) return null;
        var version = mapper.version(owner, itemId, versionId);
        if (version == null) return null;
        JsonNode content = json.readTree(version.contentJson());
        if (content == null || !content.isObject()) throw incompleteContent();
        // JD 引用区间以原文字面值为准，外发不能夹带公司备注、既有要求或组织链接。
        return new AiVersion(version.itemId(), version.id(), version.versionNumber(), Kind.TARGET,
                version.title(), textNode(content, "jd").stringValue(), checked);
    }

    public ExpressionCheck checkExpression(String owner, String itemId, String versionId) {
        if (!uuid(owner) || !uuid(itemId) || !uuid(versionId)) {
            return new ExpressionCheck(unavailable(Kind.EXPRESSION, itemId, versionId), List.of());
        }
        SourceCheck expression = mapper.expressionSourceCheck(owner, itemId, versionId);
        if (expression == null) {
            return new ExpressionCheck(unavailable(Kind.EXPRESSION, itemId, versionId), List.of());
        }
        if (expression.state() != SourceState.CURRENT) return new ExpressionCheck(expression, List.of());
        return new ExpressionCheck(expression, expressionBasis(owner, itemId, versionId).stream()
                .map(ExpressionBasisReviewDtos.Basis::check).toList());
    }

    public List<ExpressionBasisReviewDtos.Basis> expressionBasis(String owner, String itemId, String versionId) {
        // 当前资格可能因缺少基线而为 UNVERIFIED；人工确认仍须读到原正文与关系共同证明的真实事实身份。
        if (!uuid(owner) || !uuid(itemId) || !uuid(versionId)) throw inconsistentExpressionBasis();
        JsonNode content = expressionContent(mapper.version(owner, itemId, versionId));
        JsonNode facts = content.get("factVersionIds");
        if (facts == null || !facts.isArray() || facts.size() < 1 || facts.size() > 50) throw inconsistentExpressionBasis();
        var factVersions = new ArrayList<String>();
        var seenVersions = new HashSet<String>();
        for (JsonNode fact : facts) {
            if (!fact.isTextual() || !uuid(fact.stringValue()) || !seenVersions.add(fact.stringValue())) {
                throw inconsistentExpressionBasis();
            }
            factVersions.add(fact.stringValue());
        }

        // 两份依据必须完全一致；不能用空关系或忽略异常关系得到“无需复核”。
        var rows = mapper.expressionBasis(owner, itemId, versionId);
        if (rows.size() != factVersions.size()) throw inconsistentExpressionBasis();
        var byVersion = new HashMap<String, PreparationRows.ExpressionBasisRow>();
        for (var row : rows) {
            if (!"FACT".equals(row.sourceKind()) || !"FACT".equals(row.itemKind())
                    || !"FACT_BASIS".equals(row.useType()) || !uuid(row.sourceItemId())
                    || !uuid(row.sourceVersionId()) || !row.sourceItemId().equals(row.versionItemId())
                    || row.title() == null || row.versionNumber() == null || row.versionNumber() < 1 || !uuid(row.projectId())
                    || !seenVersions.contains(row.sourceVersionId())
                    || byVersion.put(row.sourceVersionId(), row) != null) throw inconsistentExpressionBasis();
        }
        var basis = new ArrayList<ExpressionBasisReviewDtos.Basis>();
        // 代次只用于本地授权核对，按原正文中的顺序保存，不读取事实正文。
        for (String factVersion : factVersions) {
            var row = byVersion.get(factVersion);
            if (row == null) throw inconsistentExpressionBasis();
            basis.add(new ExpressionBasisReviewDtos.Basis(row.sourceItemId(), factVersion, row.title(),
                    row.versionNumber(), row.projectId(), check(owner, Kind.FACT, row.sourceItemId(), factVersion)));
        }
        return List.copyOf(basis);
    }

    public ExpressionRead readExpression(String owner, String itemId, String versionId) {
        ExpressionCheck checked = checkExpression(owner, itemId, versionId);
        if (!currentWithGeneration(checked.expression())
                || checked.factChecks().stream().anyMatch(fact -> !currentWithGeneration(fact))
                || !aiAllowed(owner, Kind.EXPRESSION, itemId, versionId)) return null;
        var version = mapper.version(owner, itemId, versionId);
        JsonNode content = expressionContent(version);
        JsonNode type = textNode(content, "type"), body = textNode(content, "body");
        if (!List.of("INTRO", "PROJECT_SHORT", "PROJECT_LONG", "STAR", "TRADEOFF").contains(type.stringValue())
                || !validExpressionText(version.title(), 200) || !validExpressionText(body.stringValue(), 50000)) return null;
        var projected = json.createObjectNode();
        projected.set("type", type.deepCopy());
        projected.set("body", body.deepCopy());
        return new ExpressionRead(new AiVersion(version.itemId(), version.id(), version.versionNumber(), Kind.EXPRESSION,
                version.title(), json.writeValueAsString(projected), checked.expression()), checked);
    }

    public boolean aiAllowed(String owner, Kind kind, String itemId, String versionId) {
        if (!uuid(owner) || kind == null || !uuid(itemId) || !uuid(versionId)) return false;
        var source = materialEligibility.source(owner, kind.name(), itemId, versionId);
        if (source == null) return false;
        if (!itemId.equals(source.itemId()) || !kind.name().equals(source.kind())
                || !versionId.equals(source.versionId())) throw inconsistentMaterialBasis();
        if (kind == Kind.EXPRESSION) {
            // 仅核原保存的全部事实身份及材料许可，不把这些依据正文展开到外发清单。
            for (var basis : expressionBasis(owner, itemId, versionId)) {
                if (!aiAllowed(owner, Kind.FACT, basis.itemId(), basis.versionId())) return false;
            }
            return true;
        }
        var excerpts = materialEligibility.excerpts(owner, itemId, versionId);
        if (excerpts == null || excerpts.size() > 20 || new HashSet<>(excerpts).size() != excerpts.size()
                || excerpts.stream().anyMatch(id -> !uuid(id))) throw inconsistentMaterialBasis();
        for (String excerptId : excerpts) if (!materialLifecycle.aiAllowed(owner, excerptId)) return false;
        if (kind != Kind.FACT) return true;
        return uuid(source.projectId()) && uuid(source.projectVersionId())
                && aiAllowed(owner, Kind.PROJECT, source.projectId(), source.projectVersionId());
    }

    private JsonNode expressionContent(PreparationRows.VersionRow version) {
        if (version == null) throw incompleteContent();
        JsonNode content = json.readTree(version.contentJson());
        if (content == null || !content.isObject()) throw incompleteContent();
        return content;
    }

    private static boolean currentWithGeneration(SourceCheck checked) {
        return checked.state() == SourceState.CURRENT && validGeneration(checked.sourceGeneration())
                && (checked.kind() == Kind.FACT ? validGeneration(checked.projectGeneration()) : checked.projectGeneration() == null);
    }

    private static boolean validGeneration(Long value) {
        return value != null && value >= 0 && value <= 9_007_199_254_740_991L;
    }

    private static boolean validExpressionText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }

    private static IllegalStateException inconsistentExpressionBasis() {
        return new IllegalStateException("表达版本的事实依据与保存关系不一致");
    }

    private static JsonNode textNode(JsonNode content, String field) {
        JsonNode value = content.get(field);
        if (value == null || !value.isTextual()) throw incompleteContent();
        return value;
    }

    private static IllegalStateException incompleteContent() {
        return new IllegalStateException("准备版本正文结构不完整，无法生成外发快照");
    }

    private static IllegalStateException inconsistentMaterialBasis() {
        return new IllegalStateException("准备版本的材料依据身份或数量不一致");
    }

    private static SourceCheck unavailable(Kind kind, String itemId, String versionId) {
        return new SourceCheck(kind, itemId, versionId, SourceState.UNAVAILABLE, null, null);
    }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
