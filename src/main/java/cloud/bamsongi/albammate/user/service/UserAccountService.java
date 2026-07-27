package cloud.bamsongi.albammate.user.service;

import cloud.bamsongi.albammate.global.security.PasswordHashExecutor;
import cloud.bamsongi.albammate.user.dto.UserAccount;
import cloud.bamsongi.albammate.user.dto.UserCredentials;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.exception.EmailAlreadyExistsException;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import java.util.Objects;
import java.util.Optional;
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

    /** 이메일로 로그인 검증에 필요한 ID·닉네임·저장 해시만 조회한다. */
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentialsByEmail(String email) {
        requireValue(email, "email");
        return userRepository
                .findByEmail(email)
                .map(
                        user ->
                                new UserCredentials(
                                        user.getId(), user.getNickname(), user.getPasswordHash()));
    }

    /** 로그인 성공 뒤 현재 비밀번호 비용으로 저장 해시를 갱신한다. */
    @Transactional
    public void updatePasswordHash(Long userId, String passwordHash) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId는 양수여야 합니다.");
        }
        requireValue(passwordHash, "passwordHash");
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "user credential no longer exists"));
        user.changePasswordHash(passwordHash);
    }

    private void requireValue(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }
}
