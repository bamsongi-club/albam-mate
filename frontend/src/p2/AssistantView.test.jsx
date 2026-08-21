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

function recommendation(overrides = {}) {
  return {
    state: 'RECOMMENDED',
    conditions: {
      categories: ['COOPERATIVE'],
      mechanisms: [],
      themes: [],
      playerCount: 4,
      startsAt: '2099-08-23T19:00:00+09:00',
      region: '홍대',
      experienceLevel: 'BEGINNER_WELCOME'
    },
    missingFields: [],
    candidates: [{ id: 101, name: '카탄', imageUrl: null, description: '정식 카탈로그 설명' }],
    ...overrides
  };
}

async function requestRecommendation() {
  await waitFor(() => expect(screen.getByLabelText('알밤봇에게 묻기')).toBeTruthy());
  fireEvent.change(screen.getByLabelText('알밤봇에게 묻기'), { target: { value: '주말 협력 게임 추천해줘' } });
  await act(async () => { screen.getByRole('button', { name: '전송' }).click(); });
  await waitFor(() => expect(screen.getByRole('link', { name: '카탄 상세 보기' })).toBeTruthy());
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

    render(<AssistantView onBack={vi.fn()} onNavigate={onNavigate} assistantMemory={{
      result: recommendation(),
      selectedCandidate: { id: 101, name: '카탄', imageUrl: null, description: '정식 카탈로그 설명' },
      editState: { title: '카탄 모임', description: '복원하면 안 되는 카드', startsAt: '', region: '홍대', playerCount: '', experienceLevel: 'BEGINNER_WELCOME', isRulemasterLed: false }
    }} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '주말 협력 게임 모임' })).toBeTruthy());
    expect(screen.queryByRole('link', { name: '카탄 상세 보기' })).toBeNull();

    await act(async () => { screen.getByRole('button', { name: '방 만들기 확정' }).click(); });
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());

    await act(async () => { screen.getByRole('button', { name: '방 만들기 확정' }).click(); });

    await waitFor(() => expect(onNavigate).toHaveBeenCalledWith('/session/42', { replace: true }));
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(confirm.mock.calls[0][0]).toBe(7);
    expect(confirm.mock.calls[0][1]).toBe(2);
    expect(confirm.mock.calls[0][2]).toBe(confirm.mock.calls[1][2]);
  });

  it('후보 이미지와 게임명은 상세로만 이동하고 null 이미지는 fallback을 보인다', async () => {
    vi.spyOn(api, 'recommendAssistant').mockResolvedValue(recommendation());
    const createDraft = vi.spyOn(api, 'createAssistantDraft');
    const createRoom = vi.spyOn(api, 'createRoom');
    const confirmDraft = vi.spyOn(api, 'confirmAssistantDraft');
    const activeDraftLookup = api.getActiveAssistantDraft;
    const { container } = render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await requestRecommendation();

    const detailLink = screen.getByRole('link', { name: '카탄 상세 보기' });
    expect(detailLink.getAttribute('href')).toBe('#/game/101');
    expect(container.querySelector('.assistant-candidate img')?.getAttribute('src')).toContain('default-game-cover');
    const lookupsBeforeDetail = activeDraftLookup.mock.calls.length;
    await act(async () => { detailLink.click(); });
    expect(activeDraftLookup).toHaveBeenCalledTimes(lookupsBeforeDetail);
    expect(createDraft).not.toHaveBeenCalled();
    expect(createRoom).not.toHaveBeenCalled();
    expect(confirmDraft).not.toHaveBeenCalled();
  });

  it('CTA만 확인 모달을 열고 취소와 닫기는 draft·Room 부수효과가 없다', async () => {
    vi.spyOn(api, 'recommendAssistant').mockResolvedValue(recommendation());
    const createDraft = vi.spyOn(api, 'createAssistantDraft');
    const createRoom = vi.spyOn(api, 'createRoom');
    const confirmDraft = vi.spyOn(api, 'confirmAssistantDraft');
    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await requestRecommendation();
    await act(async () => { screen.getByRole('button', { name: '이 게임으로 모임 만들기' }).click(); });
    expect(screen.getByRole('dialog', { name: '이 게임으로 모임 만들기' })).toBeTruthy();
    await act(async () => { screen.getByRole('button', { name: '취소' }).click(); });
    expect(screen.queryByRole('dialog')).toBeNull();

    await act(async () => { screen.getByRole('button', { name: '이 게임으로 모임 만들기' }).click(); });
    await act(async () => { screen.getByRole('button', { name: '모임 만들기 확인 닫기' }).click(); });
    expect(createDraft).not.toHaveBeenCalled();
    expect(createRoom).not.toHaveBeenCalled();
    expect(confirmDraft).not.toHaveBeenCalled();
  });

  it('유효한 조건의 모달 확인은 기본 입력으로 초안을 정확히 한 번 만든다', async () => {
    vi.spyOn(api, 'recommendAssistant').mockResolvedValue(recommendation());
    const createDraft = vi.spyOn(api, 'createAssistantDraft').mockResolvedValue(activeDraft());

    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await requestRecommendation();
    await act(async () => { screen.getByRole('button', { name: '이 게임으로 모임 만들기' }).click(); });
    const confirm = screen.getByRole('button', { name: '이 조건으로 만들기' });
    await act(async () => { confirm.click(); confirm.click(); });

    await waitFor(() => expect(createDraft).toHaveBeenCalledTimes(1));
    expect(createDraft).toHaveBeenCalledWith({
      roomType: 'GAME_FOCUSED',
      title: '카탄 모임',
      description: null,
      gameId: 101,
      experienceLevel: 'BEGINNER_WELCOME',
      isRulemasterLed: false,
      startsAt: '2099-08-23T19:00:00+09:00',
      region: '홍대',
      place: null,
      recruitmentCapacity: 3
    });
    expect(screen.getByRole('heading', { name: '주말 협력 게임 모임' })).toBeTruthy();
  });

  it('자동 조건이 부족하면 직접 입력만 열고 제출 전에는 초안을 만들지 않는다', async () => {
    vi.spyOn(api, 'recommendAssistant').mockResolvedValue(recommendation({
      conditions: {
        categories: ['COOPERATIVE'], mechanisms: [], themes: [], playerCount: null, startsAt: null,
        region: '홍대', experienceLevel: 'BEGINNER_WELCOME'
      }
    }));
    const createDraft = vi.spyOn(api, 'createAssistantDraft').mockResolvedValue(activeDraft());
    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await requestRecommendation();
    await act(async () => { screen.getByRole('button', { name: '이 게임으로 모임 만들기' }).click(); });
    expect(screen.queryByRole('button', { name: '이 조건으로 만들기' })).toBeNull();
    await act(async () => { screen.getByRole('button', { name: '내가 직접 채우기' }).click(); });
    expect(screen.getByLabelText('모임 제목').value).toBe('카탄 모임');
    expect(screen.getByLabelText('지역').value).toBe('홍대');
    expect(createDraft).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('시작 시각'), { target: { value: '2099-08-23T19:00' } });
    fireEvent.change(screen.getByLabelText('총 인원'), { target: { value: '4' } });
    await act(async () => { screen.getByRole('button', { name: '확인 카드 만들기' }).click(); });
    await waitFor(() => expect(createDraft).toHaveBeenCalledTimes(1));
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

  it('열린 활성 초안의 확인이 410이면 만료 안내에서 새 흐름을 시작한다', async () => {
    vi.spyOn(api, 'getActiveAssistantDraft').mockResolvedValue(activeDraft());
    vi.spyOn(api, 'confirmAssistantDraft').mockRejectedValue(new ApiError({
      status: 410,
      code: 'ASSISTANT_DRAFT_EXPIRED',
      message: '초안이 만료되었습니다.'
    }));

    render(<AssistantView onBack={vi.fn()} onNavigate={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '주말 협력 게임 모임' })).toBeTruthy());
    await act(async () => { screen.getByRole('button', { name: '방 만들기 확정' }).click(); });

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
    await act(async () => { screen.getByRole('button', { name: '전송' }).click(); });

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
