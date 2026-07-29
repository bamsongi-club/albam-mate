package cloud.bamsongi.albammate.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.auth.dto.ProfileUpdateRequest;
import cloud.bamsongi.albammate.auth.dto.UserSummary;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.user.contract.UserProfile;
import cloud.bamsongi.albammate.user.contract.UserProfileService;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 현재 인증 사용자의 프로필 HTTP 경계를 담당한다. */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public final class ProfileController {

	@NonNull private final CurrentUserAccessor currentUserAccessor;
	@NonNull private final UserProfileService userProfileService;

	@GetMapping
	public ResponseEntity<ApiResponse<UserSummary>> findMyProfile() {
		UserProfile profile = userProfileService.findProfile(currentUserAccessor.requireCurrentUserId());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, UserSummary.from(profile)));
	}

	@PatchMapping
	public ResponseEntity<ApiResponse<UserSummary>> updateMyProfile(
		@Valid @RequestBody
		ProfileUpdateRequest request) {
		UserProfile profile = userProfileService.changeNickname(
			currentUserAccessor.requireCurrentUserId(), request.normalize());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, UserSummary.from(profile)));
	}
}
