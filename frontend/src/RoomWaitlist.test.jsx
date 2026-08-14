import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from './api';
import { App, SessionDetailView, WAITLIST_POLL_INTERVAL_MS } from './main';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  cleanup();
  window.location.hash = '';
});

const me = { id: 1, nickname: '테스터' };

function fullRoom(overrides = {}) {
  return {
    id: 7,
    title: '정원이 찬 모임',
    roomType: 'GAME_FOCUSED',
    status: 'CLOSED',
    startsAt: '2099-09-01T19:00:00+09:00',
    region: '홍대',
    place: '카페',
    experienceLevel: 'ALL_LEVELS',
    isRulemasterLed: false,
    participantCount: 3,
    recruitmentCapacity: 3,
    remainingRecruitmentSeats: 0,
    participants: [],
    myRole: null,
    joinable: false,
    waitlistable: false,
    ...overrides
  };
}

function renderDetail(props = {}) {
  return render(
    <SessionDetailView
      sessionId="7"
      me={me}
      onApply={vi.fn()}
      onCancelApply={vi.fn()}
      onHostCancel={vi.fn()}
      onFinish={vi.fn()}
      onJoinWaitlist={vi.fn().mockResolvedValue(true)}
      onCancelWaitlist={vi.fn().mockResolvedValue(true)}
      onWaitlistSettled={vi.fn()}
      dataVersion={0}
      {...props}
    />
  );
}

// 로그인·알림 등 App 전역 배선은 대기 흐름과 무관하므로 최소값으로 고정한다.
function stubAppDependencies() {
  vi.spyOn(api, 'getMyProfile').mockResolvedValue(me);
  vi.spyOn(api, 'getSocialProviders').mockResolvedValue([]);
  vi.spyOn(api, 'getNotifications').mockResolvedValue({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
  vi.spyOn(api, 'getUnreadNotificationCount').mockResolvedValue({ unreadCount: 0 });
}

describe('PART-04 대기 상태와 상세 행동 가능 여부 수렴', () => {
  it('WAITING 동안 대기 상태를 다시 읽어 외부 승격을 감지하고 상세 동기화를 요청한다', async () => {
    // 폴링 주기를 실시간으로 기다리지 않되 waitFor는 그대로 동작하도록 가짜 시계를 실시간과 함께 진행시킨다.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.spyOn(api, 'getRoom').mockResolvedValue(fullRoom());
    const getMyWaitlist = vi.spyOn(api, 'getMyWaitlist')
      .mockResolvedValueOnce({ roomId: 7, waitlistStatus: 'WAITING', position: 1 })
      .mockResolvedValue({ roomId: 7, waitlistStatus: 'PROMOTED', position: null });
    const onWaitlistSettled = vi.fn();
    renderDetail({ onWaitlistSettled });

    await waitFor(() => expect(screen.getByText('대기 1번째입니다.')).toBeTruthy());

    await act(async () => {
      await vi.advanceTimersByTimeAsync(WAITLIST_POLL_INTERVAL_MS);
    });

    expect(getMyWaitlist.mock.calls.length).toBeGreaterThan(1);
    expect(onWaitlistSettled).toHaveBeenCalled();
    expect(screen.getByText('대기가 자리로 승격되어 참가가 확정됐어요')).toBeTruthy();
  });

  it('재활성화하지 않는 EXPIRED 이력에서는 이전 waitlistable로 재신청을 안내하지 않는다', async () => {
    vi.spyOn(api, 'getRoom').mockResolvedValue(fullRoom({ waitlistable: true }));
    vi.spyOn(api, 'getMyWaitlist').mockResolvedValue({ roomId: 7, waitlistStatus: 'EXPIRED', position: null });
    renderDetail();

    await waitFor(() => expect(screen.getByText('모임이 시작되어 대기가 종료됐어요.')).toBeTruthy());
    expect(screen.queryByRole('button', { name: '대기 신청하기' })).toBeNull();
  });

  it('대기 취소가 끝나면 상세를 다시 읽어 최신 waitlistable로 재신청을 안내한다', async () => {
    stubAppDependencies();
    const getRoom = vi.spyOn(api, 'getRoom')
      .mockResolvedValueOnce(fullRoom())
      .mockResolvedValue(fullRoom({ waitlistable: true }));
    vi.spyOn(api, 'getMyWaitlist')
      .mockResolvedValueOnce({ roomId: 7, waitlistStatus: 'WAITING', position: 1 })
      .mockRejectedValue(new ApiError({ status: 404, code: 'WAITLIST_ENTRY_NOT_FOUND', message: '대기 이력이 없습니다.' }));
    vi.spyOn(api, 'cancelWaitlist').mockResolvedValue({});
    window.location.hash = '#/session/7';
    render(<App />);

    await waitFor(() => expect(screen.getByRole('button', { name: '대기 취소' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '대기 취소' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '대기 신청하기' })).toBeTruthy());
    expect(getRoom.mock.calls.length).toBeGreaterThan(1);
  });

  it('빈자리 경합으로 대기 신청이 실패해도 상세를 다시 읽어 같은 CTA를 남기지 않는다', async () => {
    stubAppDependencies();
    const getRoom = vi.spyOn(api, 'getRoom')
      .mockResolvedValueOnce(fullRoom({ waitlistable: true }))
      .mockResolvedValue(fullRoom({ status: 'RECRUITING', participantCount: 2, remainingRecruitmentSeats: 1, joinable: true }));
    vi.spyOn(api, 'getMyWaitlist')
      .mockRejectedValue(new ApiError({ status: 404, code: 'WAITLIST_ENTRY_NOT_FOUND', message: '대기 이력이 없습니다.' }));
    vi.spyOn(api, 'joinWaitlist')
      .mockRejectedValue(new ApiError({ status: 409, code: 'WAITLIST_NOT_AVAILABLE', message: '대기 신청할 수 없습니다.' }));
    window.location.hash = '#/session/7';
    render(<App />);

    await waitFor(() => expect(screen.getByRole('button', { name: '대기 신청하기' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '대기 신청하기' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '참가하기' })).toBeTruthy());
    expect(getRoom.mock.calls.length).toBeGreaterThan(1);
    expect(screen.queryByRole('button', { name: '대기 신청하기' })).toBeNull();
  });
});
