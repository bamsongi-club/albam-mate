package cloud.bamsongi.albammate.global.security.currentuser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;

class CurrentUserAccessorTest {

	private final CurrentUserAccessor accessor = new SecurityContextCurrentUserAccessor();

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void 인증이_없으면_현재_사용자_ID가_없다() {
		assertEquals(Optional.empty(), accessor.currentUserId());
		assertThrows(UnauthenticatedException.class, accessor::requireCurrentUserId);
	}

	@Test
	void SecurityContext의_최소_주체에서_현재_사용자_ID를_읽는다() {
		SecurityContextHolder.getContext()
			.setAuthentication(
				new UsernamePasswordAuthenticationToken(
					new CurrentUserPrincipal(42L),
					null,
					AuthorityUtils.NO_AUTHORITIES));

		assertEquals(Optional.of(42L), accessor.currentUserId());
		assertEquals(42L, accessor.requireCurrentUserId());
	}

	@Test
	void 익명_인증과_업무정보가_없는_주체는_현재_사용자로_노출하지_않는다() {
		SecurityContextHolder.getContext()
			.setAuthentication(
				new AnonymousAuthenticationToken(
					"anonymous-key",
					"anonymousUser",
					AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
		assertTrue(accessor.currentUserId().isEmpty());

		SecurityContextHolder.getContext()
			.setAuthentication(
				new UsernamePasswordAuthenticationToken(
					"user-name", null, AuthorityUtils.NO_AUTHORITIES));
		assertTrue(accessor.currentUserId().isEmpty());
	}
}
