package cloud.bamsongi.albammate.user.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.user.contract.UserRowLockPort;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserRowLockService implements UserRowLockPort {

	private final UserRepository userRepository;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Set<Long> lockExistingUsersInAscendingOrder(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Set.of();
		}
		return userRepository.findExistingUsersForUpdateInAscendingOrder(userIds).stream()
			.map(user -> user.getId())
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}
}
