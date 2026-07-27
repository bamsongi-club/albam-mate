package cloud.bamsongi.albammate.auth.controller;

import cloud.bamsongi.albammate.auth.dto.CsrfTokenResponse;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 관련 HTTP 요청을 처리한다. */
@RestController
@RequestMapping("/api/auth")
public final class CsrfController {

    @GetMapping("/csrf")
    public ApiResponse<CsrfTokenResponse> getCsrfToken(CsrfToken csrfToken) {
        CsrfTokenResponse response =
                new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken());
        return ApiResponse.success(HttpStatus.OK, response);
    }
}
