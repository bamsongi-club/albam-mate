package cloud.bamsongi.albammate.user.contract;

/** 인증 사용자의 프로필 조회·수정에서 공개하는 최소 사용자 정보다. */
public record UserProfile(Long id, String nickname) {}
