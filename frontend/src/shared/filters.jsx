import React, { useState } from 'react';

// 한 값만 고르는 조건은 라디오로 그린다. 값이 빈 문자열인 선택지가 조건 없음이다.
export function FilterRadioGroup({ name, label, value, options, onChange, children }) {
  return (
    <fieldset className="filter-group">
      <legend>{label}</legend>
      {options.map((option) => (
        <label className="filter-option" key={String(option.value)}>
          <input type="radio" name={name} checked={value === option.value} onChange={() => onChange(option.value)} />
          {option.label}
        </label>
      ))}
      {children}
    </fieldset>
  );
}

export function FilterCheckGroup({ label, checked, onChange, text }) {
  return (
    <fieldset className="filter-group">
      <legend>{label}</legend>
      <label className="filter-option">
        <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
        {text}
      </label>
    </fieldset>
  );
}

// 여러 값을 함께 고르는 조건은 체크박스로 그린다. 고른 값들은 목록 안에서 OR로 결합한다.
// wide는 선택지가 많은 조건에서 쓴다. 좁은 칼럼에 세로로 쌓지 않고 전체 폭에서 가로로 흐른다.
export function FilterMultiCheckGroup({ label, values, options, onToggle, children, wide = false }) {
  return (
    <fieldset className={'filter-group' + (wide ? ' filter-group-wide' : '')}>
      <legend>{label}</legend>
      <div className="filter-option-list">
        {options.map((option) => (
          <label className="filter-option" key={option.value}>
            <input
              type="checkbox"
              checked={values.includes(option.value)}
              onChange={(event) => onToggle(option.value, event.target.checked)}
            />
            {option.label}
          </label>
        ))}
      </div>
      {children}
    </fieldset>
  );
}

// 고정 체크박스에 없는 값(9명 이상)을 계약이 받는 양의 정수 그대로 추가한다.
export function CustomPlayerCountInput({ label, values, onAdd }) {
  const [value, setValue] = useState('');
  const submit = (event) => {
    event.preventDefault();
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 1) return;
    const stringValue = String(parsed);
    if (!values.includes(stringValue)) onAdd(stringValue);
    setValue('');
  };
  return (
    <form className="filter-custom-add" onSubmit={submit}>
      <input type="number" inputMode="numeric" min="1" aria-label={label + ' 직접 입력'} placeholder="직접 입력" value={value} onChange={(event) => setValue(event.target.value)} />
      <button type="submit">추가</button>
    </form>
  );
}

// 최소·최대는 각각 생략할 수 있다. 마지막 입력 뒤 조회는 화면이 맡고 이 컴포넌트는 입력만 다룬다.
export function FilterNumberRangeGroup({ label, min, max, unit, onMinChange, onMaxChange, children, rowStart = false }) {
  return (
    <fieldset className={'filter-group' + (rowStart ? ' filter-group-row-start' : '')}>
      <legend>{label}</legend>
      <div className="filter-range">
        <input
          type="number"
          inputMode="numeric"
          min="1"
          aria-label="최소"
          placeholder="최소"
          value={min}
          onChange={(event) => onMinChange(event.target.value)}
        />
        <span className="filter-range-dash" aria-hidden="true">~</span>
        <input
          type="number"
          inputMode="numeric"
          min="1"
          aria-label="최대"
          placeholder="최대"
          value={max}
          onChange={(event) => onMaxChange(event.target.value)}
        />
        <span className="filter-range-unit">{unit}</span>
      </div>
      {children}
    </fieldset>
  );
}

// 고른 조건은 칩으로 보여 주고 칩마다 그 조건만 해제한다. 패널을 접어도 무엇이 걸려 있는지 남는다.
export function FilterPanel({ chips, onReset, children, searchSlot }) {
  const [isOpen, setIsOpen] = useState(false);
  return (
    <div className="filter-shell">
      <div className="filter-bar">
        <button type="button" className={'filter-toggle' + (isOpen ? ' on' : '')} aria-expanded={isOpen} aria-controls="search-filter-panel" aria-label="조건 필터" onClick={() => setIsOpen(!isOpen)}>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><line x1="4" y1="8" x2="20" y2="8" /><line x1="4" y1="16" x2="20" y2="16" /><circle cx="10" cy="8" r="2.4" fill="currentColor" stroke="none" /><circle cx="15" cy="16" r="2.4" fill="currentColor" stroke="none" /></svg>
        </button>
        {chips.map((chip) => (
          <button type="button" className="filter-chip" key={chip.key} aria-label={chip.label + ' 조건 해제'} onClick={chip.onClear}>{chip.label}<span aria-hidden="true">×</span></button>
        ))}
        {searchSlot && <div className="filter-bar-search">{searchSlot}</div>}
      </div>
      {isOpen && (
        <div className="filter-panel" id="search-filter-panel">
          <div className="filter-groups">{children}</div>
          <div className="filter-panel-foot">
            {!!chips.length && <button type="button" className="filter-reset" onClick={onReset}>초기화</button>}
            <button type="button" className="filter-close" onClick={() => setIsOpen(false)}>닫기</button>
          </div>
        </div>
      )}
    </div>
  );
}
