import {
  AGE_BAND_LABEL,
  COMPLEXITY_BANDS,
  EMPTY_PLAYER_COUNT_RANGE,
  EXCLUSIVE_PLAYER_COUNT_OPTIONS,
  PLAYED_FILTER_OPTIONS,
  PLAY_TIME_LABEL
} from './constants';

export function complexityBandOf(filters) {
  return COMPLEXITY_BANDS.find((band) => band.min === filters.complexityMin && band.max === filters.complexityMax);
}

// 인원 숫자만 지운 상태로 비교한다. 숫자 입력과 나머지 선택의 변경을 가려내는 기준이다.
export function gameFiltersWithoutPlayerCountNumbers(filters) {
  return JSON.stringify({ ...filters, playerCountMin: '', playerCountMax: '' });
}

/**
 * 전용 인원 선택을 인원 조건 patch로 바꾼다.
 *
 * 하나만 고르면 `1인 전용`은 `min_players = max_players = 1`이라 `1 ~ 1` 경계 정확 일치와 같은 조건이다.
 * 그래서 범위 입력에 그대로 되비춰 무엇을 고른 상태인지 보여 준다. 둘을 함께 고르면 OR이라
 * 하나의 범위로 나타낼 수 없으므로 범위 입력을 비운다.
 */
export function exclusivePlayerCountPatch(selected) {
  const single = selected.length === 1 ? selected[0] : '';
  return {
    exclusivePlayerCount: selected,
    playerCountMin: single,
    playerCountMax: single,
    playerCountExact: selected.length === 1
  };
}

export function clearedExclusivePlayerCount(filters, value) {
  return exclusivePlayerCountPatch(filters.exclusivePlayerCount.filter((selected) => selected !== value));
}

/**
 * 요청에 실제로 실을 조건을 만든다.
 *
 * 전용 인원을 고른 상태의 범위 입력은 같은 조건을 되비추는 표시일 뿐이다. 계약은 범위 계열과
 * 전용 인원을 함께 담은 요청을 검증 오류로 거절하므로 이때 범위 파라미터를 뺀다.
 */
export function gameFilterParameters(filters) {
  return filters.exclusivePlayerCount.length ? { ...filters, ...EMPTY_PLAYER_COUNT_RANGE } : filters;
}

export function playerCountRangeLabel(filters) {
  if (!filters.playerCountMin && !filters.playerCountMax) return '';
  const suffix = filters.playerCountExact ? ' 정확히' : '';
  if (filters.playerCountMin && filters.playerCountMax) {
    return filters.playerCountMin + '~' + filters.playerCountMax + '명' + suffix;
  }
  if (filters.playerCountMin) return filters.playerCountMin + '명 이상' + suffix;
  return filters.playerCountMax + '명 이하' + suffix;
}

export function gameFilterChips(filters, onChange, mechanismOptions, categoryOptions = [], themeOptions = []) {
  const update = (patch) => onChange({ ...filters, ...patch });
  const chips = [];
  // 전용 인원을 고르면 범위 입력이 같은 조건을 되비추므로 칩을 두 번 만들지 않는다.
  const rangeLabel = filters.exclusivePlayerCount.length ? '' : playerCountRangeLabel(filters);
  if (rangeLabel) {
    chips.push({
      key: 'playerCountRange',
      label: rangeLabel,
      onClear: () => update({ playerCountMin: '', playerCountMax: '', playerCountExact: false })
    });
  }
  filters.exclusivePlayerCount.forEach((value) => {
    const option = EXCLUSIVE_PLAYER_COUNT_OPTIONS.find((candidate) => candidate.value === value);
    if (option) {
      chips.push({
        key: 'exclusive-' + value,
        label: option.label,
        onClear: () => update(clearedExclusivePlayerCount(filters, value))
      });
    }
  });
  filters.playTime.forEach((value) => {
    if (PLAY_TIME_LABEL[value]) {
      chips.push({
        key: 'playTime-' + value,
        label: PLAY_TIME_LABEL[value],
        onClear: () => update({ playTime: filters.playTime.filter((selected) => selected !== value) })
      });
    }
  });
  filters.ageBand.forEach((value) => {
    if (AGE_BAND_LABEL[value]) {
      chips.push({
        key: 'ageBand-' + value,
        label: AGE_BAND_LABEL[value],
        onClear: () => update({ ageBand: filters.ageBand.filter((selected) => selected !== value) })
      });
    }
  });
  filters.mechanism.forEach((code) => {
    const option = mechanismOptions.find((candidate) => candidate.code === code);
    if (option) {
      const nextMechanisms = filters.mechanism.filter((selected) => selected !== code);
      chips.push({
        key: 'mechanism-' + code,
        label: option.nameKo,
        onClear: () => update({ mechanism: nextMechanisms, ...(nextMechanisms.length ? null : { mechanismMatch: '' }) })
      });
    }
  });
  const pushOptionChips = (key, options, prefix = '', matchKey) => {
    filters[key].forEach((code) => {
      const option = options.find((candidate) => candidate.code === code);
      if (!option) return;
      const nextValues = filters[key].filter((selected) => selected !== code);
      chips.push({
        key: key + '-' + code,
        label: prefix + option.nameKo,
        onClear: () => update({ [key]: nextValues, ...(matchKey && !nextValues.length ? { [matchKey]: '' } : null) })
      });
    });
  };
  pushOptionChips('category', categoryOptions);
  pushOptionChips('theme', themeOptions, '', 'themeMatch');
  if (filters.theme.length > 1 && filters.themeMatch === 'ALL') {
    chips.push({ key: 'themeMatch', label: '테마 모두 포함', onClear: () => update({ themeMatch: '' }) });
  }
  if (filters.mechanism.length > 1 && filters.mechanismMatch === 'ALL') {
    chips.push({ key: 'mechanismMatch', label: '메커니즘 모두 포함', onClear: () => update({ mechanismMatch: '' }) });
  }
  [['recommendedPlayerCount', '추천'], ['bestPlayerCount', '베스트']].forEach(([key, prefix]) => {
    filters[key].forEach((value) => {
      chips.push({
        key: key + '-' + value,
        label: prefix + ' ' + value + '명',
        onClear: () => update({ [key]: filters[key].filter((selected) => selected !== value) })
      });
    });
  });
  const playedOption = PLAYED_FILTER_OPTIONS.find((option) => option.value && option.value === filters.playedFilter);
  if (playedOption) chips.push({ key: 'playedFilter', label: playedOption.label, onClear: () => update({ playedFilter: '' }) });
  const band = complexityBandOf(filters);
  if (band) chips.push({ key: 'complexity', label: '난이도 ' + band.label, onClear: () => update({ complexityMin: '', complexityMax: '' }) });
  if (filters.upcomingOnly) chips.push({ key: 'upcomingOnly', label: '예정 모임 있음', onClear: () => update({ upcomingOnly: false }) });
  return chips;
}
