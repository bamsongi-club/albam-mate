package cloud.bamsongi.albammate.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

/**
 * PostgreSQL 위에서 실제로 실행되는 사용자 요약 projection(닉네임·프로필 이미지 URL) 쿼리를 검증한다. issue #812
 * T3(모임 상세 host·participants)와 T6(프로필 이미지 없는 사용자의 null)이 의존하는 repository 계층 근거다.
 */
@Testcontainers
@SpringBootTest
class UserSummaryProjectionPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_user_summary_projection_test");

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void tearDown() {
		userRepository.deleteAll();
	}

	@Test
	void T3_사용자_요약_단건_조회는_닉네임과_프로필_이미지_URL을_함께_반환한다() {
		User user = User.create("summary-single@example.com", "{bcrypt}hash", "방장");
		user.changeProfileImageUrl("https://cdn.example.com/host.png");
		User saved = userRepository.saveAndFlush(user);

		UserRepository.UserSummaryProjection summary = userRepository
			.findUserSummaryProjectionById(saved.getId())
			.orElseThrow();

		assertEquals("방장", summary.getNickname());
		assertEquals("https://cdn.example.com/host.png", summary.getProfileImageUrl());
	}

	@Test
	void T6_프로필_이미지가_없는_사용자의_요약_profileImageUrl은_null이다() {
		User user = User.create("summary-no-image@example.com", "{bcrypt}hash", "참가자");
		User saved = userRepository.saveAndFlush(user);

		UserRepository.UserSummaryProjection summary = userRepository
			.findUserSummaryProjectionById(saved.getId())
			.orElseThrow();

		assertEquals("참가자", summary.getNickname());
		assertNull(summary.getProfileImageUrl());
	}

	@Test
	void T3_여러_사용자_요약_조회는_ID로_존재하는_사용자만_닉네임과_프로필_이미지_URL을_반환한다() {
		User withImage = User.create("summary-multi-with-image@example.com", "{bcrypt}hash", "방장");
		withImage.changeProfileImageUrl("https://cdn.example.com/host.png");
		User withoutImage = User.create("summary-multi-without-image@example.com", "{bcrypt}hash", "참가자");
		List<User> saved = userRepository.saveAllAndFlush(List.of(withImage, withoutImage));

		Map<Long, UserRepository.UserSummaryProjection> summariesById = userRepository
			.findUserSummaryProjectionsByIds(
				List.of(saved.getFirst().getId(), saved.getLast().getId(), saved.getFirst().getId(), 999_999L))
			.stream()
			.collect(Collectors.toMap(UserRepository.UserSummaryProjection::getId, projection -> projection));

		assertEquals(2, summariesById.size());
		assertEquals("방장", summariesById.get(saved.getFirst().getId()).getNickname());
		assertEquals(
			"https://cdn.example.com/host.png", summariesById.get(saved.getFirst().getId()).getProfileImageUrl());
		assertEquals("참가자", summariesById.get(saved.getLast().getId()).getNickname());
		assertNull(summariesById.get(saved.getLast().getId()).getProfileImageUrl());
	}
}
