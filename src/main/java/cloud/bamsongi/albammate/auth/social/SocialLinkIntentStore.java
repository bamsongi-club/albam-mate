package cloud.bamsongi.albammate.auth.social;

import java.util.Optional;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 일회성 연결 의도를 서버 세션에 보관한다.
 *
 * <p>의도는 읽는 순간 폐기하므로 같은 callback을 다시 보내도 연결되지 않는다.
 */
@Component
public final class SocialLinkIntentStore {

	private static final String SESSION_ATTRIBUTE = SocialLinkIntentStore.class.getName() + ".INTENT";

	public void save(HttpServletRequest request, SocialLinkIntent intent) {
		request.getSession(true).setAttribute(SESSION_ATTRIBUTE, intent);
	}

	/** 저장된 의도를 반환하면서 폐기한다. */
	public Optional<SocialLinkIntent> consume(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return Optional.empty();
		}
		Object intent = session.getAttribute(SESSION_ATTRIBUTE);
		session.removeAttribute(SESSION_ATTRIBUTE);
		return Optional.ofNullable(intent).map(SocialLinkIntent.class::cast);
	}
}
