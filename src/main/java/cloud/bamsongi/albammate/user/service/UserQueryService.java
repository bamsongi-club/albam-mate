package cloud.bamsongi.albammate.user.service;

import cloud.bamsongi.albammate.user.repository.UserRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserQueryService implements UserQuery {

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<String> findNicknameById(Long userId) {
        return userRepository.findNicknameById(userId);
    }
}
