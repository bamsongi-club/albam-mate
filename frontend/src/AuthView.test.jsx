import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './api';
import { AuthView } from './main';

afterEach(cleanup);

function renderSignup(onSignup) {
  render(<AuthView onLogin={vi.fn()} onSignup={onSignup} />);
  fireEvent.click(screen.getByRole('button', { name: '회원가입' }));
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '테스터' } });
  return screen.getByLabelText('비밀번호');
}

describe('#387 T1 회원가입 비밀번호 Unicode 하한', () => {
  it('이모지 14자는 회원가입 요청 전에 거절한다', () => {
    const onSignup = vi.fn().mockResolvedValue(false);
    const passwordInput = renderSignup(onSignup);

    fireEvent.change(passwordInput, { target: { value: '😀'.repeat(14) } });
    fireEvent.submit(passwordInput.closest('form'));

    expect(onSignup).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(passwordInput);
    expect(screen.getByRole('alert').textContent).toContain('15자 이상');
  });

  it('이모지 15자는 다른 상한을 넘지 않으면 회원가입 요청으로 전달한다', () => {
    const onSignup = vi.fn().mockResolvedValue(false);
    const passwordInput = renderSignup(onSignup);
    const password = '😀'.repeat(15);

    fireEvent.change(passwordInput, { target: { value: password } });
    fireEvent.submit(passwordInput.closest('form'));

    expect(onSignup).toHaveBeenCalledWith({
      email: 'user@example.com',
      nickname: '테스터',
      password
    });
  });
});

describe('#387 T2·T3 회원가입 비밀번호 상한', () => {
  it('영문 64자는 회원가입 요청으로 전달한다', () => {
    const onSignup = vi.fn().mockResolvedValue(false);
    const passwordInput = renderSignup(onSignup);
    const password = 'a'.repeat(64);

    fireEvent.change(passwordInput, { target: { value: password } });
    fireEvent.submit(passwordInput.closest('form'));

    expect(onSignup).toHaveBeenCalledWith({
      email: 'user@example.com',
      nickname: '테스터',
      password
    });
  });

  it('영문 65자는 입력을 자르지 않고 회원가입 요청 전에 거절한다', () => {
    const onSignup = vi.fn().mockResolvedValue(false);
    const passwordInput = renderSignup(onSignup);
    const password = 'a'.repeat(65);

    fireEvent.change(passwordInput, { target: { value: password } });
    fireEvent.submit(passwordInput.closest('form'));

    expect(passwordInput.value).toBe(password);
    expect(onSignup).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toContain('64자를 넘어');
  });

  it('UTF-8 72바이트인 한글 24자는 회원가입 요청으로 전달한다', () => {
    const onSignup = vi.fn().mockResolvedValue(false);
    const passwordInput = renderSignup(onSignup);
    const password = '가'.repeat(24);

    fireEvent.change(passwordInput, { target: { value: password } });
    fireEvent.submit(passwordInput.closest('form'));

    expect(onSignup).toHaveBeenCalledWith({
      email: 'user@example.com',
      nickname: '테스터',
      password
    });
  });

  it('UTF-8 72바이트를 넘는 한글 25자는 회원가입 요청 전에 거절한다', () => {
    const onSignup = vi.fn().mockResolvedValue(false);
    const passwordInput = renderSignup(onSignup);

    fireEvent.change(passwordInput, { target: { value: '가'.repeat(25) } });
    fireEvent.submit(passwordInput.closest('form'));

    expect(onSignup).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toContain('한글이나 이모지는 영문보다 길이를 많이 차지해요.');
  });
});

describe('#387 T4·T5 회원가입 제출 경계 회귀', () => {
  it('서버 검증 오류를 기존 오류 영역에 표시한다', async () => {
    const onSignup = vi.fn().mockRejectedValue(new ApiError({
      status: 400,
      code: 'VALIDATION_ERROR',
      message: '서버 검증 오류'
    }));
    const passwordInput = renderSignup(onSignup);

    fireEvent.change(passwordInput, { target: { value: 'a'.repeat(15) } });
    fireEvent.submit(passwordInput.closest('form'));

    expect(await screen.findByText('서버 검증 오류')).toBeTruthy();
  });

  it('로그인 비밀번호는 회원가입 길이 검증을 적용하지 않는다', () => {
    const onLogin = vi.fn().mockResolvedValue(undefined);
    render(<AuthView onLogin={onLogin} onSignup={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'short' } });

    fireEvent.submit(screen.getByLabelText('비밀번호').closest('form'));

    expect(onLogin).toHaveBeenCalledWith({ email: 'user@example.com', password: 'short' });
  });
});
