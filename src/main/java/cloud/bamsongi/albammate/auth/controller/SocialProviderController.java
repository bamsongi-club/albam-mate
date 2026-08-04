package cloud.bamsongi.albammate.auth.controller;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.auth.dto.SocialProviderItem;
import cloud.bamsongi.albammate.auth.social.SocialClientRegistrationRepository;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.user.contract.SocialAccountService;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 로그인·연결 진입점에 노출할 소셜 제공자 조회 경계다. */
@RestController
@RequestMapping("/api/auth/social")
@RequiredArgsConstructor
public final class SocialProviderController {

	@NonNull private final SocialClientRegistrationRepository clientRegistrationRepository;
	@NonNull private final SocialAccountService socialAccountService;
	@NonNull private final CurrentUserAccessor currentUserAccessor;

	@GetMapping("/providers")
	public ApiResponse<List<SocialProviderItem>> getProviders() {
		Set<SocialProvider> linked = currentUserAccessor.currentUserId()
			.map(socialAccountService::linkedProviders)
			.orElseGet(Set::of);
		List<SocialProviderItem> providers = clientRegistrationRepository.configuredProviders()
			.stream()
			.map(provider -> SocialProviderItem.of(provider, linked.contains(provider)))
			.toList();
		return ApiResponse.success(HttpStatus.OK, providers);
	}
}
