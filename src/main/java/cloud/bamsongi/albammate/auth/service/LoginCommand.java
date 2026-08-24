package cloud.bamsongi.albammate.auth.service;

import java.util.Objects;

import cloud.bamsongi.albammate.user.contract.UserEmail;

/**
 * 로그인 유스케이스에 전달하는 검증된 내부 입력이다.
 *
 * <p>비밀번호만 값 타입이 아니다. {@code RawPassword}는 회원가입 최소 길이를 강제하므로, 그 정책 이전에 만들어진 짧은 비밀번호
 * 계정이 로그인하지 못하게 된다. 로그인 비밀번호의 상한은 HTTP 경계의 {@code @ValidPassword}가 검사한다.
 */
public record LoginCommand(UserEmail email, String password) {

	public LoginCommand {
		Objects.requireNonNull(email, "email");
		Objects.requireNonNull(password, "password");
	}

	@Override
	public String toString() {
		return "LoginCommand[email=" + email.value() + ", password=<redacted>]";
	}
}
