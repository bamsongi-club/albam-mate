package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.password.PasswordHashConcurrencyLimiter;
import cloud.bamsongi.albammate.global.security.password.PasswordHashExecutor;
import cloud.bamsongi.albammate.global.security.password.PasswordHashPermit;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserCredentials;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.exception.EmailAlreadyExistsException;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Test
	void 이메일을_먼저_중복확인하고_슬롯_안에서_해시해_계정을_저장한다() {
		AlwaysAvailableLimiter limiter = new AlwaysAvailableLimiter();
		UserAccountApplicationService service = new UserAccountApplicationService(
			userRepository, passwordEncoder, new PasswordHashExecutor(limiter));
		when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
		when(passwordEncoder.encode("123456789012345")).thenReturn("{bcrypt}encoded");
		when(userRepository.saveAndFlush(any(User.class)))
			.thenAnswer(
				invocation -> {
					User user = invocation.getArgument(0);
					setId(user, 9L);
					return user;
				});

		UserAccount account = service.createAccount(command("user@example.com", "123456789012345", "닉네임"));

		assertEquals(new UserAccount(9L, "닉네임"), account);
		verify(passwordEncoder).encode("123456789012345");
		verify(userRepository).saveAndFlush(any(User.class));
		assertEquals(0, limiter.currentConcurrent());
	}

	@Test
	void 사전_중복이면_해시와_저장을_수행하지_않는다() {
		AlwaysAvailableLimiter limiter = new AlwaysAvailableLimiter();
		UserAccountApplicationService service = new UserAccountApplicationService(
			userRepository, passwordEncoder, new PasswordHashExecutor(limiter));
		when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

		assertThrows(
			EmailAlreadyExistsException.class,
			() -> service.createAccount(command("user@example.com", "123456789012345", "닉네임")));

		verify(passwordEncoder, never()).encode(any());
		verify(userRepository, never()).saveAndFlush(any());
		assertEquals(0, limiter.currentConcurrent());
	}

	@Test
	void 직접_호출도_사용자_값_타입으로_이메일과_닉네임을_정규화한다() {
		AlwaysAvailableLimiter limiter = new AlwaysAvailableLimiter();
		UserAccountApplicationService service = new UserAccountApplicationService(
			userRepository, passwordEncoder, new PasswordHashExecutor(limiter));
		when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
		when(passwordEncoder.encode("123456789012345")).thenReturn("{bcrypt}encoded");
		when(userRepository.saveAndFlush(any(User.class)))
			.thenAnswer(
				invocation -> {
					User user = invocation.getArgument(0);
					setId(user, 10L);
					return user;
				});

		UserAccount account = service.createAccount(command(" User@Example.COM ", "123456789012345", " 닉네임 "));

		assertEquals(new UserAccount(10L, "닉네임"), account);
		verify(userRepository).existsByEmail("user@example.com");
		verify(userRepository)
			.saveAndFlush(
				org.mockito.ArgumentMatchers.argThat(
					user -> user.getEmail().equals("user@example.com")
						&& user.getNickname().equals("닉네임")));
	}

	@Test
	void 사용자_값_타입이_잘못된_이메일과_제어문자_닉네임을_계정_생성_전에_거절한다() {
		assertTrue(UserEmail.from("not-an-email").isEmpty());
		assertTrue(UserNickname.from("닉\n네임").isEmpty());
	}

	@Test
	void 가입_비밀번호_하한_미달은_해시하지_않고_거절한다() {
		assertInvalidPasswordIsRejectedBeforeHashing("1234567");
	}

	@Test
	void 직접_호출은_긴_비밀번호를_해시_슬롯_획득_전에_거절한다() {
		assertInvalidPasswordIsRejectedBeforeHashing("a".repeat(65));
	}

	@Test
	void 허용되지_않은_문자가_있는_가입_비밀번호는_해시하지_않고_거절한다() {
		assertInvalidPasswordIsRejectedBeforeHashing("가".repeat(8));
	}

	@Test
	void DB_unique_경쟁도_EMAIL_ALREADY_EXISTS로_변환한다() {
		AlwaysAvailableLimiter limiter = new AlwaysAvailableLimiter();
		UserAccountApplicationService service = new UserAccountApplicationService(
			userRepository, passwordEncoder, new PasswordHashExecutor(limiter));
		when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
		when(passwordEncoder.encode("123456789012345")).thenReturn("{bcrypt}encoded");
		when(userRepository.saveAndFlush(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("unique email"));

		assertThrows(
			EmailAlreadyExistsException.class,
			() -> service.createAccount(command("user@example.com", "123456789012345", "닉네임")));

		assertEquals(0, limiter.currentConcurrent());
	}

	@Test
	void 해시_슬롯이_없으면_사용자_생성을_시작하지_않는다() {
		NoSlotLimiter limiter = new NoSlotLimiter();
		UserAccountApplicationService service = new UserAccountApplicationService(
			userRepository, passwordEncoder, new PasswordHashExecutor(limiter));

		assertThrows(
			RateLimitExceededException.class,
			() -> service.createAccount(command("user@example.com", "123456789012345", "닉네임")));

		verify(userRepository, never()).existsByEmail(any());
		verify(passwordEncoder, never()).encode(any());
	}

	@Test
	void 유효한_이메일의_로그인_자격증명을_반환한다() {
		UserAccountApplicationService service = service();
		User user = User.create("user@example.com", "{bcrypt}encoded", "닉네임");
		setId(user, 13L);
		when(userRepository.findByEmailAndPasswordHashIsNotNull("user@example.com"))
			.thenReturn(Optional.of(user));

		Optional<UserCredentials> credentials = service.findCredentialsByEmail(
			UserEmail.from(" User@Example.COM ").orElseThrow());

		assertEquals(Optional.of(new UserCredentials(13L, "닉네임", "{bcrypt}encoded")), credentials);
		verify(userRepository).findByEmailAndPasswordHashIsNotNull("user@example.com");
	}

	@Test
	void 자격증명이_없으면_빈_결과를_반환한다() {
		UserAccountApplicationService service = service();
		when(userRepository.findByEmailAndPasswordHashIsNotNull("user@example.com"))
			.thenReturn(Optional.empty());

		assertTrue(
			service.findCredentialsByEmail(UserEmail.from("user@example.com").orElseThrow())
				.isEmpty());
	}

	@Test
	void 비정상_ID나_빈_해시는_사용자_조회_전에_거절한다() {
		UserAccountApplicationService service = service();

		assertThrows(
			IllegalArgumentException.class, () -> service.updatePasswordHash(null, "hash"));
		assertThrows(IllegalArgumentException.class, () -> service.updatePasswordHash(0L, "hash"));
		assertThrows(IllegalArgumentException.class, () -> service.updatePasswordHash(1L, null));
		assertThrows(IllegalArgumentException.class, () -> service.updatePasswordHash(1L, ""));

		verify(userRepository, never()).findById(any());
	}

	@Test
	void 갱신할_사용자가_없으면_명시적으로_실패한다() {
		UserAccountApplicationService service = service();
		when(userRepository.findById(14L)).thenReturn(Optional.empty());

		assertThrows(
			IllegalStateException.class, () -> service.updatePasswordHash(14L, "new-hash"));
	}

	@Test
	void 찾은_사용자의_비밀번호_해시를_교체한다() {
		UserAccountApplicationService service = service();
		User user = User.create("user@example.com", "old-hash", "닉네임");
		when(userRepository.findById(15L)).thenReturn(Optional.of(user));

		service.updatePasswordHash(15L, "new-hash");

		assertEquals("new-hash", user.getPasswordHash());
	}

	private void assertInvalidPasswordIsRejectedBeforeHashing(String password) {
		assertTrue(RawPassword.from(password).isEmpty());
	}

	private static void setId(User user, long id) {
		try {
			Field field = User.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(user, id);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private UserAccountApplicationService service() {
		return new UserAccountApplicationService(
			userRepository,
			passwordEncoder,
			new PasswordHashExecutor(new AlwaysAvailableLimiter()));
	}

	private static CreateUserAccountCommand command(
		String email, String password, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(password).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}

	private static final class AlwaysAvailableLimiter implements PasswordHashConcurrencyLimiter {

		private int current;

		@Override
		public Optional<PasswordHashPermit> tryAcquire() {
			current++;
			return Optional.of(
				() -> {
					current--;
				});
		}

		public int currentConcurrent() {
			return current;
		}
	}

	private static final class NoSlotLimiter implements PasswordHashConcurrencyLimiter {

		@Override
		public Optional<PasswordHashPermit> tryAcquire() {
			return Optional.empty();
		}
	}
}
