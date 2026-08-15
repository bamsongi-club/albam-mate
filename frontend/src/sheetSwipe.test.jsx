import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FilterPanel } from './shared/filters';
import { SHEET_CLOSE_DRAG_PX } from './shared/sheetDrag';

afterEach(cleanup);

const pointerAt = (clientY) => ({ clientY, pointerId: 1 });

function drag(node, from, to, { release = true } = {}) {
  fireEvent.pointerDown(node, pointerAt(from));
  fireEvent.pointerMove(node, pointerAt(to));
  if (release) fireEvent.pointerUp(node, pointerAt(to));
}

function openFilter() {
  const view = render(
    <FilterPanel title="게임 필터" chips={[]} onReset={vi.fn()} ctaLabel="게임 보기">
      <label htmlFor="min">최소</label>
      <input id="min" defaultValue="" />
    </FilterPanel>
  );
  fireEvent.click(screen.getByRole('button', { name: /게임 필터/ }));
  return view;
}

const grip = () => document.querySelector('.sheet-grip');
const sheet = () => document.querySelector('.sheet');
const isOpen = () => Boolean(document.querySelector('[role="dialog"]'));

describe('#750 T1~T8 바텀시트 스와이프 닫기', () => {
  it('T1 손잡이에서 임계값 이상 끌면 닫히고 필터 버튼으로 포커스가 돌아온다', () => {
    openFilter();
    expect(isOpen()).toBe(true);

    drag(grip(), 100, 100 + SHEET_CLOSE_DRAG_PX);

    expect(isOpen()).toBe(false);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: /게임 필터/ }));
  });

  it('T2 임계값 미만으로 끌었다 놓으면 열린 상태로 남는다', () => {
    openFilter();

    drag(grip(), 100, 100 + SHEET_CLOSE_DRAG_PX - 1);

    expect(isOpen()).toBe(true);
    // 놓은 뒤에는 인라인 transform이 사라져 CSS transition이 제자리로 되돌린다.
    expect(sheet().style.transform).toBe('');
  });

  it('T3 위로 끌어도 닫히지 않고 시트를 끌어올리지 않는다', () => {
    openFilter();

    fireEvent.pointerDown(grip(), pointerAt(200));
    fireEvent.pointerMove(grip(), pointerAt(80));

    expect(isOpen()).toBe(true);
    expect(sheet().style.transform).toBe('');

    fireEvent.pointerUp(grip(), pointerAt(80));
    expect(isOpen()).toBe(true);
  });

  it('끄는 동안에는 시트가 손가락을 따라 내려간다', () => {
    openFilter();

    drag(grip(), 100, 140, { release: false });

    expect(sheet().style.transform).toBe('translateY(40px)');
    expect(isOpen()).toBe(true);
  });

  it('T5 시트 본문을 스크롤해도 닫히지 않는다', () => {
    openFilter();

    // 손잡이가 아닌 본문에서 시작한 세로 이동은 닫기 제스처가 아니다.
    const body = screen.getByLabelText('최소');
    drag(body, 100, 100 + SHEET_CLOSE_DRAG_PX * 2);

    expect(isOpen()).toBe(true);
  });

  it('T6 제스처를 넣은 뒤에도 바깥 영역 터치로 닫힌다', () => {
    openFilter();

    fireEvent.mouseDown(document.querySelector('.sheet-backdrop'));

    expect(isOpen()).toBe(false);
  });

  it('T6 제스처를 넣은 뒤에도 Escape로 닫힌다', () => {
    openFilter();

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(isOpen()).toBe(false);
  });

  it('T7 제스처로 닫아도 body 스크롤 잠금이 열기 전 값으로 돌아온다', () => {
    document.body.style.overflow = '';
    openFilter();
    expect(document.body.style.overflow).toBe('hidden');

    drag(grip(), 100, 100 + SHEET_CLOSE_DRAG_PX);

    expect(isOpen()).toBe(false);
    expect(document.body.style.overflow).toBe('');
  });

  it('T8 포인터 없이 키보드만으로 시트를 닫을 수 있다', () => {
    openFilter();

    fireEvent.click(screen.getByRole('button', { name: '게임 보기' }));

    expect(isOpen()).toBe(false);
  });
});
