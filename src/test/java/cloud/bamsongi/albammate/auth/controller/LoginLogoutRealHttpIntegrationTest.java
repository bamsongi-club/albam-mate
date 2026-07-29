package cloud.bamsongi.albammate.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.cookie.secure=false")
class LoginLogoutRealHttpIntegrationTest {

    private static final Pattern CSRF_TOKEN_PATTERN =
            Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

    @LocalServerPort private int port;

    @Autowired private UserAccountService userAccountService;

    @Test
    void 실제_HTTP_쿠키만으로_로그인부터_로그아웃_뒤_기존_세션_거절까지_수행한다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String email = "real-http-" + suffix + "@example.com";
        String password = "123456789012345";
        userAccountService.createAccount(
                new CreateUserAccountCommand(email, password, "실제 HTTP 사용자"));

        URI baseUri = URI.create("http://localhost:" + port);
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

        HttpResponse<String> initialCsrf = get(client, baseUri.resolve("/api/auth/csrf"));
        assertEquals(200, initialCsrf.statusCode());
        String initialToken = csrfToken(initialCsrf.body());
        assertTrue(cookieNamed(cookieManager, "XSRF-TOKEN").isPresent());
        assertTrue(cookieNamed(cookieManager, "JSESSIONID").isEmpty());

        HttpResponse<String> login =
                post(
                        client,
                        baseUri.resolve("/api/auth/login"),
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
                        initialToken);
        assertEquals(200, login.statusCode());
        assertCookieContract(login, "JSESSIONID", false, false);
        assertCookieContract(login, "XSRF-TOKEN", false, true);

        HttpCookie authenticatedSession = cookieNamed(cookieManager, "JSESSIONID").orElseThrow();
        HttpResponse<String> protectedProfile = get(client, baseUri.resolve("/api/users/me"));
        assertEquals(200, protectedProfile.statusCode());

        HttpResponse<String> refreshedCsrf = get(client, baseUri.resolve("/api/auth/csrf"));
        assertEquals(200, refreshedCsrf.statusCode());
        String refreshedToken = csrfToken(refreshedCsrf.body());
        assertFalse(initialToken.equals(refreshedToken));

        HttpResponse<String> logout =
                post(client, baseUri.resolve("/api/auth/logout"), "", refreshedToken);
        assertEquals(200, logout.statusCode());
        assertCookieContract(logout, "JSESSIONID", false, true);
        assertCookieContract(logout, "XSRF-TOKEN", false, true);

        HttpCookie staleSession = new HttpCookie("JSESSIONID", authenticatedSession.getValue());
        staleSession.setPath("/");
        cookieManager.getCookieStore().add(baseUri, staleSession);
        HttpResponse<String> staleProfile = get(client, baseUri.resolve("/api/users/me"));
        assertEquals(401, staleProfile.statusCode());
    }

    private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient client, URI uri, String body, String csrfToken)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", csrfToken)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String csrfToken(String body) {
        Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private java.util.Optional<HttpCookie> cookieNamed(CookieManager cookieManager, String name) {
        return cookieManager.getCookieStore().getCookies().stream()
                .filter(cookie -> name.equals(cookie.getName()))
                .findFirst();
    }

    private void assertCookieContract(
            HttpResponse<String> response, String name, boolean secure, boolean expired) {
        List<String> headers = response.headers().allValues("Set-Cookie");
        String header =
                headers.stream()
                        .filter(value -> value.startsWith(name + "="))
                        .findFirst()
                        .orElseThrow();

        Map<String, String> attributes = cookieAttributes(header);
        assertEquals("/", attributes.get("path"));
        assertEquals("Lax", attributes.get("samesite"));
        assertTrue(attributes.containsKey("httponly"));
        assertEquals(secure, attributes.containsKey("secure"));
        if (expired) {
            assertTrue(
                    "0".equals(attributes.get("max-age"))
                            || attributes
                                    .getOrDefault("expires", "")
                                    .startsWith("Thu, 01 Jan 1970"),
                    header);
        }
    }

    private Map<String, String> cookieAttributes(String header) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            int separator = trimmed.indexOf('=');
            String name = separator < 0 ? trimmed : trimmed.substring(0, separator);
            String value = separator < 0 ? "" : trimmed.substring(separator + 1);
            attributes.put(name.toLowerCase(Locale.ROOT), value);
        }
        return attributes;
    }
}
