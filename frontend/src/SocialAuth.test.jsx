import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, clearCsrfToken, socialLoginUrl } from './api';
import { AuthView, ProfileView, SocialLinkView, consumeSocialAuthResult, readSocialAuthResult } from './main';

const GOOGLE_NOT_LINKED = { provider: 'GOOGLE', linked: false };
const NAVER_LINKED = { provider: 'NAVER', linked: true };
const KAKAO_NOT_LINKED = { provider: 'KAKAO', linked: false };

function successfulResponse(data) {
  return new Response(JSON.stringify({ status: 200, data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  });
}

function renderProfile(socialProviders, onSocialLink = vi.fn()) {
  render(
    <ProfileView
      me={{ nickname: '테스터' }}
      onSave={vi.fn()}
      onLogout={vi.fn()}
      socialProviders={socialProviders}
      onSocialLink={onSocialLink}
    />
  );
  return onSocialLink;
}

function renderSocialLink(socialProviders, onSocialLink = vi.fn()) {
  render(<SocialLinkView socialProviders={socialProviders} onSocialLink={onSocialLink} onBack={vi.fn()} />);
  return onSocialLink;
}

afterEach(() => {
  cleanup();
  clearCsrfToken();
  vi.unstubAllGlobals();
});

describe('프로필 수정 흐름', () => {
  it('수정에서 닉네임을 고치고 사진은 아바타 배지로 바꾼다', async () => {
    const onSave = vi.fn().mockResolvedValue(true);
    const onUploadImage = vi.fn().mockResolvedValue();
    render(
      <ProfileView
        me={{ nickname: '테스터', email: 'tester@example.com' }}
        onSave={onSave}
        onLogout={vi.fn()}
        onUploadImage={onUploadImage}
      />
    );

    const editButton = screen.getByRole('button', { name: '수정' });
    expect(editButton.getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(editButton);

    const editor = screen.getByRole('form', { name: '프로필 수정' });
    expect(editButton.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByLabelText('닉네임').value).toBe('테스터');
    expect(screen.getByRole('button', { name: '프로필 사진 변경' })).toBeTruthy();

    const image = new File(['profile'], 'profile.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText('프로필 사진 파일'), { target: { files: [image] } });
    await waitFor(() => expect(onUploadImage).toHaveBeenCalledWith(image));

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '새 닉네임' } });
    fireEvent.submit(editor);

    await waitFor(() => expect(onSave).toHaveBeenCalledWith('새 닉네임'));
    await waitFor(() => expect(screen.queryByRole('form', { name: '프로필 수정' })).toBeNull());
  });
});

describe('#334 T1 로그인 화면의 제공자 표시와 authorization 경로', () => {
  it('설정된 제공자만 서버가 준 순서로 표시한다', () => {
    render(<AuthView onLogin={vi.fn()} socialProviders={[GOOGLE_NOT_LINKED, KAKAO_NOT_LINKED]} onSocialLogin={vi.fn()} />);

    const buttons = screen.getAllByRole('button', { name: /로 계속하기$/ });
    expect(buttons.map((button) => button.getAttribute('aria-label'))).toEqual(['Google로 계속하기', 'Kakao로 계속하기']);
    expect(screen.queryByRole('button', { name: 'Naver로 계속하기' })).toBeNull();
  });

  it('설정된 제공자가 없으면 소셜 로그인 영역을 표시하지 않는다', () => {
    render(<AuthView onLogin={vi.fn()} socialProviders={[]} onSocialLogin={vi.fn()} />);

    expect(screen.queryByRole('button', { name: /로 계속하기$/ })).toBeNull();
    expect(screen.getByRole('button', { name: '이메일로 로그인' })).toBeTruthy();
    expect(screen.getByLabelText('이메일')).toBeTruthy();
  });

  it('제공자를 선택하면 해당 제공자로 로그인 시작을 요청한다', () => {
    const onSocialLogin = vi.fn();
    render(<AuthView onLogin={vi.fn()} socialProviders={[GOOGLE_NOT_LINKED]} onSocialLogin={onSocialLogin} />);

    fireEvent.click(screen.getByRole('button', { name: 'Google로 계속하기' }));

    expect(onSocialLogin).toHaveBeenCalledWith('GOOGLE');
  });

  it('로그인 시작 경로는 same-site authorization 경로의 소문자 제공자 값을 사용한다', () => {
    expect(socialLoginUrl('GOOGLE')).toBe('/api/auth/social/authorization/google');
    expect(socialLoginUrl('NAVER')).toBe('/api/auth/social/authorization/naver');
    expect(socialLoginUrl('KAKAO')).toBe('/api/auth/social/authorization/kakao');
  });
});

describe('#334 T2 callback 고정 결과 해석과 URL 제거', () => {
  const allowed = [
    'login-success',
    'link-success',
    'link-required',
    'link-conflict',
    'canceled',
    'invalid-state',
    'provider-unavailable',
    'failed'
  ];

  it('허용된 여덟 결과만 안내 문구로 해석한다', () => {
    allowed.forEach((value) => {
      const result = readSocialAuthResult('?socialAuth=' + value);
      expect(result, value).not.toBeNull();
      expect(typeof result.message).toBe('string');
      expect(result.message.length).toBeGreaterThan(0);
    });
  });

  it('허용 목록 밖의 결과와 provider 오류 설명은 해석하지 않는다', () => {
    expect(readSocialAuthResult('?socialAuth=unknown-result')).toBeNull();
    expect(readSocialAuthResult('?socialAuth=access_denied')).toBeNull();
    expect(readSocialAuthResult('?error=access_denied&error_description=user+denied')).toBeNull();
    expect(readSocialAuthResult('?code=authorization-code-value')).toBeNull();
    expect(readSocialAuthResult('')).toBeNull();
  });

  it('안내 문구에 provider 오류 설명이나 code를 포함하지 않는다', () => {
    const result = readSocialAuthResult('?socialAuth=failed&error_description=provider+detail&code=secret-code');

    expect(result.message).not.toContain('provider');
    expect(result.message).not.toContain('detail');
    expect(result.message).not.toContain('secret-code');
  });

  it('해석한 뒤 query string을 지우고 hash route는 남긴다', () => {
    window.history.replaceState({}, '', '/?socialAuth=login-success#/home');

    expect(consumeSocialAuthResult().message).toBeTruthy();
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('#/home');
  });

  it('허용하지 않는 query도 브라우저 주소와 히스토리에 남기지 않는다', () => {
    window.history.replaceState({}, '', '/?code=secret-code&error_description=provider+detail#/auth');

    expect(consumeSocialAuthResult()).toBeNull();
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('#/auth');
  });
});

describe('#334 T3 소셜 계정 연결 화면의 상태와 연결 시작', () => {
  it('내정보에서 연결 화면으로 들어가고 연결 수를 함께 알린다', () => {
    renderProfile([GOOGLE_NOT_LINKED, NAVER_LINKED]);

    const entry = screen.getByRole('link', { name: /소셜 계정 연결/ });
    expect(entry.getAttribute('href')).toBe('#/social-link');
    expect(entry.textContent).toContain('1개 연결됨');
  });

  it('이메일이 같아도 자동으로 합치지 않는다는 안내를 먼저 둔다', () => {
    renderSocialLink([GOOGLE_NOT_LINKED]);

    expect(screen.getByText(/이메일만 같다고 자동으로 합치지 않아요/)).toBeTruthy();
  });

  it('연결된 제공자는 상태만 표시하고 교체·해제를 제공하지 않는다', () => {
    renderSocialLink([NAVER_LINKED]);

    expect(screen.getByText('Naver')).toBeTruthy();
    expect(screen.getByText('연결됨')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Naver/ })).toBeNull();
  });

  it('미연결 제공자만 연결을 시작할 수 있다', () => {
    const onSocialLink = renderSocialLink([GOOGLE_NOT_LINKED, NAVER_LINKED]);

    fireEvent.click(screen.getByRole('button', { name: /Google/ }));

    expect(onSocialLink).toHaveBeenCalledWith('GOOGLE');
    expect(screen.queryByRole('button', { name: /Naver/ })).toBeNull();
  });

  it('설정된 제공자가 없으면 내정보에 연결 진입점을 두지 않는다', () => {
    renderProfile([]);

    expect(screen.queryByRole('link', { name: /소셜 계정 연결/ })).toBeNull();
    expect(screen.getByRole('button', { name: /로그아웃/ })).toBeTruthy();
  });
});

describe('#334 T1·T3 소셜 API 호출 계약', () => {
  beforeEach(() => clearCsrfToken());

  it('제공자 목록을 인증 선택 GET 경로로 조회한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(successfulResponse([GOOGLE_NOT_LINKED]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.getSocialProviders()).resolves.toEqual([GOOGLE_NOT_LINKED]);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/social/providers',
      expect.objectContaining({ method: 'GET', credentials: 'include' })
    );
  });

  it('연결 시작은 CSRF 토큰을 붙인 POST로 요청하고 same-site authorization 경로를 돌려준다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(successfulResponse({ headerName: 'X-CSRF-TOKEN', token: 'current-csrf-token' }))
      .mockResolvedValueOnce(successfulResponse({ authorizationUri: '/api/auth/social/authorization/google?linkNonce=abc' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.startSocialLink('GOOGLE')).resolves.toBe('/api/auth/social/authorization/google?linkNonce=abc');
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/users/me/social-accounts/google/link',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'current-csrf-token' })
      })
    );
  });
});
