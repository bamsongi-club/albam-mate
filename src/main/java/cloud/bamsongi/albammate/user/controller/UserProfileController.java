package cloud.bamsongi.albammate.user.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.user.dto.ProfileUpdateRequest;
import cloud.bamsongi.albammate.user.dto.UserProfileResponse;
import cloud.bamsongi.albammate.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 현재 인증 사용자의 프로필 HTTP 경계를 담당한다. */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public final class UserProfileController {

	@NonNull private final CurrentUserAccessor currentUserAccessor;
	@NonNull private final UserProfileService userProfileService;

	@GetMapping
	public ResponseEntity<ApiResponse<UserProfileResponse>> findMyProfile() {
		UserProfileResponse profile = userProfileService.findProfile(currentUserAccessor.requireCurrentUserId());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, profile));
	}

	@PatchMapping
	public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
		@Valid @RequestBody
		ProfileUpdateRequest request) {
		UserProfileResponse profile = userProfileService.changeNickname(
			currentUserAccessor.requireCurrentUserId(), request.normalize());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, profile));
	}

	@PostMapping("/profile-image")
	public ResponseEntity<ApiResponse<UserProfileResponse>> uploadProfileImage(
		@RequestParam("file")
		MultipartFile file) {
		validateProfileImage(file);
		UserProfileResponse profile;
		try {
			profile = userProfileService.uploadProfileImage(
				currentUserAccessor.requireCurrentUserId(),
				file.getInputStream(),
				file.getOriginalFilename(),
				file.getContentType());
		} catch (IOException exception) {
			throw new UncheckedIOException("파일을 읽을 수 없습니다.", exception);
		}
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, profile));
	}

	@DeleteMapping("/profile-image")
	public ResponseEntity<ApiResponse<UserProfileResponse>> deleteProfileImage() {
		UserProfileResponse profile = userProfileService.removeProfileImage(
			currentUserAccessor.requireCurrentUserId());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, profile));
	}

	private void validateProfileImage(MultipartFile file) {
		if (file.isEmpty()) {
			throw new IllegalArgumentException("빈 파일입니다.");
		}
		if (file.getSize() > 5 * 1024 * 1024) {
			throw new IllegalArgumentException("파일 크기는 5MB 이하여야 합니다.");
		}
		Set<String> allowedTypes = Set.of("image/jpeg", "image/png", "image/webp");
		if (!allowedTypes.contains(file.getContentType())) {
			throw new IllegalArgumentException("지원하지 않는 이미지 형식입니다.");
		}
	}
}
