import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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

function fillRequiredFields(region) {
  fireEvent.change(screen.getByLabelText('제목'), { target: { value: '테스트 모임' } });
  fireEvent.change(screen.getByLabelText('장소'), { target: { value: '테스트 장소' } });
  fireEvent.change(screen.getByLabelText('지역'), { target: { value: region } });
  fireEvent.change(screen.getByLabelText('날짜'), { target: { value: '2099-09-01' } });
  fireEvent.change(screen.getByLabelText('시작 시간'), { target: { value: '19:00' } });
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

  it('T1 게임 중심 생성은 선택한 지역을 실제 생성 요청에 전달한다', async () => {
    const createRoom = vi.spyOn(api, 'createRoom').mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, 'getGames').mockResolvedValue({
      content: [{ id: 101, name: '카탄', imageUrl: null }],
      page: 0,
      size: 10,
      hasNext: false
    });
    renderCreateView();

    fireEvent.click(await screen.findByRole('button', { name: /게임 선택하기/ }));
    fireEvent.change(screen.getByRole('textbox', { name: '게임 이름 검색' }), { target: { value: '카탄' } });
    fireEvent.click(await screen.findByRole('button', { name: /카탄/ }));
    fillRequiredFields('강남');
    fireEvent.click(screen.getByRole('button', { name: '모임 열기' }));

    await waitFor(() => expect(createRoom).toHaveBeenCalledWith(expect.objectContaining({
      roomType: 'GAME_FOCUSED',
      region: '강남'
    })));
  });

  it('T2 사람 중심 생성은 선택한 지역을 실제 생성 요청에 전달한다', async () => {
    const createRoom = vi.spyOn(api, 'createRoom').mockReturnValue(new Promise(() => {}));
    renderCreateView();

    fireEvent.click(await screen.findByRole('button', { name: /사람 중심/ }));
    fillRequiredFields('잠실');
    fireEvent.click(screen.getByRole('button', { name: '모임 열기' }));

    await waitFor(() => expect(createRoom).toHaveBeenCalledWith(expect.objectContaining({
      roomType: 'PERSON_FOCUSED',
      region: '잠실'
    })));
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
