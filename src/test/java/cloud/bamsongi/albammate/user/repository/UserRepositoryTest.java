package cloud.bamsongi.albammate.user.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;
import cloud.bamsongi.albammate.user.contract.UserPublicProfile;
import cloud.bamsongi.albammate.user.entity.User;

@DataJpaTest
@Import({JpaConfig.class, TimeConfig.class})
class UserRepositoryTest {

	@Autowired
	private UserRepository userRepository;

	@Test
	void 여러_사용자_닉네임은_ID와_닉네임_projection으로_존재하는_사용자만_반환한다() {
		List<User> users = userRepository.saveAllAndFlush(
			List.of(
				User.create("host@example.com", "{bcrypt}hash", "방장"),
				User.create("participant@example.com", "{bcrypt}hash", "참가자")));

		Map<Long, String> nicknamesById = userRepository.findNicknameProjectionsByIds(
			List.of(users.getFirst().getId(), users.getLast().getId(), users.getFirst().getId(), 999_999L))
			.stream()
			.collect(
				Collectors.toMap(
					UserRepository.UserNicknameProjection::getId,
					UserRepository.UserNicknameProjection::getNickname));

		assertEquals(
			Map.of(users.getFirst().getId(), "방장", users.getLast().getId(), "참가자"), nicknamesById);
	}

	@Test
	void 공개_프로필_projection은_이미지가_없으면_null로_반환한다() {
		User userWithoutImage = userRepository.saveAndFlush(
			User.create("no-image@example.com", "{bcrypt}hash", "이미지 없음"));
		User userWithImage = userRepository.saveAndFlush(
			User.createSocial("with-image@example.com", "이미지 있음", "https://example.com/profile.png"));

		List<UserPublicProfile> profiles = userRepository.findPublicProfilesByIds(
			List.of(userWithoutImage.getId(), userWithImage.getId(), 999_999L));

		Map<Long, UserPublicProfile> profilesById = profiles.stream()
			.collect(Collectors.toMap(UserPublicProfile::userId, profile -> profile));
		assertEquals(
			Map.of(
				userWithoutImage.getId(), new UserPublicProfile(userWithoutImage.getId(), "이미지 없음", null),
				userWithImage.getId(),
				new UserPublicProfile(userWithImage.getId(), "이미지 있음", "https://example.com/profile.png")),
			profilesById);
		assertTrue(userRepository.findPublicProfileById(999_999L).isEmpty());
	}
}
