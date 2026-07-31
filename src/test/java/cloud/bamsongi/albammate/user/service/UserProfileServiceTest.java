package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.dto.UserProfileResponse;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

	@Mock
	private UserRepository userRepository;
	@InjectMocks
	private UserProfileService userProfileService;

	@Test
	void 현재_사용자의_프로필은_사용자_요약으로_조회한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "닉네임");
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));

		assertEquals(new UserProfileResponse(null, "닉네임"), userProfileService.findProfile(7L));
		verify(userRepository).findById(7L);
	}

	@Test
	void 현재_사용자의_닉네임을_엔티티_도메인_메서드로_변경한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));

		UserProfileResponse profile = userProfileService.changeNickname(
			7L, userNickname(" 새 닉네임 "));

		assertEquals("새 닉네임", user.getNickname());
		assertEquals(new UserProfileResponse(null, "새 닉네임"), profile);
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
		assertEquals(new UserProfileResponse(null, nickname), profile);
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

	private static UserNickname userNickname(String value) {
		return UserNickname.from(value).orElseThrow();
	}
}
