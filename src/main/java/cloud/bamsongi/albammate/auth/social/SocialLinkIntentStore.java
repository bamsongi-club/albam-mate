package cloud.bamsongi.albammate.auth.social;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 일회성 연결 의도를 서버 세션에 보관한다.
 *
 * <p>연결 의도는 link nonce를 통해 Spring Security가 생성한 OAuth {@code state}와 결속한다. callback에서 일치한 의도만
 * request 범위로 옮기고 세션에서는 폐기하므로, 일반 로그인과 callback 재사용이 연결을 만들지 못한다. 취소된 state 표식은 짧은
 * 시간 동안만 고정된 개수로 보관한다.
 */
@Component
@RequiredArgsConstructor
public final class SocialLinkIntentStore {

	private static final String SESSION_ATTRIBUTE = SocialLinkIntentStore.class.getName() + ".INTENT";
	private static final String BOUND_STATE_ATTRIBUTE = SocialLinkIntentStore.class.getName() + ".BOUND_STATE";
	static final String DISCARDED_STATES_ATTRIBUTE = SocialLinkIntentStore.class.getName() + ".DISCARDED_STATES";
	private static final String CALLBACK_LINK_ATTEMPT_ATTRIBUTE = SocialLinkIntentStore.class.getName()
		+ ".CALLBACK_LINK_ATTEMPT";
	private static final String CALLBACK_INTENT_ATTRIBUTE = SocialLinkIntentStore.class.getName() + ".CALLBACK_INTENT";
	static final int MAX_DISCARDED_STATES = 8;
	static final Duration DISCARDED_STATE_TTL = Duration.ofMinutes(5);

	@NonNull private final Clock clock;

	public void save(HttpServletRequest request, SocialLinkIntent intent) {
		discardPendingIntent(request);
		request.getSession(true).setAttribute(SESSION_ATTRIBUTE, intent);
	}

	/** link nonce와 OAuth state가 모두 일치하는 authorization request만 연결 흐름으로 표시한다. */
	public void bindAuthorizationRequest(HttpServletRequest request, String state, String nonce) {
		HttpSession session = request.getSession(false);
		if (session == null || state == null || nonce == null) {
			return;
		}
		Object value = session.getAttribute(SESSION_ATTRIBUTE);
		if (!(value instanceof SocialLinkIntent intent) || !intent.nonce().equals(nonce)) {
			return;
		}
		session.setAttribute(BOUND_STATE_ATTRIBUTE, state);
	}

	/** 일반 로그인 시작 또는 변조된 link callback 뒤 남은 연결 의도와 state 결속을 함께 폐기한다. */
	public boolean discardPendingIntent(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return false;
		}
		boolean pending = session.getAttribute(SESSION_ATTRIBUTE) instanceof SocialLinkIntent;
		Object value = session.getAttribute(BOUND_STATE_ATTRIBUTE);
		boolean hasBoundState = value instanceof String;
		if (value instanceof String state) {
			rememberDiscardedState(session, state);
		}
		session.removeAttribute(SESSION_ATTRIBUTE);
		session.removeAttribute(BOUND_STATE_ATTRIBUTE);
		return pending || hasBoundState;
	}

	/** callback의 OAuth state가 연결 흐름에 결속됐으면 해당 의도를 request 범위로 옮긴다. */
	public void activateForCallback(HttpServletRequest request, String state) {
		HttpSession session = request.getSession(false);
		if (session == null || state == null) {
			return;
		}
		if (isDiscardedState(session, state)) {
			markLinkCallback(request);
			return;
		}
		Object value = session.getAttribute(BOUND_STATE_ATTRIBUTE);
		if (!(value instanceof String boundState) || !boundState.equals(state)) {
			return;
		}
		session.removeAttribute(BOUND_STATE_ATTRIBUTE);
		rememberDiscardedState(session, state);
		request.setAttribute(CALLBACK_LINK_ATTEMPT_ATTRIBUTE, true);
		Object intentValue = session.getAttribute(SESSION_ATTRIBUTE);
		if (intentValue instanceof SocialLinkIntent intent) {
			session.removeAttribute(SESSION_ATTRIBUTE);
			request.setAttribute(CALLBACK_INTENT_ATTRIBUTE, intent);
		}
	}

	public boolean isLinkCallback(HttpServletRequest request) {
		return Boolean.TRUE.equals(request.getAttribute(CALLBACK_LINK_ATTEMPT_ATTRIBUTE));
	}

	/** 변조된 state가 남은 연결 의도를 폐기한 callback도 연결 시도 결과로 표시한다. */
	public void markLinkCallback(HttpServletRequest request) {
		request.setAttribute(CALLBACK_LINK_ATTEMPT_ATTRIBUTE, true);
	}

	/** OAuth state와 결속되어 callback request에 옮겨진 의도만 반환한다. */
	public Optional<SocialLinkIntent> consumeCallbackIntent(HttpServletRequest request) {
		Object intent = request.getAttribute(CALLBACK_INTENT_ATTRIBUTE);
		request.removeAttribute(CALLBACK_INTENT_ATTRIBUTE);
		return Optional.ofNullable(intent).map(SocialLinkIntent.class::cast);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Instant> discardedStates(HttpSession session) {
		Object value = session.getAttribute(DISCARDED_STATES_ATTRIBUTE);
		if (value instanceof Map<?, ?>) {
			return (Map<String, Instant>)value;
		}
		Map<String, Instant> discardedStates = new LinkedHashMap<>();
		session.setAttribute(DISCARDED_STATES_ATTRIBUTE, discardedStates);
		return discardedStates;
	}

	private boolean isDiscardedState(HttpSession session, String state) {
		Map<String, Instant> discardedStates = discardedStates(session);
		pruneExpiredStates(discardedStates);
		return discardedStates.containsKey(state);
	}

	private void rememberDiscardedState(HttpSession session, String state) {
		Map<String, Instant> discardedStates = discardedStates(session);
		pruneExpiredStates(discardedStates);
		discardedStates.put(state, Instant.now(clock).plus(DISCARDED_STATE_TTL));
		while (discardedStates.size() > MAX_DISCARDED_STATES) {
			Iterator<String> iterator = discardedStates.keySet().iterator();
			iterator.next();
			iterator.remove();
		}
	}

	private void pruneExpiredStates(Map<String, Instant> discardedStates) {
		Instant now = Instant.now(clock);
		discardedStates.values().removeIf(expiresAt -> !expiresAt.isAfter(now));
	}
}
