import { createHash } from 'node:crypto';

export const MIXED_OPERATIONS = Object.freeze(['t1', 't2', 't5']);
export const MIXED_TIERS = Object.freeze(['hot', 'spread']);
export const MIXED_OUTCOMES = Object.freeze([
  'success',
  'business',
  'concurrency',
  'unexpected',
]);

const MIXED_TIME_UNIT = '1s';
const MAX_TARGET_ARRIVALS = 10_000;

function fail(message) {
  throw new Error(message);
}

function integer(value, name, minimum, maximum) {
  const parsed = typeof value === 'number'
    ? value
    : typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value.trim())
      ? Number(value.trim())
      : Number.NaN;
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    fail(`${name}은(는) ${minimum} 이상 ${maximum} 이하의 정수여야 합니다.`);
  }
  return parsed;
}

function percentage(value, name) {
  return integer(value, name, 0, 100);
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function metricValues(metric) {
  if (!metric || typeof metric !== 'object' || Array.isArray(metric)) {
    return null;
  }
  if (metric.values && typeof metric.values === 'object' && !Array.isArray(metric.values)) {
    return metric.values;
  }
  return metric;
}

function metricCount(metric) {
  const count = metricValues(metric)?.count;
  return Number.isSafeInteger(count) && count >= 0 ? count : null;
}

function metricStatistic(values, names) {
  for (const name of names) {
    if (values?.[name] !== undefined) {
      return values[name];
    }
  }
  return null;
}

function parseTags(metricName, metricPrefix) {
  if (metricName === metricPrefix) {
    return {};
  }
  const match = new RegExp(`^${metricPrefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\{(.+)\\}$`).exec(metricName);
  if (!match) {
    return null;
  }
  const tags = {};
  for (const entry of match[1].split(',')) {
    const separator = entry.indexOf(':');
    if (separator <= 0 || separator === entry.length - 1) {
      return null;
    }
    const key = entry.slice(0, separator);
    const value = entry.slice(separator + 1);
    if (Object.hasOwn(tags, key)) {
      return null;
    }
    tags[key] = value;
  }
  return tags;
}

function hasExactTags(tags, expected) {
  const tagKeys = Object.keys(tags || {});
  const expectedKeys = Object.keys(expected);
  return tagKeys.length === expectedKeys.length
    && expectedKeys.every((key) => tags?.[key] === expected[key]);
}

function matchingMetric(metrics, metricPrefix, expectedTags) {
  const matches = Object.entries(metrics || {})
    .map(([name, metric]) => ({ tags: parseTags(name, metricPrefix), metric }))
    .filter(({ tags }) => hasExactTags(tags, expectedTags));
  if (matches.length > 1) {
    return { malformed: true, metric: null };
  }
  return { malformed: false, metric: matches[0]?.metric || null };
}

function allocate(total, weightedNames) {
  const rows = weightedNames.map(({ name, weight }, index) => {
    const exact = (total * weight) / 100;
    return {
      name,
      index,
      count: Math.floor(exact),
      remainder: exact - Math.floor(exact),
    };
  });
  let remaining = total - rows.reduce((sum, row) => sum + row.count, 0);
  rows.sort((left, right) => right.remainder - left.remainder || left.index - right.index);
  for (let index = 0; index < remaining; index += 1) {
    rows[index].count += 1;
  }
  return Object.fromEntries(rows.map((row) => [row.name, row.count]));
}

function orderedArrivalIndexes(total, seed) {
  return Array.from({ length: total }, (_, arrivalIndex) => ({
    arrivalIndex,
    sortKey: sha256(`${seed}:mixed-arrival:${arrivalIndex}`),
  })).sort((left, right) => left.sortKey.localeCompare(right.sortKey));
}

function selectionCounts(selections) {
  const byOperation = Object.fromEntries(MIXED_OPERATIONS.map((operation) => [operation, 0]));
  const byTier = Object.fromEntries(MIXED_TIERS.map((tier) => [tier, 0]));
  const byTierOperation = Object.fromEntries(
    MIXED_TIERS.map((tier) => [
      tier,
      Object.fromEntries(MIXED_OPERATIONS.map((operation) => [operation, 0])),
    ]),
  );
  for (const selection of selections) {
    byOperation[selection.operation] += 1;
    byTier[selection.tier] += 1;
    byTierOperation[selection.tier][selection.operation] += 1;
  }
  return { byOperation, byTier, byTierOperation };
}

export function normalizeMixedProfileOptions(input) {
  const hotRoomCount = integer(input.hotRoomCount, 'hotRoomCount', 1, 100);
  const spreadRoomCount = integer(input.spreadRoomCount, 'spreadRoomCount', 1, 1_000);
  const hotRequestPercent = percentage(input.hotRequestPercent, 'hotRequestPercent');
  const spreadRequestPercent = percentage(input.spreadRequestPercent, 'spreadRequestPercent');
  const t1Percent = percentage(input.t1Percent, 't1Percent');
  const t2Percent = percentage(input.t2Percent, 't2Percent');
  const t5Percent = percentage(input.t5Percent, 't5Percent');
  const arrivalRate = integer(input.arrivalRate, 'arrivalRate', 1, 1_000);
  const arrivalTimeUnit = String(input.arrivalTimeUnit ?? '').trim();
  const durationSeconds = integer(input.durationSeconds, 'durationSeconds', 1, 3_600);
  const preAllocatedVUs = integer(input.preAllocatedVUs, 'preAllocatedVUs', 1, 2_000);
  const maxVUs = integer(input.maxVUs, 'maxVUs', 1, 2_000);
  const seed = integer(input.seed, 'seed', 0, 2_147_483_647);

  if (hotRequestPercent + spreadRequestPercent !== 100) {
    fail('hotRequestPercent와 spreadRequestPercent의 합은 100이어야 합니다.');
  }
  if (hotRequestPercent === 0 || spreadRequestPercent === 0) {
    fail('hotRequestPercent와 spreadRequestPercent는 모두 0보다 커야 합니다.');
  }
  if (t1Percent + t2Percent + t5Percent !== 100) {
    fail('t1Percent, t2Percent, t5Percent의 합은 100이어야 합니다.');
  }
  if (t1Percent === 0 || t2Percent === 0 || t5Percent === 0) {
    fail('t1Percent, t2Percent, t5Percent는 모두 0보다 커야 합니다.');
  }
  if (arrivalTimeUnit !== MIXED_TIME_UNIT) {
    fail(`arrivalTimeUnit은 ${MIXED_TIME_UNIT}이어야 합니다.`);
  }
  if (maxVUs < preAllocatedVUs) {
    fail('maxVUs는 preAllocatedVUs 이상이어야 합니다.');
  }
  if (spreadRoomCount < arrivalRate) {
    fail('spreadRoomCount는 같은 1초 wave의 spread 요청을 격리하도록 arrivalRate 이상이어야 합니다.');
  }
  if (Math.ceil(arrivalRate / hotRoomCount) > 10) {
    fail('hotRoomCount당 같은 1초 wave의 요청 수는 ROOM 정원 상한 10을 넘을 수 없습니다.');
  }

  const targetArrivalCount = arrivalRate * durationSeconds;
  if (targetArrivalCount > MAX_TARGET_ARRIVALS) {
    fail(`arrivalRate × durationSeconds는 fixture 상한 ${MAX_TARGET_ARRIVALS} 이하여야 합니다.`);
  }

  return {
    hotRoomCount,
    spreadRoomCount,
    hotRequestPercent,
    spreadRequestPercent,
    t1Percent,
    t2Percent,
    t5Percent,
    arrivalRate,
    arrivalTimeUnit,
    durationSeconds,
    preAllocatedVUs,
    maxVUs,
    seed,
    targetArrivalCount,
  };
}

export function createMixedSelectionPlan(input) {
  const options = normalizeMixedProfileOptions(input);
  const operationCounts = allocate(options.targetArrivalCount, [
    { name: 't1', weight: options.t1Percent },
    { name: 't2', weight: options.t2Percent },
    { name: 't5', weight: options.t5Percent },
  ]);
  if (Object.values(operationCounts).some((count) => count === 0)) {
    fail('targetArrivalCount가 T1/T2/T5 모두에 최소 한 요청을 배정할 수 없습니다.');
  }
  const unassigned = [];
  for (const operation of MIXED_OPERATIONS) {
    const tierCounts = allocate(operationCounts[operation], [
      { name: 'hot', weight: options.hotRequestPercent },
      { name: 'spread', weight: options.spreadRequestPercent },
    ]);
    for (const tier of MIXED_TIERS) {
      for (let index = 0; index < tierCounts[tier]; index += 1) {
        unassigned.push({ operation, tier });
      }
    }
  }

  const orderedIndexes = orderedArrivalIndexes(options.targetArrivalCount, options.seed);
  const selections = orderedIndexes
    .map(({ arrivalIndex }, index) => ({
      arrivalIndex,
      wave: Math.floor(arrivalIndex / options.arrivalRate),
      ...unassigned[index],
    }))
    .sort((left, right) => left.arrivalIndex - right.arrivalIndex);
  const slotByWaveAndOperation = new Map();
  for (const selection of selections) {
    const key = `${selection.wave}:${selection.operation}:${selection.tier}`;
    const slot = slotByWaveAndOperation.get(key) || 0;
    selection.roomSlot = slot % (selection.tier === 'hot' ? options.hotRoomCount : options.spreadRoomCount);
    slotByWaveAndOperation.set(key, slot + 1);
  }

  const counts = selectionCounts(selections);
  const selectionPlanDigest = sha256(JSON.stringify({ options, selections }));
  return {
    schemaVersion: 1,
    options,
    selectionPlanDigest,
    selections,
    counts,
  };
}

export function mixedExecutionOptions(input) {
  const options = normalizeMixedProfileOptions(input);
  return {
    executor: 'constant-arrival-rate',
    rate: options.arrivalRate,
    timeUnit: options.arrivalTimeUnit,
    duration: `${options.durationSeconds}s`,
    preAllocatedVUs: options.preAllocatedVUs,
    maxVUs: options.maxVUs,
  };
}

function emptyOutcomeLatency() {
  return {
    count: 0,
    p50: null,
    p95: null,
    p99: null,
    max: null,
  };
}

function outcomeLatency(metric, count) {
  if (count === 0 && !metric) {
    return emptyOutcomeLatency();
  }
  const values = metricValues(metric);
  const metricSampleCount = metricCount(metric);
  if (!values || metricSampleCount === null) {
    return null;
  }
  if (metricSampleCount !== count) {
    return { mismatch: true, count: metricSampleCount };
  }
  const result = {
    count,
    p50: metricStatistic(values, ['p50', 'med']),
    p95: metricStatistic(values, ['p95', 'p(95)']),
    p99: metricStatistic(values, ['p99', 'p(99)']),
    max: metricStatistic(values, ['max']),
  };
  const statistics = [result.p50, result.p95, result.p99, result.max];
  if (count === 0) {
    return statistics.every((value) => value === null) ? result : { mismatch: true, ...result };
  }
  return statistics.every(Number.isFinite) ? result : { mismatch: true, ...result };
}

function outcomeMetricCount(summary, outcome) {
  return metricCount(summary?.metrics?.[`room_request_duration{outcome:${outcome}}`]);
}

function aggregateTagSchema(tags) {
  return hasExactTags(tags, { tier: tags?.tier, operation: tags?.operation, outcome: tags?.outcome })
    && MIXED_TIERS.includes(tags.tier)
    && MIXED_OPERATIONS.includes(tags.operation)
    && MIXED_OUTCOMES.includes(tags.outcome);
}

function aggregateSeriesStatus(metrics, metricPrefix) {
  let validCount = 0;
  let malformedCount = 0;
  for (const name of Object.keys(metrics)) {
    if (name !== metricPrefix && !name.startsWith(`${metricPrefix}{`)) {
      continue;
    }
    const tags = parseTags(name, metricPrefix);
    if (aggregateTagSchema(tags)) {
      validCount += 1;
    } else {
      malformedCount += 1;
    }
  }
  return { validCount, malformedCount };
}

export function buildMixedAggregate(summary, input) {
  const profile = normalizeMixedProfileOptions(input);
  const metrics = summary?.metrics;
  const invalidReasons = [];
  const failureReasons = [];
  if (!metrics || typeof metrics !== 'object' || Array.isArray(metrics)) {
    return {
      schemaVersion: 1,
      status: 'INVALID',
      profile,
      invalidReasons: ['k6 summary metrics가 없습니다.'],
      failureReasons,
      tiers: {},
    };
  }

  const requestSeriesStatus = aggregateSeriesStatus(metrics, 'room_mixed_requests');
  const durationSeriesStatus = aggregateSeriesStatus(metrics, 'room_mixed_request_duration');
  if (requestSeriesStatus.validCount === 0) {
    invalidReasons.push('room_mixed_requests aggregate가 없습니다.');
  }
  if (durationSeriesStatus.validCount === 0) {
    invalidReasons.push('room_mixed_request_duration aggregate가 없습니다.');
  }
  if (requestSeriesStatus.malformedCount > 0 || durationSeriesStatus.malformedCount > 0) {
    invalidReasons.push('mixed aggregate의 tier·operation·outcome tag schema가 올바르지 않습니다.');
  }

  const droppedIterations = metricCount(metrics.dropped_iterations);
  if (droppedIterations === null) {
    invalidReasons.push('dropped_iterations artifact가 없거나 count가 올바르지 않습니다.');
  }

  const tiers = {};
  let actualArrivals = 0;
  for (const tier of MIXED_TIERS) {
    tiers[tier] = {};
    for (const operation of MIXED_OPERATIONS) {
      const outcomes = {};
      for (const outcome of MIXED_OUTCOMES) {
        const expectedTags = { tier, operation, outcome };
        const requestSeries = matchingMetric(metrics, 'room_mixed_requests', expectedTags);
        const durationSeries = matchingMetric(metrics, 'room_mixed_request_duration', expectedTags);
        if (requestSeries.malformed || durationSeries.malformed) {
          invalidReasons.push(`${tier}/${operation}/${outcome} aggregate tag가 중복되었거나 malformed입니다.`);
          outcomes[outcome] = emptyOutcomeLatency();
          continue;
        }
        const count = requestSeries.metric ? metricCount(requestSeries.metric) : 0;
        if (count === null) {
          invalidReasons.push(`${tier}/${operation}/${outcome} request count가 올바르지 않습니다.`);
          outcomes[outcome] = emptyOutcomeLatency();
          continue;
        }
        const latency = outcomeLatency(durationSeries.metric, count);
        if (!latency || latency.mismatch) {
          failureReasons.push(`${tier}/${operation}/${outcome} outcome latency와 request count가 일치하지 않습니다.`);
        }
        outcomes[outcome] = latency && !latency.mismatch ? latency : emptyOutcomeLatency();
        actualArrivals += count;
      }
      tiers[tier][operation] = outcomes;
    }
  }

  const outcomeTotal = MIXED_OUTCOMES.reduce((sum, outcome) => {
    const count = outcomeMetricCount(summary, outcome);
    if (count === null) {
      invalidReasons.push(`room_request_duration outcome=${outcome} count가 없습니다.`);
      return sum;
    }
    return sum + count;
  }, 0);
  if (invalidReasons.length === 0 && outcomeTotal !== actualArrivals) {
    failureReasons.push('tier·operation·outcome aggregate 합과 전체 ROOM 요청 수가 다릅니다.');
  }
  if (droppedIterations !== null && actualArrivals + droppedIterations !== profile.targetArrivalCount) {
    failureReasons.push('target arrivals와 actual arrivals + dropped_iterations가 다릅니다.');
  }

  const status = invalidReasons.length > 0
    ? 'INVALID'
    : failureReasons.length > 0
      ? 'FAIL'
      : 'PASS';
  return {
    schemaVersion: 1,
    status,
    profile,
    targetArrivals: profile.targetArrivalCount,
    actualArrivals,
    actualArrivalRate: actualArrivals / profile.durationSeconds,
    droppedIterations,
    tiers,
    invalidReasons,
    failureReasons,
  };
}
