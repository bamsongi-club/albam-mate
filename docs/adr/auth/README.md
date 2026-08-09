# Auth ADR

인증, 인가와 세션 보안 경계에 관한 결정을 찾는 인덱스다. 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0003](0003-p0-server-session-spring-security.md) | P0 인증에 서버 세션과 Spring Security를 사용 | 승인됨 | 2026-07-24 | 검증됨 |
| [0013](0013-p0-password-storage-auth-request-protection.md) | P0 비밀번호를 bcrypt로 저장하고 인증 요청을 제한 | 승인됨 | 2026-07-25 | 미검증 |
| [0020](0020-api-endpoint-authorization-policy-registry.md) | API 엔드포인트 인가 정책을 등록하고 미분류 경로를 CI에서 거절 | 승인됨 | 2026-07-28 | 검증됨 |
| [0042](0042-p1-oauth-social-identity-and-session-integration.md) | P1 소셜 로그인에 OAuth2/OIDC와 별도 외부 식별자를 사용 | 승인됨 | 2026-08-03 | 검증됨 |
