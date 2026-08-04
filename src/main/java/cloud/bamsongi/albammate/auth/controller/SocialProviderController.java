package cloud.bamsongi.albammate.auth.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.auth.dto.SocialProviderItem;
import cloud.bamsongi.albammate.auth.social.SocialClientRegistrationRepository;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 로그인·연결 진입점에 노출할 소셜 제공자 조회 경계다. */
@RestController
@RequestMapping("/api/auth/social")
@RequiredArgsConstructor
public final class SocialProviderController {

	@NonNull private final SocialClientRegistrationRepository clientRegistrationRepository;

	@GetMapping("/providers")
	public ApiResponse<List<SocialProviderItem>> getProviders() {
		List<SocialProviderItem> providers = clientRegistrationRepository.configuredProviders()
			.stream()
			.map(SocialProviderItem::notLinked)
			.toList();
		return ApiResponse.success(HttpStatus.OK, providers);
	}
}
