package cloud.bamsongi.albammate.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

/**
 * T1의 제공자별 subject·선택 속성 매핑 규칙을 고정된 provider 응답으로 검증한다.
 *
 * <p>제공자 통신 없이 응답 구조만 사용하므로 실제 Client Secret이 필요하지 않다.
 */
class SocialIdentityMapperTest {

	private final SocialIdentityMapper mapper = new SocialIdentityMapper();

	@Test
	void Google은_sub를_subject로_쓰고_검증된_이메일만_정규화해_넘긴다() {
		SocialIdentity identity = mapper.map(
			SocialProvider.GOOGLE,
			Map.of("sub", "google-subject", "email", " Player@Example.COM ", "email_verified", true, "name", " 밤톨 "));

		assertEquals(SocialProvider.GOOGLE, identity.provider());
		assertEquals("google-subject", identity.providerSubject());
		assertEquals(UserEmail.from("player@example.com"), identity.email());
		assertEquals(UserNickname.from("밤톨"), identity.nickname());
	}

	@Test
	void Google의_이메일_검증_상태가_거짓이거나_없으면_이메일_없음으로_매핑한다() {
		Map<String, Object> unverified = Map.of(
			"sub", "google-subject", "email", "player@example.com", "email_verified", false);
		Map<String, Object> missingState = Map.of(
			"sub", "google-subject", "email", "player@example.com");

		assertEquals(Optional.empty(), mapper.map(SocialProvider.GOOGLE, unverified).email());
		assertEquals(Optional.empty(), mapper.map(SocialProvider.GOOGLE, missingState).email());
	}

	@Test
	void 검증_상태를_문자열로_보내도_참으로_읽는다() {
		Map<String, Object> attributes = Map.of(
			"sub", "google-subject", "email", "player@example.com", "email_verified", "true");

		assertEquals(UserEmail.from("player@example.com"), mapper.map(SocialProvider.GOOGLE, attributes).email());
	}

	@Test
	void 형식_계약을_통과하지_못한_이메일은_검증됐어도_이메일_없음으로_매핑한다() {
		Map<String, Object> attributes = Map.of(
			"sub", "google-subject", "email", "player@@example.com", "email_verified", true);

		assertEquals(Optional.empty(), mapper.map(SocialProvider.GOOGLE, attributes).email());
	}

	@Test
	void 닉네임이_없거나_계약을_통과하지_못하면_닉네임_없음으로_넘긴다() {
		Map<String, Object> missingName = Map.of("sub", "google-subject");
		Map<String, Object> blankName = Map.of("sub", "google-subject", "name", "   ");

		assertEquals(Optional.empty(), mapper.map(SocialProvider.GOOGLE, missingName).nickname());
		assertEquals(Optional.empty(), mapper.map(SocialProvider.GOOGLE, blankName).nickname());
	}

	@Test
	void Naver는_response의_id를_subject로_쓰고_이메일은_사용하지_않는다() {
		Map<String, Object> attributes = Map.of(
			"resultcode",
			"00",
			"response",
			Map.of("id", "naver-subject", "email", "player@example.com", "nickname", "밤톨"));

		SocialIdentity identity = mapper.map(SocialProvider.NAVER, attributes);

		assertEquals("naver-subject", identity.providerSubject());
		assertEquals(Optional.empty(), identity.email());
		assertEquals(UserNickname.from("밤톨"), identity.nickname());
	}

	@Test
	void Kakao는_sub를_subject로_쓰고_두_검증_상태가_모두_참일_때만_이메일을_넘긴다() {
		SocialIdentity trusted = mapper.map(SocialProvider.KAKAO, kakaoAttributes(true, true));

		assertEquals("kakao-subject", trusted.providerSubject());
		assertEquals(UserEmail.from("player@example.com"), trusted.email());
		assertEquals(UserNickname.from("밤톨"), trusted.nickname());

		assertEquals(Optional.empty(), mapper.map(SocialProvider.KAKAO, kakaoAttributes(true, false)).email());
		assertEquals(Optional.empty(), mapper.map(SocialProvider.KAKAO, kakaoAttributes(false, true)).email());
		assertEquals(Optional.empty(), mapper.map(SocialProvider.KAKAO, kakaoAttributes(null, null)).email());
	}

	@Test
	void Kakao의_회원_정보가_없으면_이메일과_닉네임_없이_subject만_매핑한다() {
		SocialIdentity identity = mapper.map(SocialProvider.KAKAO, Map.of("sub", "kakao-subject"));

		assertEquals("kakao-subject", identity.providerSubject());
		assertTrue(identity.email().isEmpty());
		assertTrue(identity.nickname().isEmpty());
	}

	@Test
	void 필수_subject가_없으면_매핑에_실패한다() {
		assertThrows(
			IllegalArgumentException.class, () -> mapper.map(SocialProvider.GOOGLE, Map.of("email_verified", true)));
		assertThrows(
			IllegalArgumentException.class,
			() -> mapper.map(SocialProvider.NAVER, Map.of("response", Map.of("nickname", "밤톨"))));
		assertThrows(
			IllegalArgumentException.class, () -> mapper.map(SocialProvider.KAKAO, Map.of("sub", "  ")));
	}

	private Map<String, Object> kakaoAttributes(Boolean emailValid, Boolean emailVerified) {
		Map<String, Object> account = new LinkedHashMap<>();
		account.put("email", "player@example.com");
		account.put("profile", Map.of("nickname", "밤톨"));
		if (emailValid != null) {
			account.put("is_email_valid", emailValid);
		}
		if (emailVerified != null) {
			account.put("is_email_verified", emailVerified);
		}
		return Map.of("sub", "kakao-subject", "id", 1_234_567_890L, "kakao_account", account);
	}
}
