package cloud.bamsongi.albammate.user;

import cloud.bamsongi.albammate.global.security.PasswordHashExecutor;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.exception.EmailAlreadyExistsException;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 계정 생성과 자격증명 저장을 담당하는 사용자 모듈의 공개 계약이다. */
@Service
public class UserAccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordHashExecutor passwordHashExecutor;

    public UserAccountService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            PasswordHashExecutor passwordHashExecutor) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.passwordHashExecutor =
                Objects.requireNonNull(passwordHashExecutor, "passwordHashExecutor");
    }

    /** 중복을 먼저 확인한 뒤 슬롯 안에서 해시하고, DB unique 경쟁도 같은 오류로 변환한다. */
    @Transactional
    public UserAccount createAccount(String email, String rawPassword, String nickname) {
        requireValue(email, "email");
        requireValue(rawPassword, "rawPassword");
        requireValue(nickname, "nickname");

        return passwordHashExecutor.execute(
                () -> {
                    if (userRepository.existsByEmail(email)) {
                        throw new EmailAlreadyExistsException();
                    }

                    String passwordHash = passwordEncoder.encode(rawPassword);
                    User user = User.create(email, passwordHash, nickname);
                    try {
                        User saved = userRepository.saveAndFlush(user);
                        return new UserAccount(saved.getId(), saved.getNickname());
                    } catch (DataIntegrityViolationException exception) {
                        throw new EmailAlreadyExistsException(exception);
                    }
                });
    }

    private void requireValue(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }
}
