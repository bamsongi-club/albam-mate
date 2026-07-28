package cloud.bamsongi.albammate.auth.controller;

import cloud.bamsongi.albammate.auth.dto.ProfileUpdateRequest;
import cloud.bamsongi.albammate.auth.dto.UserSummary;
import cloud.bamsongi.albammate.auth.exception.ProfileValidationException;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.CurrentUserAccessor;
import cloud.bamsongi.albammate.user.contract.UserProfile;
import cloud.bamsongi.albammate.user.contract.UserProfileService;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 현재 인증 사용자의 프로필 HTTP 경계를 담당한다. */
@RestController
@RequestMapping("/api/users/me")
public final class ProfileController {

    private final CurrentUserAccessor currentUserAccessor;
    private final UserProfileService userProfileService;

    public ProfileController(
            CurrentUserAccessor currentUserAccessor, UserProfileService userProfileService) {
        this.currentUserAccessor =
                Objects.requireNonNull(currentUserAccessor, "currentUserAccessor");
        this.userProfileService = Objects.requireNonNull(userProfileService, "userProfileService");
    }

    @GetMapping
    public ResponseEntity<ApiResponse<UserSummary>> findMyProfile() {
        UserProfile profile =
                userProfileService.findProfile(currentUserAccessor.requireCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, toUserSummary(profile)));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<UserSummary>> updateMyProfile(
            @Valid @RequestBody ProfileUpdateRequest request) {
        if (request == null) {
            throw new ProfileValidationException();
        }
        String nickname = request.normalizeAndValidate().nickname();
        UserProfile profile =
                userProfileService.changeNickname(
                        currentUserAccessor.requireCurrentUserId(), nickname);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, toUserSummary(profile)));
    }

    private UserSummary toUserSummary(UserProfile profile) {
        return new UserSummary(profile.id(), profile.nickname());
    }
}
