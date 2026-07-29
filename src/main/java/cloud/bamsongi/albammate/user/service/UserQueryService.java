package cloud.bamsongi.albammate.user.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.user.contract.UserQuery;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserQueryService implements UserQuery {

	@NonNull private final UserRepository userRepository;

	@Override
	public Optional<String> findNicknameById(Long userId) {
		return userRepository.findNicknameById(userId);
	}
}
