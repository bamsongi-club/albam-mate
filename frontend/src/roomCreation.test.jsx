import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { App } from './main';

const stylesCss = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'styles.css'), 'utf8');

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
  return screen.findByRole('button', { name: /^지역/ });
}

async function chooseRegion(region) {
  fireEvent.click(await regionSelect());
  fireEvent.click(await screen.findByRole('option', { name: region }));
}

async function fillRequiredFields(region) {
  fireEvent.change(screen.getByLabelText('제목'), { target: { value: '테스트 모임' } });
  fireEvent.change(screen.getByLabelText('장소'), { target: { value: '테스트 장소' } });
  await chooseRegion(region);
  fireEvent.change(screen.getByLabelText('날짜'), { target: { value: '2099-09-01' } });
  fireEvent.change(screen.getByLabelText('시작 시간'), { target: { value: '19:00' } });
}

describe('#1077 Room 생성 지역 선택', () => {
  it('T1 게임 중심 생성 화면에서 네 지역을 선택할 수 있다', async () => {
    renderCreateView();

    const trigger = await regionSelect();
    fireEvent.click(trigger);
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['홍대', '강남', '건대', '잠실']);
    fireEvent.click(screen.getByRole('option', { name: '강남' }));
    expect(trigger.textContent).toContain('강남');
  });

  it('T2 사람 중심 생성 화면에서도 네 지역을 선택할 수 있다', async () => {
    renderCreateView();
    fireEvent.click(await screen.findByRole('button', { name: /사람 중심/ }));

    await chooseRegion('잠실');
    expect((await regionSelect()).textContent).toContain('잠실');
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
    await fillRequiredFields('강남');
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
    await fillRequiredFields('잠실');
    fireEvent.click(screen.getByRole('button', { name: '모임 열기' }));

    await waitFor(() => expect(createRoom).toHaveBeenCalledWith(expect.objectContaining({
      roomType: 'PERSON_FOCUSED',
      region: '잠실'
    })));
  });

  it('T3 모임 성격을 전환해도 지역 선택 UI와 값이 유지된다', async () => {
    renderCreateView();

    await chooseRegion('건대');
    fireEvent.click(screen.getByRole('button', { name: /사람 중심/ }));
    expect((await regionSelect()).textContent).toContain('건대');
    fireEvent.click(screen.getByRole('button', { name: /게임 중심/ }));
    expect((await regionSelect()).textContent).toContain('건대');
  });

  it('T4 지역을 장소보다 먼저 안내한다', async () => {
    renderCreateView();

    await waitFor(() => expect(screen.getByLabelText('제목')).toBeTruthy());
    const title = screen.getByLabelText('제목');
    const region = await regionSelect();
    const place = screen.getByLabelText('장소');

    expect(title.compareDocumentPosition(region) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(region.compareDocumentPosition(place) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('T5 모임 생성 화면은 모임 만들기 제목만 보여준다', async () => {
    renderCreateView();

    expect(await screen.findByRole('heading', { name: '모임 만들기' })).toBeTruthy();
    expect(screen.queryByText('새 모임')).toBeNull();
    expect(screen.queryByText('같이 놀 사람을 위한 자리를 만들어보세요.')).toBeNull();
  });

  it('T6 경험 수준을 가로 알약형 선택지로 제공한다', async () => {
    renderCreateView();

    const options = await screen.findAllByRole('button', { name: /경험 무관|초보 환영|경험자 위주/ });
    expect(options).toHaveLength(3);
    expect(options.every((option) => option.closest('.optionlist'))).toBe(true);
  });

  it('T7 소개란과 생성 폼 색상은 서비스 기본 토큰을 사용한다', () => {
    expect(stylesCss).toMatch(/\.create-screen\s*\{[^}]*--create-surface:\s*var\(--fill\);/s);
    expect(stylesCss).toMatch(/\.create-screen textarea\.field-input\s*\{[^}]*height:\s*148px;/s);
  });
});
