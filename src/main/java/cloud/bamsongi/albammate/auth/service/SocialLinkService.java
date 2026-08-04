package cloud.bamsongi.albammate.auth.service;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.auth.dto.SocialAuthorizationResponse;
import cloud.bamsongi.albammate.auth.social.SocialClientRegistrationRepository;
import cloud.bamsongi.albammate.auth.social.SocialLinkIntent;
import cloud.bamsongi.albammate.auth.social.SocialLinkIntentStore;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.user.contract.SocialAccountService;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 사용자의 연결 시작을 판정한다.
 *
 * <p>설정된 제공자와 아직 연결하지 않은 제공자만 통과시키고, authorization 경로만 돌려준다. 실제 외부 이동과 {@code state} 생성은
 * Spring Security filter가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class SocialLinkService {

	@NonNull private final SocialClientRegistrationRepository clientRegistrationRepository;
	@NonNull private final SocialAccountService socialAccountService;
	@NonNull private final CurrentUserAccessor currentUserAccessor;
	@NonNull private final SocialLinkIntentStore linkIntentStore;

	public SocialAuthorizationResponse startLink(String registrationId, HttpServletRequest request) {
		long userId = currentUserAccessor.requireCurrentUserId();
		SocialProvider provider = clientRegistrationRepository.configuredProvider(registrationId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SOCIAL_PROVIDER_NOT_AVAILABLE));
		if (socialAccountService.linkedProviders(userId).contains(provider)) {
			throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
		}

		linkIntentStore.save(request, new SocialLinkIntent(provider, userId));
		return new SocialAuthorizationResponse(
			SocialClientRegistrationRepository.AUTHORIZATION_BASE_URI + "/" + registrationId);
	}
}
