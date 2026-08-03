package cloud.bamsongi.albammate.user.contract;

/** auth가 외부 신원의 첫 로그인과 명시적 연결을 호출하는 공개 계약이다. */
public interface SocialAccountService {

	SocialLoginResult login(SocialIdentity identity);

	SocialLinkResult link(Long userId, SocialIdentity identity);
}
