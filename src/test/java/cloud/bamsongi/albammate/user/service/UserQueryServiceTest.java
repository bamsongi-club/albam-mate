package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.user.contract.UserPublicProfile;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceTest {

	@Mock
	private UserRepository userRepository;
	@InjectMocks
	private UserQueryService userQueryService;

	@Test
	void 닉네임_단건_조회는_공개_필드_projection만_위임한다() {
		when(userRepository.findNicknameById(42L)).thenReturn(Optional.of("방장"));

		assertEquals(Optional.of("방장"), userQueryService.findNicknameById(42L));
		verify(userRepository).findNicknameById(42L);
	}

	@Test
	void 존재하지_않는_사용자는_empty다() {
		when(userRepository.findNicknameById(404L)).thenReturn(Optional.empty());

		assertTrue(userQueryService.findNicknameById(404L).isEmpty());
	}

	@Test
	void 공개_프로필은_닉네임과_프로필_이미지만_단건과_일괄로_반환한다() {
		UserPublicProfile host = new UserPublicProfile(42L, "방장", null);
		UserPublicProfile participant = new UserPublicProfile(77L, "참가자", "https://example.com/profile.png");
		when(userRepository.findPublicProfileById(42L)).thenReturn(Optional.of(host));
		when(userRepository.findPublicProfilesByIds(List.of(42L, 77L, 42L, 404L)))
			.thenReturn(List.of(host, participant));

		assertEquals(Optional.of(host), userQueryService.findPublicProfileById(42L));
		assertEquals(
			Map.of(42L, host, 77L, participant),
			userQueryService.findPublicProfilesByIds(List.of(42L, 77L, 42L, 404L)));

		verify(userRepository).findPublicProfileById(42L);
		verify(userRepository).findPublicProfilesByIds(List.of(42L, 77L, 42L, 404L));
		verify(userRepository, never()).findById(42L);
	}

	@Test
	void 빈_공개_프로필_ID_컬렉션은_저장소를_조회하지_않는다() {
		assertEquals(Map.of(), userQueryService.findPublicProfilesByIds(List.of()));

		verifyNoInteractions(userRepository);
	}

	@Test
	void 빈_ID_컬렉션은_저장소를_조회하지_않는다() {
		assertEquals(Map.of(), userQueryService.findNicknamesByIds(List.of()));

		verifyNoInteractions(userRepository);
	}

	@Test
	void 중복_ID는_하나의_키로_합치고_없는_ID는_제외한다() {
		List<Long> userIds = List.of(42L, 77L, 42L, 404L);
		UserRepository.UserNicknameProjection host = nicknameProjection(42L, "방장");
		UserRepository.UserNicknameProjection participant = nicknameProjection(77L, "참가자");
		when(userRepository.findNicknameProjectionsByIds(userIds)).thenReturn(List.of(host, participant));

		assertEquals(
			Map.of(42L, "방장", 77L, "참가자"),
			userQueryService.findNicknamesByIds(userIds));
		verify(userRepository).findNicknameProjectionsByIds(userIds);
		verify(userRepository, never()).findAllById(userIds);
	}

	private UserRepository.UserNicknameProjection nicknameProjection(long userId, String nickname) {
		UserRepository.UserNicknameProjection projection = org.mockito.Mockito.mock(
			UserRepository.UserNicknameProjection.class);
		when(projection.getId()).thenReturn(userId);
		when(projection.getNickname()).thenReturn(nickname);
		return projection;
	}
}
