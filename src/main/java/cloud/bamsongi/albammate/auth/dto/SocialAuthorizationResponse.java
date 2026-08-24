package cloud.bamsongi.albammate.auth.dto;

/**
 * 연결 시작이 클라이언트에 돌려주는 same-site authorization 경로다.
 *
 * <p>클라이언트는 이 경로로 전체 페이지 이동만 하며, {@code state} 생성과 제공자 리다이렉트는 Spring Security filter가 담당한다.
 */
public record SocialAuthorizationResponse(String authorizationUri) {
}
