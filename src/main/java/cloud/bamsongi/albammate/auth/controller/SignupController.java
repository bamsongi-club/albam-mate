package cloud.bamsongi.albammate.auth.controller;

import cloud.bamsongi.albammate.auth.dto.SignupRequest;
import cloud.bamsongi.albammate.auth.dto.UserSummary;
import cloud.bamsongi.albammate.auth.service.SignupService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원가입 HTTP 경계를 담당한다. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public final class SignupController {

    @NonNull private final SignupService signupService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserSummary>> signup(
            @Valid @RequestBody SignupRequest request, HttpServletRequest servletRequest) {
        UserAccount account =
                signupService.signup(request.normalize(), servletRequest.getRemoteAddr());
        UserSummary userSummary = new UserSummary(account.id(), account.nickname());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED, userSummary));
    }
}
