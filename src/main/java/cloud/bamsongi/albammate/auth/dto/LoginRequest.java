package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.service.LoginCommand;
import cloud.bamsongi.albammate.auth.validation.ValidEmail;
import cloud.bamsongi.albammate.auth.validation.ValidPassword;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import jakarta.validation.constraints.NotNull;

/** 로그인 HTTP 요청 원문을 표현한다. */
public record LoginRequest(
	@NotNull @ValidEmail
	String email, @NotNull @ValidPassword
	String password) {

	/** {@code @ValidEmail}이 {@link UserEmail#from(String)}과 같은 규칙을 쓰므로 검증 통과 뒤에는 항상 값이 있다. */
	public LoginCommand normalize() {
		return new LoginCommand(UserEmail.from(email).orElseThrow(), password);
	}

	@Override
	public String toString() {
		return "LoginRequest[email=" + email + ", password=<redacted>]";
	}
}
