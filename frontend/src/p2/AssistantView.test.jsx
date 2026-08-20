import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import { AssistantSettingsView, AssistantView } from './AssistantView';

const NOT_GRANTED = {
  status: 'NOT_GRANTED',
  provider: 'OPENAI',
  consentVersion: 'AI-01-CONSENT-V1',
  policyVersion: 'OPENAI-2026-08',
  policyUrl: 'https://example.com/provider-policy',
  store: false,
  grantedAt: null,
  revokedAt: null
};

const GRANTED = { ...NOT_GRANTED, status: 'GRANTED', grantedAt: '2026-08-20T09:00:00Z' };

function activeDraft(overrides = {}) {
  return {
    draftId: 7,
    draftVersion: 2,
    status: 'ACTIVE',
    input: {
      roomType: 'GAME_FOCUSED',
      title: '주말 협력 게임 모임',
      description: null,
      gameId: 101,
      experienceLevel: 'BEGINNER_WELCOME',
      isRulemasterLed: false,
      startsAt: '2026-08-23T19:00:00+09:00',
      region: '홍대',
      place: '홍대 보드게임 카페',
      recruitmentCapacity: 3
    },
    result: null,
    ...overrides
  };
}

afterEach(() => {
  vi.restoreAllMocks();
  cleanup();
});

beforeEach(() => {
  vi.spyOn(api, 'getAssistantConsent').mockResolvedValue(GRANTED);
  vi.spyOn(api, 'getActiveAssistantDraft').mockResolvedValue(null);
});

describe('AI 모임 도우미 화면', () => {
  it('첫 사용은 별도 동의 후에만 자연어 추천 입력을 연다', async () => {
    vi.spyOn(api, 'getAssistantConsent').mockResolvedValue(NOT_GRANTED);
    vi.spyOn(api, 'changeAssistantConsent').mockResolvedValue(GRANTED);

    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: 'AI 사용 동의' })).toBeTruthy());
    expect(screen.queryByLabelText('알밤봇에게 묻기')).toBeNull();

    await act(async () => { screen.getByRole('button', { name: '동의하고 시작하기' }).click(); });

    await waitFor(() => expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy());
    expect(api.changeAssistantConsent).toHaveBeenCalledWith({
      decision: 'GRANT',
      consentVersion: 'AI-01-CONSENT-V1'
    });
  });

  it('활성 초안을 재진입으로 복구하고 확인 재시도에는 같은 멱등 키를 사용한다', async () => {
    vi.spyOn(api, 'getActiveAssistantDraft').mockResolvedValue(activeDraft());
    const confirm = vi.spyOn(api, 'confirmAssistantDraft')
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ roomId: 42, chatRoomId: 43 });
    const onNavigate = vi.fn();

    render(<AssistantView onBack={vi.fn()} onNavigate={onNavigate} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '주말 협력 게임 모임' })).toBeTruthy());

    await act(async () => { screen.getByRole('button', { name: '방 만들기 확정' }).click(); });
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());

    await act(async () => { screen.getByRole('button', { name: '방 만들기 확정' }).click(); });

    await waitFor(() => expect(onNavigate).toHaveBeenCalledWith('/session/42', { replace: true }));
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(confirm.mock.calls[0][0]).toBe(7);
    expect(confirm.mock.calls[0][1]).toBe(2);
    expect(confirm.mock.calls[0][2]).toBe(confirm.mock.calls[1][2]);
  });

  it('추천 후보를 사용자가 선택하고 필요한 모임 정보를 채운 뒤에만 확인 초안을 만든다', async () => {
    vi.spyOn(api, 'recommendAssistant').mockResolvedValue({
      state: 'RECOMMENDED',
      conditions: {
        categories: ['COOPERATIVE'],
        mechanisms: [],
        themes: [],
        playerCount: 4,
        startsAt: '2026-08-23T19:00:00+09:00',
        region: '홍대',
        experienceLevel: 'BEGINNER_WELCOME'
      },
      missingFields: [],
      candidates: [{ id: 101, name: '카탄' }]
    });
    const createDraft = vi.spyOn(api, 'createAssistantDraft').mockResolvedValue(activeDraft());

    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await waitFor(() => expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('알밤봇에게 묻기'), { target: { value: '주말 협력 게임 추천해줘' } });
    await act(async () => { screen.getByRole('button', { name: '추천 받기' }).click(); });
    await waitFor(() => expect(screen.getByRole('button', { name: /카탄/ })).toBeTruthy());

    await act(async () => { screen.getByRole('button', { name: /카탄/ }).click(); });
    fireEvent.change(screen.getByLabelText('모임 제목'), { target: { value: '주말 카탄 모임' } });
    await act(async () => { screen.getByRole('button', { name: '확인 카드 만들기' }).click(); });

    await waitFor(() => expect(createDraft).toHaveBeenCalledWith(expect.objectContaining({
      roomType: 'GAME_FOCUSED',
      title: '주말 카탄 모임',
      gameId: 101,
      region: '홍대',
      place: null,
      recruitmentCapacity: 3
    })));
    expect(screen.getByRole('heading', { name: '주말 협력 게임 모임' })).toBeTruthy();
  });

  it('활성 초안 조회의 410은 만료 안내와 새 흐름 시작 행동으로 끝낸다', async () => {
    vi.spyOn(api, 'getActiveAssistantDraft').mockRejectedValue(new ApiError({
      status: 410,
      code: 'ASSISTANT_DRAFT_EXPIRED',
      message: '초안이 만료되었습니다.'
    }));

    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '초안이 만료됐어요' })).toBeTruthy());
    await act(async () => { screen.getByRole('button', { name: '새로 시작하기' }).click(); });
    expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy();
  });

  it('다른 탭에서 동의를 철회하면 동의 카드를 다시 불러온다', async () => {
    vi.spyOn(api, 'getAssistantConsent')
      .mockResolvedValueOnce(GRANTED)
      .mockResolvedValueOnce(NOT_GRANTED);
    vi.spyOn(api, 'recommendAssistant').mockRejectedValue(new ApiError({
      status: 403,
      code: 'ASSISTANT_CONSENT_REQUIRED',
      message: 'AI 처리 동의가 필요합니다.'
    }));

    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await waitFor(() => expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('알밤봇에게 묻기'), { target: { value: '초보 모임 추천해줘' } });
    await act(async () => { screen.getByRole('button', { name: '추천 받기' }).click(); });

    await waitFor(() => expect(screen.getByRole('heading', { name: 'AI 사용 동의' })).toBeTruthy());
    expect(api.getAssistantConsent).toHaveBeenCalledTimes(2);
  });

  it('장소를 저장한 뒤 초안을 버리면 새 추천 흐름으로 돌아간다', async () => {
    const draft = activeDraft();
    const savedDraft = activeDraft({
      draftVersion: 3,
      input: { ...draft.input, place: '강남 보드게임 카페' }
    });
    vi.spyOn(api, 'getActiveAssistantDraft').mockResolvedValue(draft);
    const updateDraft = vi.spyOn(api, 'updateAssistantDraft').mockResolvedValue(savedDraft);
    const discardDraft = vi.spyOn(api, 'discardAssistantDraft').mockResolvedValue(undefined);

    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '주말 협력 게임 모임' })).toBeTruthy());
    fireEvent.change(screen.getByLabelText('상세 장소'), { target: { value: '강남 보드게임 카페' } });
    await act(async () => { screen.getByRole('button', { name: '변경 저장' }).click(); });
    await waitFor(() => expect(updateDraft).toHaveBeenCalledWith(7, {
      draftVersion: 2,
      region: '홍대',
      place: '강남 보드게임 카페'
    }));

    await act(async () => { screen.getByRole('button', { name: '초안 버리기' }).click(); });
    await waitFor(() => expect(discardDraft).toHaveBeenCalledWith(7));
    expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy();
  });
});

describe('AI 설정 화면', () => {
  it('현재 정책을 표시하고 철회는 AI 흐름만 종료한다', async () => {
    vi.spyOn(api, 'changeAssistantConsent').mockResolvedValue({ ...NOT_GRANTED, status: 'REVOKED' });

    render(<AssistantSettingsView onBack={vi.fn()} />);

    await waitFor(() => expect(screen.getByText('OpenAI')).toBeTruthy());
    expect(screen.getByRole('link', { name: 'provider 정책 보기' }).getAttribute('href')).toBe(NOT_GRANTED.policyUrl);

    await act(async () => { screen.getByRole('button', { name: 'AI 처리 동의 철회' }).click(); });

    await waitFor(() => expect(screen.getByText('철회됨')).toBeTruthy());
    expect(api.changeAssistantConsent).toHaveBeenCalledWith({ decision: 'REVOKE' });
  });
});
