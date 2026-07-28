package cloud.bamsongi.albammate.user.contract;

/** 다른 모듈이 현재 인증 사용자의 프로필을 다루는 공개 계약이다. */
public interface UserProfileService {

    UserProfile findProfile(long userId);

    UserProfile changeNickname(long userId, String nickname);
}
