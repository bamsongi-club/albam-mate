package cloud.bamsongi.albammate.user.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.user.contract.UserQuery;
import cloud.bamsongi.albammate.user.contract.UserPublicProfile;
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

	@Override
	public Optional<UserPublicProfile> findPublicProfileById(Long userId) {
		return userRepository.findPublicProfileById(userId);
	}

	@Override
	public Map<Long, String> findNicknamesByIds(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userRepository.findNicknameProjectionsByIds(userIds).stream()
			.collect(
				Collectors.toMap(
					UserRepository.UserNicknameProjection::getId,
					UserRepository.UserNicknameProjection::getNickname));
	}

	@Override
	public Map<Long, UserPublicProfile> findPublicProfilesByIds(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userRepository.findPublicProfilesByIds(userIds).stream()
			.collect(Collectors.toMap(UserPublicProfile::userId, profile -> profile));
	}
}
