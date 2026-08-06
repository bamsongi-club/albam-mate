package cloud.bamsongi.albammate.auth.social;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

/**
 * 제공자 응답을 사용자 모듈이 받는 공통 외부 신원으로 바꾼다.
 *
 * <p>이메일은 제공자별 신뢰 조건을 통과할 때만 넘기고, 조건이 거짓·누락이거나 형식·길이 계약을 통과하지 못하면 이메일 없음으로 만든다. 닉네임도
 * 계약을 통과하지 못하면 없음으로 넘기며, 제공자별 기본 표시명은 사용자 모듈이 정한다.
 */
@Component
public final class SocialIdentityMapper {

	/**
	 * @throws IllegalArgumentException 제공자가 필수 subject를 주지 않았을 때
	 */
	public SocialIdentity map(SocialProvider provider, Map<String, Object> attributes) {
		return switch (provider) {
			case GOOGLE -> google(attributes);
			case NAVER -> naver(attributes);
			case KAKAO -> kakao(attributes);
		};
	}

	private SocialIdentity google(Map<String, Object> attributes) {
		Optional<UserEmail> email = isTrue(attributes.get("email_verified"))
			? UserEmail.from(text(attributes.get("email")))
			: Optional.empty();
		return new SocialIdentity(
			SocialProvider.GOOGLE,
			subject(attributes.get("sub")),
			email,
			UserNickname.from(text(attributes.get("name"))),
			Optional.ofNullable(text(attributes.get("picture"))));
	}

	/** Naver 회원 프로필 응답에는 이메일 검증 상태가 없으므로 이메일을 사용하지 않는다. */
	private SocialIdentity naver(Map<String, Object> attributes) {
		Map<String, Object> response = nested(attributes.get("response"));
		return new SocialIdentity(
			SocialProvider.NAVER,
			subject(response.get("id")),
			Optional.empty(),
			UserNickname.from(text(response.get("nickname"))),
			Optional.ofNullable(text(response.get("profile_image"))));
	}

	private SocialIdentity kakao(Map<String, Object> attributes) {
		Map<String, Object> account = nested(attributes.get("kakao_account"));
		Optional<UserEmail> email = isTrue(account.get("is_email_valid"))
			&& isTrue(account.get("is_email_verified"))
				? UserEmail.from(text(account.get("email")))
				: Optional.empty();
		return new SocialIdentity(
			SocialProvider.KAKAO,
			subject(attributes.get("sub")),
			email,
			UserNickname.from(text(nested(account.get("profile")).get("nickname"))),
			Optional.ofNullable(text(nested(account.get("profile")).get("profile_image_url"))));
	}

	private String subject(Object value) {
		String subject = text(value);
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("provider subject is missing");
		}
		return subject;
	}

	/**
	 * 신뢰 상태가 참인지 판정한다.
	 *
	 * <p>제공자는 같은 항목을 JSON boolean으로도 문자열로도 보낼 수 있어 두 표현을 함께 받는다. 값이 없거나 다른 타입이면 거짓이다.
	 */
	private boolean isTrue(Object value) {
		return switch (value) {
			case Boolean flag -> flag;
			case String flag -> Boolean.parseBoolean(flag);
			case null, default -> false;
		};
	}

	private String text(Object value) {
		return value instanceof String text ? text : null;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> nested(Object value) {
		return value instanceof Map<?, ?> nested ? (Map<String, Object>)nested : Map.of();
	}
}
