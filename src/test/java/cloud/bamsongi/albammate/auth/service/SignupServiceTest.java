package cloud.bamsongi.albammate.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

	@Mock
	private AuthenticationRequestLimiter requestLimiter;

	@Mock
	private UserAccountService userAccountService;

	@Test
	void IP_제한을_먼저_확인하고_사용자_모듈을_호출한다() {
		SignupService service = new SignupService(requestLimiter, userAccountService);
		UserAccount account = new UserAccount(7L, "닉네임");
		CreateUserAccountCommand command = command("user@example.com", "123456789012345", "닉네임");
		when(userAccountService.createAccount(command)).thenReturn(account);

		UserAccount result = service.signup(command, "203.0.113.21");

		assertEquals(account, result);
		InOrder inOrder = org.mockito.Mockito.inOrder(requestLimiter, userAccountService);
		inOrder.verify(requestLimiter).requireSignupAllowed("203.0.113.21");
		inOrder.verify(userAccountService).createAccount(command);
	}

	@Test
	void IP_제한을_초과하면_사용자_모듈을_호출하지_않는다() {
		SignupService service = new SignupService(requestLimiter, userAccountService);
		doThrow(new RateLimitExceededException(1))
			.when(requestLimiter)
			.requireSignupAllowed("203.0.113.22");

		assertThrows(
			RateLimitExceededException.class,
			() -> service.signup(
				command("user@example.com", "123456789012345", "닉네임"),
				"203.0.113.22"));

		verify(userAccountService, never()).createAccount(org.mockito.ArgumentMatchers.any());
	}

	private CreateUserAccountCommand command(String email, String password, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(password).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}
}
