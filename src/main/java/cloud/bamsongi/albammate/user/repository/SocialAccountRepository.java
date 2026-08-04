package cloud.bamsongi.albammate.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.entity.SocialAccount;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

	Optional<SocialAccount> findByProviderAndProviderSubject(SocialProvider provider, String providerSubject);

	Optional<SocialAccount> findByUserIdAndProvider(Long userId, SocialProvider provider);
}
