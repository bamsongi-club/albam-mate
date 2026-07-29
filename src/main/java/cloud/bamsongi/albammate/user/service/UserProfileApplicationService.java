package cloud.bamsongi.albammate.user.service;

import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.contract.UserProfile;
import cloud.bamsongi.albammate.user.contract.UserProfileService;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.exception.InvalidNicknameException;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 인증 사용자의 프로필 조회와 닉네임 변경을 사용자 모듈 트랜잭션으로 처리한다. */
@Service
public class UserProfileApplicationService implements UserProfileService {

    private final UserRepository userRepository;

    public UserProfileApplicationService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfile findProfile(long userId) {
        return toProfile(findAuthenticatedUser(userId));
    }

    @Override
    @Transactional
    public UserProfile changeNickname(long userId, String nickname) {
        String normalizedNickname =
                UserNickname.from(nickname)
                        .map(UserNickname::value)
                        .orElseThrow(InvalidNicknameException::new);
        User user = findAuthenticatedUser(userId);
        user.changeNickname(normalizedNickname);
        return toProfile(user);
    }

    private User findAuthenticatedUser(long userId) {
        if (userId <= 0) {
            throw new UnauthenticatedException();
        }
        return userRepository.findById(userId).orElseThrow(UnauthenticatedException::new);
    }

    private UserProfile toProfile(User user) {
        return new UserProfile(user.getId(), user.getNickname());
    }
}
