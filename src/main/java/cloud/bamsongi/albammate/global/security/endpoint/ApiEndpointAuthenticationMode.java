package cloud.bamsongi.albammate.global.security.endpoint;

/** API 엔드포인트가 요구하는 인증 수준이다. */
public enum ApiEndpointAuthenticationMode {
	PUBLIC,
	OPTIONAL_AUTHENTICATION,
	AUTHENTICATED
}
