package com.wenjie.studio.growth;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wenjie.studio.growth.GrowthDtos.*;

/** 分桶按显示时区，去重按领域真实身份；不据浏览时区重算回忆预算。 */
public final class GrowthActivitySupport {
    private static final Capability READY = new Capability(Availability.READY, CapabilityReason.NONE);

    private GrowthActivitySupport() {}

    public static Collector collector(Scope scope, Grain grain, Set<ActivityMetric> metrics) {
        return new Collector(scope, grain, metrics);
    }

    public static DomainActivityWindow unavailable(Scope scope, Grain grain, Set<ActivityMetric> metrics,
                                                    Capability capability) {
        GrowthRules.capability(capability);
        if (capability.status() == Availability.READY) throw GrowthRules.integrity();
        return collector(scope, grain, metrics).finish(false, capability);
    }

    public static final class Collector {
        private final Scope scope;
        private final EnumSet<ActivityMetric> metrics;
        private final Map<ActivityMetric, Set<String>> totals;
        private final List<Bucket> buckets = new ArrayList<>();
        private final ZoneId zone;

        private Collector(Scope scope, Grain grain, Set<ActivityMetric> metrics) {
            GrowthRules.scope(scope);
            GrowthRules.required(grain);
            GrowthRules.required(metrics);
            if (metrics.isEmpty() || scope.filter().targetMode() != TargetMode.RECORDED_ONLY) {
                throw GrowthRules.integrity();
            }
            this.scope = scope;
            this.metrics = EnumSet.copyOf(metrics);
            this.totals = identities();
            this.zone = ZoneId.of(scope.window().zoneId());
            var from = scope.window().from();
            var through = scope.window().to();
            while (!from.isAfter(through)) {
                LocalDate nominalStart = grain == Grain.DAY ? from
                        : from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate nominalEnd = grain == Grain.DAY ? from : nominalStart.plusDays(6);
                LocalDate to = nominalEnd.isAfter(through) ? through : nominalEnd;
                Instant startAt = from.atStartOfDay(zone).toInstant();
                Instant endAt = to.plusDays(1).atStartOfDay(zone).toInstant();
                PeriodState state = !startAt.isBefore(scope.window().observedUntil()) ? PeriodState.FUTURE
                        : endAt.isAfter(scope.window().observedUntil()) ? PeriodState.PARTIAL_TODAY : PeriodState.COMPLETE;
                buckets.add(new Bucket(from, to, startAt, endAt, state,
                        !from.equals(nominalStart) || !to.equals(nominalEnd), identities()));
                from = to.plusDays(1);
            }
        }

        public void add(ActivityMetric metric, String uniqueIdentity, Instant occurredAt) {
            if (!metrics.contains(metric) || uniqueIdentity == null || uniqueIdentity.isBlank()) {
                throw GrowthRules.integrity();
            }
            GrowthRules.time(occurredAt);
            var window = scope.window();
            if (occurredAt.isBefore(window.startAt()) || !occurredAt.isBefore(window.observedUntil())) return;
            LocalDate date = occurredAt.atZone(zone).toLocalDate();
            totals.get(metric).add(uniqueIdentity);
            for (var bucket : buckets) {
                if (!date.isBefore(bucket.from) && !date.isAfter(bucket.to)) {
                    bucket.identities.get(metric).add(uniqueIdentity);
                    return;
                }
            }
            throw GrowthRules.integrity();
        }

        public DomainActivityWindow finish(boolean scanLimited) { return finish(scanLimited, READY); }

        private DomainActivityWindow finish(boolean scanLimited, Capability capability) {
            boolean future = !scope.window().startAt().isBefore(scope.window().observedUntil());
            var values = values(totals, scanLimited, future, capability);
            var result = new ArrayList<ActivityBucket>(buckets.size());
            for (var bucket : buckets) result.add(new ActivityBucket(bucket.from, bucket.to, bucket.startAt,
                    bucket.endAt, bucket.state, bucket.clipped,
                    values(bucket.identities, scanLimited, bucket.state == PeriodState.FUTURE, capability)));
            return new DomainActivityWindow(values, List.copyOf(result), scanLimited);
        }

        private Map<ActivityMetric, Set<String>> identities() {
            var result = new EnumMap<ActivityMetric, Set<String>>(ActivityMetric.class);
            for (var metric : metrics) result.put(metric, new HashSet<>());
            return result;
        }

        private List<MetricValue> values(Map<ActivityMetric, Set<String>> identities, boolean limited,
                                         boolean future, Capability capability) {
            var result = new ArrayList<MetricValue>(metrics.size());
            for (var metric : metrics) {
                var unit = GrowthRules.metricUnit(metric);
                // 整段去重集合与各桶独立维护，同一题或卡跨日出现不能相加成总量。
                Count count = future || capability.status() != Availability.READY
                        ? new Count(null, CountMode.UNAVAILABLE)
                        : new Count((long) identities.get(metric).size(), limited ? CountMode.LOWER_BOUND : CountMode.EXACT);
                result.add(new MetricValue(metric, unit, capability, count,
                        unit != MetricUnit.DISTINCT_QUESTION && unit != MetricUnit.DISTINCT_CARD));
            }
            GrowthRules.domainMetrics(result);
            return List.copyOf(result);
        }
    }

    private record Bucket(LocalDate from, LocalDate to, Instant startAt, Instant endAt, PeriodState state,
                          boolean clipped, Map<ActivityMetric, Set<String>> identities) {}
}
