package cloud.bamsongi.albammate.global.security.currentuser;

import java.util.Optional;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Spring Security {@link SecurityContextHolder}에서 현재 사용자 ID를 읽는 어댑터다. */
@Component
public final class SecurityContextCurrentUserAccessor implements CurrentUserAccessor {

	@Override
	public Optional<Long> currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!isAuthenticated(authentication)) {
			return Optional.empty();
		}

		Object principal = authentication.getPrincipal();
		if (principal instanceof CurrentUserPrincipal currentUserPrincipal) {
			return Optional.of(currentUserPrincipal.userId());
		}
		return Optional.empty();
	}

	private boolean isAuthenticated(Authentication authentication) {
		return authentication != null
			&& authentication.isAuthenticated()
			&& !(authentication instanceof AnonymousAuthenticationToken);
	}
}
