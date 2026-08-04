package cloud.bamsongi.albammate.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.auth.dto.SocialAuthorizationResponse;
import cloud.bamsongi.albammate.auth.service.SocialLinkService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 로그인 사용자가 외부 신원을 명시적으로 연결하기 시작하는 경계다. */
@RestController
@RequestMapping("/api/users/me/social-accounts")
@RequiredArgsConstructor
public final class SocialAuthController {

	@NonNull private final SocialLinkService socialLinkService;

	@PostMapping("/{provider}/link")
	public ApiResponse<SocialAuthorizationResponse> startLink(
		@PathVariable
		String provider,
		HttpServletRequest request) {
		return ApiResponse.success(HttpStatus.OK, socialLinkService.startLink(provider, request));
	}
}
