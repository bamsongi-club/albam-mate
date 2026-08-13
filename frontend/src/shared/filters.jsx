import React, { useEffect, useState } from 'react';
import { FilterIcon } from './ui';

// 한 값만 고르는 조건은 라디오로 그린다. 값이 빈 문자열인 선택지가 조건 없음이다.
export function FilterRadioGroup({ name, label, value, options, onChange, children }) {
  return (
    <fieldset className="filter-group">
      <legend>{label}</legend>
      <div className="filter-option-list">
        {options.map((option) => (
          <label className="filter-option" key={String(option.value)}>
            <input type="radio" name={name} checked={value === option.value} onChange={() => onChange(option.value)} />
            {option.label}
          </label>
        ))}
      </div>
      {children}
    </fieldset>
  );
}

export function FilterCheckGroup({ label, checked, onChange, text }) {
  return (
    <fieldset className="filter-group">
      <legend>{label}</legend>
      <div className="filter-option-list">
        <label className="filter-option">
          <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
          {text}
        </label>
      </div>
    </fieldset>
  );
}

// 여러 값을 함께 고르는 조건은 체크박스로 그린다. 고른 값들은 목록 안에서 OR로 결합한다.
export function FilterMultiCheckGroup({ label, values, options, onToggle, children }) {
  return (
    <fieldset className="filter-group">
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

// 고정 체크박스에 없는 값(7명 이상)을 계약이 받는 양의 정수 그대로 추가한다.
export function CustomPlayerCountInput({ label, values, onAdd }) {
  const [value, setValue] = useState('');
  const add = () => {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 1) return;
    const stringValue = String(parsed);
    if (!values.includes(stringValue)) onAdd(stringValue);
    setValue('');
  };
  return (
    // 필터 시트 자체가 form이 아니므로 중첩 form을 만들지 않고 버튼으로 추가한다.
    <div className="filter-custom-add">
      <input
        type="number"
        inputMode="numeric"
        min="1"
        aria-label={label + ' 직접 입력'}
        placeholder="직접 입력"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); add(); } }}
      />
      <button type="button" onClick={add}>추가</button>
    </div>
  );
}

// 최소·최대는 각각 생략할 수 있다. 마지막 입력 뒤 조회는 화면이 맡고 이 컴포넌트는 입력만 다룬다.
// 입력 둘을 flex로 나란히 둘 때 min-width:0이 없으면 intrinsic width 때문에 줄어들지 않는다.
export function FilterNumberRangeGroup({ label, min, max, unit, onMinChange, onMaxChange, children }) {
  return (
    <fieldset className="filter-group">
      <legend>{label}</legend>
      <div className="filter-range">
        <input type="number" inputMode="numeric" min="1" aria-label="최소" placeholder="최소" value={min} onChange={(event) => onMinChange(event.target.value)} />
        <span className="filter-range-dash" aria-hidden="true">—</span>
        <input type="number" inputMode="numeric" min="1" aria-label="최대" placeholder="최대" value={max} onChange={(event) => onMaxChange(event.target.value)} />
        {unit && <span className="filter-range-unit">{unit}</span>}
      </div>
      {children}
    </fieldset>
  );
}

/**
 * 조건 필터. 칩 줄의 `필터 N`을 누르면 바텀시트로 조건을 편다.
 *
 * 칩 줄에는 화면이 넘겨 준 빠른 선택(날짜 프리셋·해 본 게임)만 두고, 나머지 조건은 시트 안에서
 * 같은 칩 모양으로 고른다. 걸린 조건 수는 `필터 N`이 대신 말한다.
 */
export function FilterPanel({ title = '필터', chips = [], quickSlot, onReset, ctaLabel, children }) {
  const [isOpen, setIsOpen] = useState(false);
  const count = chips.length;

  useEffect(() => {
    if (!isOpen) return undefined;
    const closeOnEscape = (event) => { if (event.key === 'Escape') setIsOpen(false); };
    window.addEventListener('keydown', closeOnEscape);
    // 시트 뒤 본문이 함께 스크롤되지 않게 한다.
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', closeOnEscape);
      document.body.style.overflow = previousOverflow;
    };
  }, [isOpen]);

  return (
    <>
      <div className="filter-bar nos">
        {/* 보이는 글자가 그대로 조작 이름이 된다. 걸린 조건 수는 이름에도 함께 남는다. */}
        <button
          type="button"
          className={'filter-toggle' + (count ? ' on' : '')}
          aria-expanded={isOpen}
          onClick={() => setIsOpen(true)}
        >
          <FilterIcon />{title}{count ? ' ' + count : ''}
        </button>
        {quickSlot}
      </div>
      {isOpen && (
        <div className="sheet-backdrop" role="presentation" onMouseDown={() => setIsOpen(false)}>
          <section className="sheet nos" role="dialog" aria-modal="true" aria-label={title} onMouseDown={(event) => event.stopPropagation()}>
            <span className="sheet-handle" aria-hidden="true" />
            <div className="sheet-head">
              <h2>{title}</h2>
              <button type="button" className="sheet-reset" onClick={onReset}>초기화</button>
            </div>
            <div className="filter-groups">{children}</div>
            <button type="button" className="btn cta sheet-cta" onClick={() => setIsOpen(false)}>{ctaLabel || '결과 보기'}</button>
          </section>
        </div>
      )}
    </>
  );
}
