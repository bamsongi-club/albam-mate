package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.user.contract.SocialProvider;

/**
 * 현재 실행 환경에 설정된 소셜 제공자 한 건이다.
 *
 * <p>{@code linked}는 로그인 사용자의 현재 연결 여부이며, 비로그인은 모두 {@code false}다. 연결 상태를 읽는 계약은 로그인 사용자의
 * 명시적 연결(AUTH-05c)이 담당하므로 지금은 모든 항목이 {@code false}다.
 */
public record SocialProviderItem(String provider, boolean linked) {

	public static SocialProviderItem notLinked(SocialProvider provider) {
		return new SocialProviderItem(provider.name(), false);
	}
}
