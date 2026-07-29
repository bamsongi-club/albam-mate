package cloud.bamsongi.albammate.user.contract;

import java.util.Optional;

/** 다른 모듈이 사용자 계정 유스케이스를 호출할 때 사용하는 공개 계약이다. */
public interface UserAccountService {

    UserAccount createAccount(CreateUserAccountCommand command);

    Optional<UserCredentials> findCredentialsByEmail(String email);

    void updatePasswordHash(Long userId, String passwordHash);
}
