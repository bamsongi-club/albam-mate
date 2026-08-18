package cloud.bamsongi.albammate.monitoring;

import java.io.IOException;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/** local·production proxy 응답에 검증된 Spring 역할만 노출한다. */
@Component
@Profile({"local", "production"})
@Slf4j
public final class UpstreamRoleResponseFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Albam-Mate-Upstream";
	private static final Set<String> ALLOWED_ROLES = Set.of("app1", "app2");

	private final String role;

	public UpstreamRoleResponseFilter(@Value("${app.monitoring.upstream-role:}")
	String role) {
		if (!ALLOWED_ROLES.contains(role)) {
			throw new IllegalArgumentException("ALBAM_MATE_ROLE must be exactly app1 or app2");
		}
		this.role = role;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		response.setHeader(HEADER_NAME, role);
		filterChain.doFilter(request, response);
		if (response.getStatus() >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
			log.atError().addKeyValue("event", "http_request_failed")
				.addKeyValue("failureCode", response.getStatus() == HttpServletResponse.SC_GATEWAY_TIMEOUT
					? "HTTP_TIMEOUT" : "HTTP_SERVER_ERROR")
				.log("http request failed");
		}
	}
}
