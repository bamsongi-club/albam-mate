import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { App } from './main';

afterEach(() => {
  vi.restoreAllMocks();
  cleanup();
  window.location.hash = '';
});

function renderCreateView() {
  vi.spyOn(api, 'getMyProfile').mockResolvedValue({ id: 1, nickname: '테스터' });
  vi.spyOn(api, 'getSocialProviders').mockResolvedValue([]);
  window.location.hash = '#/create';
  render(<App />);
}

async function regionSelect() {
  return screen.findByLabelText('지역');
}

describe('#1077 Room 생성 지역 선택', () => {
  it('T1 게임 중심 생성 화면에서 네 지역을 선택할 수 있다', async () => {
    renderCreateView();

    const select = await regionSelect();
    expect([...select.options].map((option) => option.value)).toEqual(['', '홍대', '강남', '건대', '잠실']);
    fireEvent.change(select, { target: { value: '강남' } });
    expect(select.value).toBe('강남');
  });

  it('T2 사람 중심 생성 화면에서도 네 지역을 선택할 수 있다', async () => {
    renderCreateView();
    fireEvent.click(await screen.findByRole('button', { name: /사람 중심/ }));

    const select = await regionSelect();
    fireEvent.change(select, { target: { value: '잠실' } });
    expect(select.value).toBe('잠실');
  });

  it('T3 모임 성격을 전환해도 지역 선택 UI와 값이 유지된다', async () => {
    renderCreateView();

    const select = await regionSelect();
    fireEvent.change(select, { target: { value: '건대' } });
    fireEvent.click(screen.getByRole('button', { name: /사람 중심/ }));
    expect((await regionSelect()).value).toBe('건대');
    fireEvent.click(screen.getByRole('button', { name: /게임 중심/ }));
    expect((await regionSelect()).value).toBe('건대');
  });
});
