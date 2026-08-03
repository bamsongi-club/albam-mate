package cloud.bamsongi.albammate.notification.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 HTTP 경로에서 세션 없는 두 GET의 인증 경계를 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.security.cookie.secure=false")
class NotificationRealHttpIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	void 세션없는_실제_HTTP_두_GET은_UNAUTHENTICATED다() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		for (String path : new String[] {"/api/users/me/notifications", "/api/users/me/notifications/unread-count"}) {
			HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertEquals(401, response.statusCode());
			assertTrue(response.body().contains("UNAUTHENTICATED"));
		}
	}
}
