package cloud.bamsongi.albammate.global.security;

import java.util.Objects;
import org.springframework.http.HttpMethod;

/** Spring MVC에서 수집한 애플리케이션 API 핸들러의 메서드·경로 조합이다. */
public record ApiEndpointMapping(HttpMethod method, String pathPattern) {

    public ApiEndpointMapping {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pathPattern, "pathPattern");
    }
}
