package cloud.bamsongi.albammate.auth.dto;

/** 다음 상태 변경 요청에 사용할 CSRF 헤더와 토큰이다. */
public record CsrfTokenResponse(String headerName, String token) {}
