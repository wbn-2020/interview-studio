package com.wenjie.studio.growth;

import com.wenjie.studio.common.CurrentOwner;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.wenjie.studio.growth.GrowthDtos.*;
import static com.wenjie.studio.growth.GrowthRules.*;

@RestController
@RequestMapping(value = "/api/v1/growth", produces = MediaType.APPLICATION_JSON_VALUE)
public class GrowthController {
    private final CurrentOwner owner;
    private final GrowthQueryService queries;
    private final GrowthUseService uses;
    private final GrowthPreferenceService preferences;
    private final GrowthNextStepsService nextSteps;

    public GrowthController(CurrentOwner owner, GrowthQueryService queries, GrowthUseService uses,
                            GrowthPreferenceService preferences, GrowthNextStepsService nextSteps) {
        this.owner = owner; this.queries = queries; this.uses = uses;
        this.preferences = preferences; this.nextSteps = nextSteps;
    }

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview(@RequestParam MultiValueMap<String, String> query) {
        return response(queries.overview(owner.id(), overviewQuery(query)));
    }

    @GetMapping("/evidence")
    public ResponseEntity<EvidencePage> evidence(@RequestParam MultiValueMap<String, String> query) {
        return response(queries.evidence(owner.id(), evidenceQuery(query)));
    }

    @GetMapping("/evidence/ref")
    public ResponseEntity<EvidenceResolution> evidenceRef(@RequestParam MultiValueMap<String, String> query) {
        return response(queries.evidenceRef(owner.id(), evidenceQueryRef(query)));
    }

    @GetMapping("/activity")
    public ResponseEntity<ActivitySeries> activity(@RequestParam MultiValueMap<String, String> query) {
        return response(queries.activity(owner.id(), activityQuery(query)));
    }

    @GetMapping("/source-progress")
    public ResponseEntity<SourceProgressPage> progress(@RequestParam MultiValueMap<String, String> query) {
        return response(queries.sourceProgress(owner.id(), sourceProgressQuery(query)));
    }

    @PostMapping(value = "/target-evidence/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UsePreview> preview(@RequestBody byte[] raw) {
        return response(uses.preview(owner.id(), decode(raw, CreateUsePreview.class)));
    }

    @PostMapping(value = "/target-evidence/{id}/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UsePreview> previewChange(@PathVariable String id, @RequestBody byte[] raw) {
        return response(uses.previewChange(owner.id(), id, decode(raw, ChangeUsePreview.class)));
    }

    @PostMapping(value = "/target-evidence", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EvidenceUse> create(@RequestHeader("Idempotency-Key") String key, @RequestBody byte[] raw) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(uses.create(owner.id(), key, decode(raw, CreateUse.class)));
    }

    @PutMapping(value = "/target-evidence/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EvidenceUse> change(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                              @RequestBody byte[] raw) {
        return response(uses.change(owner.id(), id, key, decode(raw, ChangeUse.class)));
    }

    @GetMapping("/target-evidence/by-identity")
    public ResponseEntity<UseIdentityLookup> useByIdentity(@RequestParam MultiValueMap<String, String> query) {
        return response(uses.byIdentity(owner.id(), useIdentityQuery(query)));
    }

    @GetMapping("/target-evidence/{id}")
    public ResponseEntity<UseLookup> use(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
        parameters(query, java.util.Set.of());
        return response(uses.get(owner.id(), id));
    }

    @GetMapping("/target-evidence")
    public ResponseEntity<UsePage> useDirectory(@RequestParam MultiValueMap<String, String> query) {
        return response(uses.directory(owner.id(), useDirectoryQuery(query)));
    }

    @GetMapping("/target-evidence/{id}/history")
    public ResponseEntity<UseHistoryPage> useHistory(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
        var history = historyQuery(query);
        return response(uses.history(owner.id(), id, history.before(), history.limit()));
    }

    @GetMapping("/target-coverage")
    public ResponseEntity<TargetCoverage> coverage(@RequestParam MultiValueMap<String, String> query) {
        return response(uses.coverage(owner.id(), targetQuery(query)));
    }

    @GetMapping("/next-steps")
    public ResponseEntity<NextSteps> nextSteps(@RequestParam MultiValueMap<String, String> query) {
        return response(nextSteps.nextSteps(owner.id(), nextStepsQuery(query)));
    }

    @PostMapping(value = "/next-step-preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NextStepPreference> createPreference(@RequestHeader("Idempotency-Key") String key,
                                                              @RequestBody byte[] raw) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(preferences.create(owner.id(), key, decode(raw, CreatePreference.class)));
    }

    @PutMapping(value = "/next-step-preferences/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NextStepPreference> changePreference(@PathVariable String id,
                                                              @RequestHeader("Idempotency-Key") String key,
                                                              @RequestBody byte[] raw) {
        return response(preferences.change(owner.id(), id, key, decode(raw, SavePreference.class)));
    }

    @GetMapping("/next-step-preferences/by-subject")
    public ResponseEntity<PreferenceSubjectLookup> preferenceBySubject(@RequestParam MultiValueMap<String, String> query) {
        return response(preferences.bySubject(owner.id(), preferenceSubjectQuery(query)));
    }

    @GetMapping("/next-step-preferences/{id}")
    public ResponseEntity<PreferenceLookup> preference(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
        parameters(query, java.util.Set.of());
        return response(preferences.get(owner.id(), id));
    }

    @GetMapping("/next-step-preferences")
    public ResponseEntity<PreferencePage> preferenceDirectory(@RequestParam MultiValueMap<String, String> query) {
        return response(preferences.directory(owner.id(), preferenceQuery(query)));
    }

    @GetMapping("/next-step-preferences/{id}/history")
    public ResponseEntity<PreferenceHistoryPage> preferenceHistory(@PathVariable String id,
                                                                 @RequestParam MultiValueMap<String, String> query) {
        var history = historyQuery(query);
        return response(preferences.history(owner.id(), id, history.before(), history.limit()));
    }

    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
