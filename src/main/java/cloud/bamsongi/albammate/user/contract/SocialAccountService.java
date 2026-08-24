package cloud.bamsongi.albammate.user.contract;

import java.util.Set;

/** auth가 외부 신원의 첫 로그인과 명시적 연결을 호출하는 공개 계약이다. */
public interface SocialAccountService {

	SocialLoginResult login(SocialIdentity identity);

	SocialLinkResult link(Long userId, SocialIdentity identity);

	/** 연결 시작 전 판정과 제공자 목록 노출에 쓰는 현재 연결 상태다. */
	Set<SocialProvider> linkedProviders(Long userId);
}
