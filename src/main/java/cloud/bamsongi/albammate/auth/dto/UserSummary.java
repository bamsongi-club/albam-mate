package cloud.bamsongi.albammate.auth.dto;

/** API가 공개하는 최소 사용자 요약이다. 이메일·비밀번호와 인증 정보는 포함하지 않는다. */
public record UserSummary(Long id, String nickname) {
}
