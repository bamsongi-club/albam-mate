package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "issue360.localMultiProxy", matches = "true")
class LocalMultiProxyRuntimePostgresTest {

	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
	private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("\\\"messageId\\\":(\\d+)");
	private static final Pattern ROOM_ID_PATTERN = Pattern.compile("\\\"id\\\":(\\d+)");
	private static final List<String> LOCAL_MULTI_SERVICES = List.of("postgres", "redis", "spring-1", "spring-2",
		"proxy");
	private static final Set<String> PUBLIC_SERVICES = Set.of("postgres", "redis", "proxy");
	private static final String ALLOWED_ORIGIN = "http://localhost:5174";
	private static final String PASSWORD = "123456789012345";
	private static final String UPSTREAM_HEADER = "x-albam-mate-upstream";
	private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	@Test
	void local_multi_서비스가_healthy이고_공개_포트가_loopback에만_바인딩되며_프록시_세션이_공유된다() throws Exception {
		assertLocalMultiServicesHealthyAndLoopbackBound();

		URI proxyUri = URI.create("http://127.0.0.1:5174");
		String password = "123456789012345";
		String email = "proxy-runtime-" + UUID.randomUUID() + "@example.com";
		HttpClient client = HttpClient.newBuilder()
			.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
			.build();

		HttpResponse<String> csrf = get(client, proxyUri.resolve("/api/auth/csrf"));
		assertEquals(200, csrf.statusCode());
		String csrfToken = csrfToken(csrf.body());

		HttpResponse<String> signup = post(
			client,
			proxyUri.resolve("/api/auth/signup"),
			"{\"email\":\"" + email + "\",\"password\":\"" + password + "\","
				+ "\"nickname\":\"프록시 세션 사용자\"}",
			csrfToken);
		assertEquals(201, signup.statusCode());

		csrf = get(client, proxyUri.resolve("/api/auth/csrf"));
		csrfToken = csrfToken(csrf.body());
		HttpResponse<String> login = post(
			client,
			proxyUri.resolve("/api/auth/login"),
			"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
			csrfToken);
		assertEquals(200, login.statusCode());

		HttpCookie sessionCookie = cookieNamed(client, "JSESSIONID");
		Set<String> upstreams = new HashSet<>();
		for (int requestNumber = 0; requestNumber < 8; requestNumber++) {
			HttpResponse<String> profile = getWithSession(
				proxyUri.resolve("/api/users/me"), sessionCookie);
			assertEquals(200, profile.statusCode(), "proxy request " + requestNumber + " lost the shared session");
			upstreams.add(profile.headers().firstValue("X-Albam-Mate-Upstream").orElseThrow());
		}
		assertEquals(2, upstreams.size(), "proxy did not route requests to both Spring instances: " + upstreams);
	}

	/** T1: 프록시 주소로 보낸 WebSocket Upgrade 요청이 실제 Spring 인스턴스까지 라우팅되어 101로 전환된다. */
	@Test
	void 프록시_주소로_WebSocket_Upgrade_연결을_생성한다() throws Exception {
		URI proxyUri = URI.create("http://127.0.0.1:5174");
		HttpClient client = HttpClient.newBuilder()
			.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
			.build();
		String sessionId = signupAndLogin(client, proxyUri, "proxy-ws-upgrade-" + UUID.randomUUID() + "@example.com");
		long roomId = createRoom(client, proxyUri);

		try (ProxyWebSocket webSocket = connectProxyWebSocket(proxyUri, roomId, sessionId, null)) {
			assertEquals(101, webSocket.statusCode, "프록시 WebSocket Upgrade가 실패했습니다.");
			assertEquals("websocket", webSocket.headers.get("upgrade"));
			assertEquals("upgrade", webSocket.headers.get("connection").toLowerCase(Locale.ROOT));
			assertNotNull(webSocket.headers.get(UPSTREAM_HEADER), "Upgrade 응답에 업스트림 인스턴스 정보가 없습니다.");
		}
	}

	/** T2: HTTP로 메시지를 저장한 인스턴스와 다른 인스턴스가 맡은 프록시 WebSocket 연결도 실시간 프레임을 수신한다. */
	@Test
	void 메시지를_저장한_인스턴스와_다른_인스턴스의_프록시_WebSocket_연결이_실시간으로_수신한다() throws Exception {
		URI proxyUri = URI.create("http://127.0.0.1:5174");
		HttpClient client = HttpClient.newBuilder()
			.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
			.build();
		String sessionId = signupAndLogin(client, proxyUri, "proxy-ws-cross-" + UUID.randomUUID() + "@example.com");
		long roomId = createRoom(client, proxyUri);

		try (ProxyWebSocket webSocket = connectProxyWebSocket(proxyUri, roomId, sessionId, null)) {
			assertEquals(101, webSocket.statusCode);
			String webSocketUpstream = webSocket.headers.get(UPSTREAM_HEADER);

			// Nginx upstream의 shared zone이 worker 간 실행 상태를 공유하므로 순차 요청의 라운드로빈이 결정적이다.
			// CSRF 조회+전송(2회 요청)만 반복하면 WebSocket과의 상대 패리티가 고정되므로,
			// 홀수 시도에서 CSRF를 한 번 더 조회해 패리티를 뒤집어 교차 인스턴스 저장을 만든다.
			long targetMessageId = -1;
			String targetHttpUpstream = null;
			for (int attempt = 0; attempt < 4 && targetMessageId < 0; attempt++) {
				HttpResponse<String> sendResponse = sendMessage(
					client, proxyUri, roomId, "프록시 교차 인스턴스 메시지 " + attempt, attempt % 2 == 1);
				String httpUpstream = sendResponse.headers().firstValue(UPSTREAM_HEADER).orElseThrow();
				if (!httpUpstream.equals(webSocketUpstream)) {
					targetMessageId = messageId(sendResponse.body());
					targetHttpUpstream = httpUpstream;
				}
			}
			assertTrue(
				targetMessageId > 0,
				"WebSocket과 다른 인스턴스로 메시지를 저장하는 시도가 모두 실패했습니다.");
			assertNotEquals(webSocketUpstream, targetHttpUpstream, "메시지 저장 인스턴스(HTTP)와 WebSocket 연결 인스턴스가 동일합니다.");

			String frame = pollUntilEventId(webSocket, targetMessageId, 4);
			assertNotNull(frame, "다른 인스턴스가 저장한 메시지의 실시간 프레임을 받지 못했습니다.");
			assertTrue(frame.contains("\"type\":\"MESSAGE_CREATED\""), frame);
		}
	}

	/** T3: 연결이 끊긴 뒤 다른 인스턴스로 재연결해도 같은 세션으로 판정되고 누락 메시지가 복구된다. */
	@Test
	void 재연결_시_다른_인스턴스에서도_공용_세션과_누락_메시지가_복구된다() throws Exception {
		URI proxyUri = URI.create("http://127.0.0.1:5174");
		HttpClient client = HttpClient.newBuilder()
			.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
			.build();
		String sessionId = signupAndLogin(client, proxyUri, "proxy-ws-restart-" + UUID.randomUUID() + "@example.com");
		long roomId = createRoom(client, proxyUri);

		String firstInstanceUpstream;
		long firstMessageId;
		try (ProxyWebSocket firstConnection = connectProxyWebSocket(proxyUri, roomId, sessionId, null)) {
			assertEquals(101, firstConnection.statusCode);
			firstInstanceUpstream = firstConnection.headers.get(UPSTREAM_HEADER);

			HttpResponse<String> sendResponse = sendMessage(client, proxyUri, roomId, "재연결 전 메시지");
			firstMessageId = messageId(sendResponse.body());
			String liveFrame = pollUntilEventId(firstConnection, firstMessageId, 2);
			assertNotNull(liveFrame, "재연결 전 실시간 프레임을 받지 못했습니다.");
		}

		HttpResponse<String> missedResponse = sendMessage(client, proxyUri, roomId, "연결이 끊긴 동안 커밋된 메시지");
		long missedMessageId = messageId(missedResponse.body());
		String missedHttpUpstream = missedResponse.headers().firstValue(UPSTREAM_HEADER).orElseThrow();
		assertTrue(missedMessageId > firstMessageId);

		ProxyWebSocket reconnected = null;
		try {
			for (int attempt = 0; attempt < 4; attempt++) {
				if (reconnected != null) {
					reconnected.close();
				}
				reconnected = connectProxyWebSocket(proxyUri, roomId, sessionId, firstMessageId);
				assertEquals(101, reconnected.statusCode, "같은 세션의 재연결 handshake가 실패했습니다.");
				if (!firstInstanceUpstream.equals(reconnected.headers.get(UPSTREAM_HEADER))) {
					break;
				}
			}
			String reconnectedWsUpstream = reconnected.headers.get(UPSTREAM_HEADER);
			assertNotEquals(
				firstInstanceUpstream, reconnectedWsUpstream,
				"재연결이 다른 인스턴스로 라우팅되지 않았습니다.");
			assertNotEquals(
				missedHttpUpstream, reconnectedWsUpstream,
				"누락 메시지를 저장한 HTTP 인스턴스(" + missedHttpUpstream + ")와 복구 WebSocket 인스턴스("
					+ reconnectedWsUpstream + ")가 동일하여 FND-10-AC8 교차 인스턴스 조건이 검증되지 않았습니다.");

			String recoveredFrame = pollUntilEventId(reconnected, missedMessageId, 4);
			assertNotNull(recoveredFrame, "재연결 뒤 다른 인스턴스의 catch-up 프레임을 받지 못했습니다.");
			assertTrue(recoveredFrame.contains("\"type\":\"MESSAGE_CREATED\""), recoveredFrame);
		} finally {
			if (reconnected != null) {
				reconnected.close();
			}
		}
	}

	private void assertLocalMultiServicesHealthyAndLoopbackBound() throws Exception {
		for (String service : LOCAL_MULTI_SERVICES) {
			String containerId = dockerCompose("ps", "-q", service).trim();
			assertFalse(containerId.isBlank(), service + " container is not running");
			assertEquals("running", dockerInspect(containerId, "{{.State.Status}}"), service + " state");
			assertEquals("healthy", dockerInspect(containerId, "{{.State.Health.Status}}"), service + " health");

			String publishedHostIps = dockerInspect(
				containerId,
				"{{range .NetworkSettings.Ports}}{{range .}}{{println .HostIp}}{{end}}{{end}}");
			if (PUBLIC_SERVICES.contains(service)) {
				assertFalse(publishedHostIps.isBlank(), service + " has no published host binding");
			}
			publishedHostIps.lines()
				.filter(hostIp -> !hostIp.isBlank())
				.forEach(hostIp -> assertEquals("127.0.0.1", hostIp, service + " published HostIp"));
		}
	}

	private String dockerCompose(String... arguments) throws Exception {
		String[] command = new String[arguments.length + 6];
		command[0] = "docker";
		command[1] = "compose";
		command[2] = "--env-file";
		command[3] = ".env.example";
		command[4] = "-f";
		command[5] = "compose.local-multi.yml";
		System.arraycopy(arguments, 0, command, 6, arguments.length);
		return runCommand(command);
	}

	private String dockerInspect(String containerId, String format) throws Exception {
		return runCommand("docker", "inspect", "--format", format, containerId);
	}

	private String runCommand(String... command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
			String.join(" ", command) + " timed out");
		assertEquals(0, process.exitValue(), String.join(" ", command) + " failed: " + output);
		return output.trim();
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> getWithSession(URI uri, HttpCookie sessionCookie) throws Exception {
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(uri)
				.header("Cookie", "JSESSIONID=" + sessionCookie.getValue())
				.GET()
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(HttpClient client, URI uri, String body, String csrfToken) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/json")
				.header("X-XSRF-TOKEN", csrfToken)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpCookie cookieNamed(HttpClient client, String name) {
		CookieManager cookieManager = (CookieManager)client.cookieHandler().orElseThrow();
		return cookieManager.getCookieStore().getCookies().stream()
			.filter(cookie -> cookie.getName().equals(name))
			.findFirst()
			.orElseThrow();
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
	}

	private long messageId(String body) {
		Matcher matcher = MESSAGE_ID_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return Long.parseLong(matcher.group(1));
	}

	private String signupAndLogin(HttpClient client, URI proxyUri, String email) throws Exception {
		String csrfToken = csrfToken(get(client, proxyUri.resolve("/api/auth/csrf")).body());
		HttpResponse<String> signup = post(
			client,
			proxyUri.resolve("/api/auth/signup"),
			"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"프록시 WS 사용자\"}",
			csrfToken);
		assertEquals(201, signup.statusCode(), signup.body());

		csrfToken = csrfToken(get(client, proxyUri.resolve("/api/auth/csrf")).body());
		HttpResponse<String> login = post(
			client,
			proxyUri.resolve("/api/auth/login"),
			"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
			csrfToken);
		assertEquals(200, login.statusCode(), login.body());
		return cookieNamed(client, "JSESSIONID").getValue();
	}

	private long createRoom(HttpClient client, URI proxyUri) throws Exception {
		String csrfToken = csrfToken(get(client, proxyUri.resolve("/api/auth/csrf")).body());
		HttpResponse<String> response = post(
			client,
			proxyUri.resolve("/api/rooms"),
			"{\"roomType\":\"PERSON_FOCUSED\",\"title\":\"프록시 WebSocket 검증 방\","
				+ "\"experienceLevel\":\"ALL_LEVELS\",\"isRulemasterLed\":false,"
				+ "\"startsAt\":\"2099-01-01T10:00:00+09:00\",\"place\":\"홍대\",\"recruitmentCapacity\":2}",
			csrfToken);
		assertEquals(201, response.statusCode(), response.body());
		Matcher matcher = ROOM_ID_PATTERN.matcher(response.body());
		assertTrue(matcher.find(), response.body());
		return Long.parseLong(matcher.group(1));
	}

	private HttpResponse<String> sendMessage(HttpClient client, URI proxyUri, long roomId, String content)
		throws Exception {
		return sendMessage(client, proxyUri, roomId, content, false);
	}

	/**
	 * 프록시 라운드로빈 패리티를 뒤집어야 할 때 CSRF를 한 번 더 미리 조회해 요청 수를 홀수만큼 늘린다.
	 * 라운드로빈은 결정적이므로 같은 개수의 요청을 반복하면 상대 인스턴스가 고정된다.
	 */
	private HttpResponse<String> sendMessage(
		HttpClient client, URI proxyUri, long roomId, String content, boolean shiftRoundRobinParity)
		throws Exception {
		if (shiftRoundRobinParity) {
			get(client, proxyUri.resolve("/api/auth/csrf"));
		}
		String csrfToken = csrfToken(get(client, proxyUri.resolve("/api/auth/csrf")).body());
		HttpResponse<String> response = post(
			client,
			proxyUri.resolve("/api/rooms/" + roomId + "/chat/messages"),
			"{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"" + content + "\"}",
			csrfToken);
		assertEquals(201, response.statusCode(), response.body());
		return response;
	}

	private String pollUntilEventId(ProxyWebSocket webSocket, long eventId, int maxAttempts) throws IOException {
		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			String frame = webSocket.pollTextFrame(15);
			if (frame != null && frame.contains("\"eventId\":" + eventId)) {
				return frame;
			}
		}
		return null;
	}

	private ProxyWebSocket connectProxyWebSocket(URI proxyUri, long roomId, String sessionId, Long afterMessageId)
		throws IOException {
		String path = "/api/rooms/" + roomId + "/chat/ws"
			+ (afterMessageId == null ? "" : "?afterMessageId=" + afterMessageId);
		byte[] keyBytes = new byte[16];
		new SecureRandom().nextBytes(keyBytes);
		String secWebSocketKey = Base64.getEncoder().encodeToString(keyBytes);

		Socket socket = new Socket(proxyUri.getHost(), proxyUri.getPort());
		try {
			socket.setSoTimeout(10_000);
			OutputStream output = socket.getOutputStream();
			String request = "GET " + path + " HTTP/1.1\r\n"
				+ "Host: " + proxyUri.getHost() + ":" + proxyUri.getPort() + "\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Key: " + secWebSocketKey + "\r\n"
				+ "Sec-WebSocket-Version: 13\r\n"
				+ "Origin: " + ALLOWED_ORIGIN + "\r\n"
				+ "Cookie: JSESSIONID=" + sessionId + "\r\n"
				+ "\r\n";
			output.write(request.getBytes(StandardCharsets.US_ASCII));
			output.flush();

			InputStream input = socket.getInputStream();
			String statusLine = readLine(input);
			int statusCode = Integer.parseInt(statusLine.split(" ")[1]);

			Map<String, String> headers = new HashMap<>();
			String headerLine;
			while (!(headerLine = readLine(input)).isEmpty()) {
				int separator = headerLine.indexOf(':');
				headers.put(
					headerLine.substring(0, separator).trim().toLowerCase(Locale.ROOT),
					headerLine.substring(separator + 1).trim());
			}

			if (statusCode == 101) {
				String expectedAccept = Base64.getEncoder().encodeToString(sha1(secWebSocketKey + WEBSOCKET_GUID));
				assertEquals(
					expectedAccept, headers.get("sec-websocket-accept"), "Sec-WebSocket-Accept가 유효하지 않습니다.");
			}
			return new ProxyWebSocket(socket, input, statusCode, headers);
		} catch (IOException | RuntimeException | AssertionError exception) {
			socket.close();
			throw exception;
		}
	}

	private static byte[] sha1(String value) {
		try {
			return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String readLine(InputStream input) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		int previous = -1;
		int current;
		while ((current = input.read()) != -1) {
			if (previous == '\r' && current == '\n') {
				byte[] bytes = buffer.toByteArray();
				return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
			}
			buffer.write(current);
			previous = current;
		}
		throw new EOFException("프록시 WebSocket 응답을 읽는 중 연결이 끊겼습니다.");
	}

	private static String readTextFrame(InputStream input, int byte0) throws IOException {
		int byte1 = input.read();
		boolean fin = (byte0 & 0x80) != 0;
		int opcode = byte0 & 0x0F;
		boolean masked = (byte1 & 0x80) != 0;
		long length = byte1 & 0x7F;
		if (length == 126) {
			length = ((input.read() & 0xFF) << 8) | (input.read() & 0xFF);
		} else if (length == 127) {
			length = 0;
			for (int i = 0; i < 8; i++) {
				length = (length << 8) | (input.read() & 0xFF);
			}
		}
		byte[] maskKey = null;
		if (masked) {
			maskKey = new byte[4];
			readFully(input, maskKey);
		}
		byte[] payload = new byte[(int)length];
		readFully(input, payload);
		if (masked) {
			for (int i = 0; i < payload.length; i++) {
				payload[i] ^= maskKey[i % 4];
			}
		}
		if (opcode == 0x8) {
			return null;
		}
		if (opcode != 0x1 || !fin) {
			throw new IllegalStateException("예상하지 못한 WebSocket 프레임입니다. opcode=" + opcode + " fin=" + fin);
		}
		return new String(payload, StandardCharsets.UTF_8);
	}

	private static void readFully(InputStream input, byte[] buffer) throws IOException {
		int offset = 0;
		while (offset < buffer.length) {
			int read = input.read(buffer, offset, buffer.length - offset);
			if (read == -1) {
				throw new EOFException("프록시 WebSocket 프레임을 읽는 중 연결이 끊겼습니다.");
			}
			offset += read;
		}
	}

	private static final class ProxyWebSocket implements Closeable {

		/** 프레임이 이미 시작된 뒤에는 새 프레임을 기다릴 때보다 훨씬 짧게 잡아, 중간에 멈추면 조용히 null을 반환하는 대신 예외로 드러낸다. */
		private static final int FRAME_CONTINUATION_TIMEOUT_MILLIS = 5_000;

		private final Socket socket;
		private final InputStream input;
		final int statusCode;
		final Map<String, String> headers;

		private ProxyWebSocket(Socket socket, InputStream input, int statusCode, Map<String, String> headers) {
			this.socket = socket;
			this.input = input;
			this.statusCode = statusCode;
			this.headers = headers;
		}

		/**
		 * 새 프레임이 아예 도착하지 않으면(첫 바이트 타임아웃) null을 반환한다. 프레임이 도착하기 시작한
		 * 뒤에는 짧은 타임아웃만 허용하고, 그마저 넘기면 스트림이 중간 상태로 오염되므로 예외를 그대로 던진다.
		 */
		String pollTextFrame(int timeoutSeconds) throws IOException {
			socket.setSoTimeout(timeoutSeconds * 1000);
			int byte0;
			try {
				byte0 = input.read();
			} catch (SocketTimeoutException timeout) {
				return null;
			}
			if (byte0 == -1) {
				return null;
			}
			socket.setSoTimeout(FRAME_CONTINUATION_TIMEOUT_MILLIS);
			return readTextFrame(input, byte0);
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}
}
