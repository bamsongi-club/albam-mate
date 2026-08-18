package cloud.bamsongi.albammate.user.contract;

/** 다른 업무 모듈에 공개하는 사용자 표시 프로필이다. */
public record UserPublicProfile(long userId, String nickname, String profileImageUrl) {
}
