package cloud.bamsongi.albammate.user.contract;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** 다른 모듈이 공개 사용자 정보를 조회할 때 사용하는 최소 계약이다. */
public interface UserQuery {

	/**
	 * 사용자 ID로 공개 표시용 닉네임만 조회한다.
	 *
	 * @param userId 알밤메이트 내부 사용자 ID
	 * @return 사용자가 없으면 {@link Optional#empty()}
	 */
	Optional<String> findNicknameById(Long userId);

	/**
	 * 여러 사용자 ID의 공개 표시용 닉네임을 조회한다.
	 *
	 * @param userIds 알밤메이트 내부 사용자 ID
	 * @return 빈 입력이면 빈 Map, 중복 ID는 하나의 키로 합치며 존재하지 않는 사용자 ID는 제외한 닉네임
	 */
	Map<Long, String> findNicknamesByIds(Collection<Long> userIds);

	/**
	 * 사용자 ID로 공개 표시용 닉네임과 프로필 이미지 URL을 함께 조회한다.
	 *
	 * @param userId 알밤메이트 내부 사용자 ID
	 * @return 사용자가 없으면 {@link Optional#empty()}
	 */
	Optional<UserSummary> findUserSummaryById(Long userId);

	/**
	 * 여러 사용자 ID의 공개 표시용 닉네임과 프로필 이미지 URL을 함께 조회한다.
	 *
	 * @param userIds 알밤메이트 내부 사용자 ID
	 * @return 빈 입력이면 빈 Map, 중복 ID는 하나의 키로 합치며 존재하지 않는 사용자 ID는 제외한 요약
	 */
	Map<Long, UserSummary> findUserSummariesByIds(Collection<Long> userIds);

	/** 공개 표시용 닉네임과 프로필 이미지 URL 묶음이다. 프로필 이미지가 없으면 {@code profileImageUrl}은 {@code null}이다. */
	record UserSummary(String nickname, String profileImageUrl) {
	}
}
