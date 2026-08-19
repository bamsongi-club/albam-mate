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

	@Test
	void 사용자_요약_단건_조회는_닉네임과_프로필_이미지_URL을_함께_반환하고_이미지가_없으면_null이다() {
		User withImage = User.create("with-image@example.com", "{bcrypt}hash", "방장");
		withImage.changeProfileImageUrl("https://cdn.example.com/host.png");
		User withoutImage = User.create("without-image@example.com", "{bcrypt}hash", "참가자");
		List<User> users = userRepository.saveAllAndFlush(List.of(withImage, withoutImage));

		UserRepository.UserSummaryProjection withImageSummary = userRepository
			.findUserSummaryProjectionById(users.getFirst().getId())
			.orElseThrow();
		UserRepository.UserSummaryProjection withoutImageSummary = userRepository
			.findUserSummaryProjectionById(users.getLast().getId())
			.orElseThrow();

		assertEquals("방장", withImageSummary.getNickname());
		assertEquals("https://cdn.example.com/host.png", withImageSummary.getProfileImageUrl());
		assertEquals("참가자", withoutImageSummary.getNickname());
		assertEquals(null, withoutImageSummary.getProfileImageUrl());
	}

	@Test
	void 여러_사용자_요약은_ID와_닉네임과_프로필_이미지_URL_projection으로_존재하는_사용자만_반환한다() {
		User withImage = User.create("with-image-multi@example.com", "{bcrypt}hash", "방장");
		withImage.changeProfileImageUrl("https://cdn.example.com/host.png");
		User withoutImage = User.create("without-image-multi@example.com", "{bcrypt}hash", "참가자");
		List<User> users = userRepository.saveAllAndFlush(List.of(withImage, withoutImage));

		Map<Long, UserRepository.UserSummaryProjection> summariesById = userRepository
			.findUserSummaryProjectionsByIds(
				List.of(users.getFirst().getId(), users.getLast().getId(), users.getFirst().getId(), 999_999L))
			.stream()
			.collect(Collectors.toMap(UserRepository.UserSummaryProjection::getId, projection -> projection));

		assertEquals(2, summariesById.size());
		assertEquals("방장", summariesById.get(users.getFirst().getId()).getNickname());
		assertEquals(
			"https://cdn.example.com/host.png", summariesById.get(users.getFirst().getId()).getProfileImageUrl());
		assertEquals("참가자", summariesById.get(users.getLast().getId()).getNickname());
		assertEquals(null, summariesById.get(users.getLast().getId()).getProfileImageUrl());
	}
}
