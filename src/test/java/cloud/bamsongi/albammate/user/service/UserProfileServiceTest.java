package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.dto.UserProfileResponse;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

	@Mock
	private UserRepository userRepository;
	@Mock
	private ProfileImageStorage profileImageStorage;
	@Mock
	private PlatformTransactionManager transactionManager;
	@InjectMocks
	private UserProfileService userProfileService;

	@Test
	void 현재_사용자의_프로필은_사용자_요약으로_조회한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "닉네임");
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));

		assertEquals(new UserProfileResponse(null, "닉네임", null), userProfileService.findProfile(7L));
		verify(userRepository).findById(7L);
	}

	@Test
	void 현재_사용자의_닉네임을_엔티티_도메인_메서드로_변경한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));

		UserProfileResponse profile = userProfileService.changeNickname(
			7L, userNickname(" 새 닉네임 "));

		assertEquals("새 닉네임", user.getNickname());
		assertEquals(new UserProfileResponse(null, "새 닉네임", null), profile);
	}

	@Test
	void 닉네임이_없으면_사용자_조회_전에_거절한다() {
		assertThrows(
			NullPointerException.class,
			() -> userProfileService.changeNickname(7L, null));

		verifyNoInteractions(userRepository);
	}

	@Test
	void 직접_호출에서_50_유니코드_코드포인트_닉네임은_허용한다() {
		String nickname = "😀".repeat(50);
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));

		UserProfileResponse profile = userProfileService.changeNickname(
			7L, userNickname(nickname));

		assertEquals(nickname, user.getNickname());
		assertEquals(new UserProfileResponse(null, nickname, null), profile);
	}

	@Test
	void 세션의_사용자가_더이상_없으면_미인증으로_변환한다() {
		when(userRepository.findById(7L)).thenReturn(Optional.empty());

		assertThrows(
			UnauthenticatedException.class,
			() -> userProfileService.findProfile(7L));
	}

	@Test
	void 프로필_조회에서_0과_음수_ID는_조회_없이_미인증으로_변환한다() {
		assertThrows(
			UnauthenticatedException.class,
			() -> userProfileService.findProfile(0L));
		assertThrows(
			UnauthenticatedException.class,
			() -> userProfileService.findProfile(-1L));

		verifyNoInteractions(userRepository);
	}

	@Test
	void 닉네임_변경에서_삭제된_사용자는_미인증으로_변환하고_엔티티를_바꾸지_않는다() {
		when(userRepository.findById(7L)).thenReturn(Optional.empty());

		assertThrows(
			UnauthenticatedException.class,
			() -> userProfileService.changeNickname(7L, userNickname("새 닉네임")));
	}

	@Test
	void 닉네임_변경에서_0과_음수_ID는_조회나_상태변경_없이_미인증이다() {
		assertThrows(
			UnauthenticatedException.class,
			() -> userProfileService.changeNickname(0L, userNickname("새 닉네임")));
		assertThrows(
			UnauthenticatedException.class,
			() -> userProfileService.changeNickname(-1L, userNickname("새 닉네임")));

		verifyNoInteractions(userRepository);
	}

	@Test
	void 프로필_이미지를_업로드한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");
		when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
		when(profileImageStorage.store(eq(7L), any(), any(), any())).thenReturn("new-url");

		UserProfileResponse profile = userProfileService.uploadProfileImage(7L, InputStream.nullInputStream(), "a.png",
			"image/png");

		assertEquals("new-url", user.getProfileImageUrl());
		assertEquals(new UserProfileResponse(null, "이전 닉네임", "new-url"), profile);
	}

	@Test
	void 프로필_이미지_저장은_사용자_행_잠금과_DB_트랜잭션_전에_실행한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");
		when(profileImageStorage.store(eq(7L), any(), any(), any())).thenReturn("new-url");
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

		userProfileService.uploadProfileImage(7L, InputStream.nullInputStream(), "a.png", "image/png");

		InOrder inOrder = inOrder(profileImageStorage, transactionManager, userRepository);
		inOrder.verify(profileImageStorage).store(eq(7L), any(), any(), any());
		inOrder.verify(transactionManager).getTransaction(any());
		inOrder.verify(userRepository).findByIdForUpdate(7L);
	}

	@Test
	void 프로필_이미지_업로드시_사용자가_없으면_저장한_파일을_지우고_미인증으로_변환한다() {
		when(profileImageStorage.store(eq(7L), any(), any(), any())).thenReturn("new-url");
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

		assertThrows(
			UnauthenticatedException.class,
			() -> userProfileService.uploadProfileImage(7L, InputStream.nullInputStream(), "a.png", "image/png"));

		verify(profileImageStorage).delete("new-url");
	}

	@Test
	void 프로필_이미지_업로드의_커밋_실패는_새_파일을_삭제하고_이전_파일을_보존한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");
		user.changeProfileImageUrl("old-url");
		when(profileImageStorage.store(eq(7L), any(), any(), any())).thenReturn("new-url");
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		org.mockito.Mockito.doThrow(new TransactionSystemException("commit failure"))
			.when(transactionManager)
			.commit(any());
		when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

		assertThrows(
			TransactionSystemException.class,
			() -> userProfileService.uploadProfileImage(7L, InputStream.nullInputStream(), "a.png", "image/png"));

		verify(profileImageStorage).delete("new-url");
		verify(profileImageStorage, never()).delete("old-url");
	}

	@Test
	void 프로필_이미지_교체는_커밋_성공_뒤에만_이전_파일을_삭제한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");
		user.changeProfileImageUrl("old-url");
		when(profileImageStorage.store(eq(7L), any(), any(), any())).thenReturn("new-url");
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

		userProfileService.uploadProfileImage(7L, InputStream.nullInputStream(), "a.png", "image/png");

		InOrder inOrder = inOrder(profileImageStorage, transactionManager, userRepository);
		inOrder.verify(profileImageStorage).store(eq(7L), any(), any(), any());
		inOrder.verify(transactionManager).getTransaction(any());
		inOrder.verify(userRepository).findByIdForUpdate(7L);
		inOrder.verify(transactionManager).commit(any());
		inOrder.verify(profileImageStorage).delete("old-url");
	}

	@Test
	void 프로필_이미지를_삭제한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");
		user.changeProfileImageUrl("old-url");
		when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

		UserProfileResponse profile = userProfileService.removeProfileImage(7L);

		assertEquals(null, user.getProfileImageUrl());
		assertEquals(new UserProfileResponse(null, "이전 닉네임", null), profile);
		verify(profileImageStorage).delete("old-url");
	}

	@Test
	void 프로필_이미지_삭제시_사용자가_없으면_미인증으로_변환한다() {
		when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

		assertThrows(
			UnauthenticatedException.class,
			() -> userProfileService.removeProfileImage(7L));
	}

	private static UserNickname userNickname(String value) {
		return UserNickname.from(value).orElseThrow();
	}
}
