package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.user.contract.SocialProvider;

/**
 * 현재 실행 환경에 설정된 소셜 제공자 한 건이다.
 *
 * <p>{@code linked}는 로그인 사용자의 현재 연결 여부이며, 비로그인은 모두 {@code false}다.
 */
public record SocialProviderItem(String provider, boolean linked) {

	public static SocialProviderItem of(SocialProvider provider, boolean linked) {
		return new SocialProviderItem(provider.name(), linked);
	}
}
