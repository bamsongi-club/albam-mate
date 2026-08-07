package cloud.bamsongi.albammate.user.service;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialLinkResult;
import cloud.bamsongi.albammate.user.contract.SocialLoginResult;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.entity.SocialAccount;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.SocialAccountRepository;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 각 소셜 계정 쓰기 시도를 독립 트랜잭션으로 실행한다. */
@Service
@RequiredArgsConstructor
class SocialAccountTransactionService {

	@NonNull private final UserRepository userRepository;
	@NonNull private final SocialAccountRepository socialAccountRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SocialLoginResult login(SocialIdentity identity) {
		SocialAccount existing = socialAccountRepository
			.findByProviderAndProviderSubject(identity.provider(), identity.providerSubject())
			.orElse(null);
		if (existing != null) {
			return SocialLoginResult.loggedIn(UserContractMapper.toUserAccount(existing.getUser()));
		}
		return createFirstSocialLogin(identity);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public SocialLoginResult resolveLoginConflict(SocialIdentity identity) {
		SocialAccount existing = socialAccountRepository
			.findByProviderAndProviderSubject(identity.provider(), identity.providerSubject())
			.orElse(null);
		if (existing != null) {
			return SocialLoginResult.loggedIn(UserContractMapper.toUserAccount(existing.getUser()));
		}
		if (identity.email().filter(email -> userRepository.existsByEmail(email.value())).isPresent()) {
			return SocialLoginResult.linkRequired();
		}
		throw new IllegalStateException("social account conflict was not persisted");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SocialLinkResult link(Long userId, SocialIdentity identity) {
		SocialAccount existingIdentity = socialAccountRepository
			.findByProviderAndProviderSubject(identity.provider(), identity.providerSubject())
			.orElse(null);
		if (existingIdentity != null) {
			return existingIdentity.getUser().getId().equals(userId)
				? SocialLinkResult.LINKED
				: SocialLinkResult.LINK_CONFLICT;
		}
		if (socialAccountRepository.findByUserIdAndProvider(userId, identity.provider()).isPresent()) {
			return SocialLinkResult.LINK_CONFLICT;
		}

		User user = userRepository.findById(userId)
			.orElseThrow(() -> new IllegalArgumentException("userId must refer to an existing user"));
		socialAccountRepository.saveAndFlush(
			SocialAccount.create(user, identity.provider(), identity.providerSubject()));
		return SocialLinkResult.LINKED;
	}

	@Transactional(readOnly = true)
	public Set<SocialProvider> linkedProviders(Long userId) {
		return socialAccountRepository.findAllByUserId(userId)
			.stream()
			.map(SocialAccount::getProvider)
			.collect(Collectors.toUnmodifiableSet());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public SocialLinkResult resolveLinkConflict(Long userId, SocialIdentity identity) {
		return socialAccountRepository
			.findByProviderAndProviderSubject(identity.provider(), identity.providerSubject())
			.map(account -> account.getUser().getId().equals(userId)
				? SocialLinkResult.LINKED
				: SocialLinkResult.LINK_CONFLICT)
			.orElseGet(
				() -> socialAccountRepository.findByUserIdAndProvider(userId, identity.provider())
					.map(ignored -> SocialLinkResult.LINK_CONFLICT)
					.orElseThrow(() -> new IllegalStateException("social account conflict was not persisted")));
	}

	private SocialLoginResult createFirstSocialLogin(SocialIdentity identity) {
		if (identity.email().filter(email -> userRepository.existsByEmail(email.value())).isPresent()) {
			return SocialLoginResult.linkRequired();
		}

		String email = identity.email().map(value -> value.value()).orElse(null);
		String nickname = identity.nickname()
			.map(value -> value.value())
			.orElse(identity.provider().fallbackNickname());
		String profileImageUrl = identity.profileImageUrl().orElse(null);
		User user = userRepository.save(User.createSocial(email, nickname, profileImageUrl));
		socialAccountRepository.saveAndFlush(
			SocialAccount.create(user, identity.provider(), identity.providerSubject()));
		return SocialLoginResult.loggedIn(UserContractMapper.toUserAccount(user));
	}
}
