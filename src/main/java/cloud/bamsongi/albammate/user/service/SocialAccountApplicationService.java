package cloud.bamsongi.albammate.user.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.user.contract.SocialAccountService;
import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialLinkResult;
import cloud.bamsongi.albammate.user.contract.SocialLoginResult;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 소셜 계정 유일 제약 경합을 완료된 연결 결과로 다시 조회한다. */
@Service
@RequiredArgsConstructor
public class SocialAccountApplicationService implements SocialAccountService {

	@NonNull private final SocialAccountTransactionService transactionService;

	@Override
	public SocialLoginResult login(SocialIdentity identity) {
		try {
			return transactionService.login(identity);
		} catch (DataIntegrityViolationException exception) {
			return transactionService.resolveLoginConflict(identity);
		}
	}

	@Override
	public SocialLinkResult link(Long userId, SocialIdentity identity) {
		if (userId == null || userId <= 0) {
			throw new IllegalArgumentException("userId must be positive");
		}
		try {
			return transactionService.link(userId, identity);
		} catch (DataIntegrityViolationException exception) {
			return transactionService.resolveLinkConflict(userId, identity);
		}
	}
}
