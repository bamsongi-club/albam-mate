# ADR-0003: P0 인증에 서버 세션과 Spring Security를 사용

- 상태: 승인됨
- 작성일: 2026-07-23
- 결정일: 2026-07-24
- 관련: [ADR-0001](../platform/0001-java-21-spring-boot-4-baseline.md), [P0 명세](../../P0-spec.md), [API 인증 계약](../../API.md#41-인증과-프로필)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

Albam Mate의 P0는 먼저 동작하는 제품 흐름을 빠르게 구현하는 단계다. 현재 범위에는 외부 API와 여러 서비스의 인증 연동, 별도 모바일 클라이언트처럼 토큰 기반 인증이 필요한 요구가 확인되지 않았다. 팀은 이 범위에서 서버 세션과 JWT·refresh token을 비교했다.

JWT·refresh token을 바로 도입하면 토큰 발급뿐 아니라 만료, 갱신, 폐기와 같은 정책도 함께 정해야 한다. 반면 서버 세션만 직접 다루면 보호 API와 공개 API의 인증·인가 경계를 일관되게 적용하기 어렵다고 판단했다. 따라서 현재 구현 속도를 유지하면서 서버가 보호 경로를 통제할 수 있는 조합이 필요하다.

이번 결정의 기준은 다음과 같다.

- 현재 P0 연동 범위에 필요한 인증을 가장 작은 구현 범위로 제공할 것
- 공개 경로와 인증이 필요한 경로를 서버에서 일관되게 구분할 것
- 아직 필요하지 않은 토큰 만료·갱신·폐기 정책을 미리 구현하지 않을 것
- 외부 연동이나 클라이언트 요구가 생겼을 때 재검토할 조건을 남길 것

현재 저장소에는 Spring Security 의존성, 인증 설정과 인증 테스트가 없다. 이 문서는 회의에서 선택한 방향을 기록하며 구현 완료를 의미하지 않는다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 서버 세션 + Spring Security | 현재 P0에서 토큰 수명 주기를 구현하지 않고도 로그인 상태를 서버가 관리하고 보호 경로에 인증·인가 규칙을 적용할 수 있다. | 서버가 세션 상태를 보관한다. 다중 인스턴스로 확장할 때 세션 공유 또는 라우팅 방식을 별도로 결정해야 한다. | 선택 |
| JWT + refresh token + Spring Security | 서버 인스턴스가 인증 세션을 직접 공유하지 않는 방향으로 확장할 수 있고 외부 클라이언트와 연동하기 쉽다. | 현재 요구에 없는 토큰 만료, 갱신, 폐기와 예외 처리를 P0부터 설계하고 구현해야 한다. | 보류 |
| Spring Security 없이 서버 세션만 사용 | 필요한 코드와 의존성을 더 적게 시작할 수 있다. | 공개 경로와 보호 경로의 인증·인가 규칙을 애플리케이션 코드에서 직접 관리해야 하고 이후 Security 도입 시 경계를 다시 정리해야 한다. | 제외 |

## 결정

Albam Mate의 P0 인증은 서버 세션을 사용하고, 인증·인가 경계는 Spring Security로 적용한다. JWT와 refresh token은 P0에 함께 구현하지 않는다.

인증 상태는 세션 쿠키로 전달한다. 세션 쿠키에는 `HttpOnly`를 적용하고 운영 환경에서는 `Secure`를 적용한다. P0는 웹과 API의 same-site 배포를 기본 전제로 삼아 `SameSite=Lax`를 사용하며, cross-site 배포가 필요해지면 쿠키와 CORS 정책을 배포 전에 다시 검토한다.

쿠키 기반 인증이므로 상태를 변경하는 요청에 대한 CSRF 보호를 유지하고 비활성화하지 않는다. 로그아웃은 서버 세션과 Spring Security의 인증 상태를 무효화한다. 공개·보호 API 목록, 쿠키·CSRF의 구체적인 이름과 전달 방식, 로그인·로그아웃의 요청·응답 형식은 P0 및 API 명세와 보안 설정에서 정한다.

## 결과

- 얻는 것: 현재 연동 범위에 필요한 인증을 토큰 수명 주기 없이 구현하면서 보호 API의 접근 규칙을 Spring Security 설정에 모을 수 있다.
- 감수할 비용·위험: 서버가 세션 상태를 보관하고 프론트엔드가 쿠키와 CSRF 토큰을 함께 처리해야 한다. 다중 인스턴스로 확장할 때 세션 공유 방식을 추가로 결정해야 한다.
- 후속 작업: Spring Security 의존성과 설정을 추가하고, P0·API 명세의 세션 쿠키와 CSRF 계약을 구현한다. 공개·보호 경로, 로그인·로그아웃, 세션 만료, 쿠키와 CSRF 처리를 테스트한다.

## 보류 및 재검토

- 지금 하지 않는 것: JWT·refresh token 도입, 서버 세션과 JWT의 혼합 인증
- 보류 이유: 현재 P0에는 외부 API, 다중 서비스 인증 연동, 별도 모바일 클라이언트 요구가 없고 토큰 수명 주기를 추가할 이유가 확인되지 않았다.
- 다시 검토할 조건: 별도 모바일 클라이언트나 제3자 공개 API를 지원할 때, 독립된 Resource Server 또는 명확한 stateless 운영 요구가 생길 때, 다중 인스턴스의 서버 세션 운영 비용이 수용하기 어려울 때

## 참고 자료

- [Spring Security CSRF 보호](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Security 세션 관리](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Spring Security 로그아웃](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html)

## 검증

- 상태: 미검증
- 근거: 2026-07-24에 P0·API 명세를 서버 세션, `JSESSIONID`, CSRF 토큰 조회와 로그아웃 계약으로 정렬했다. 현재 `build.gradle`과 애플리케이션 코드에는 Spring Security 의존성·설정·인증 테스트가 없으므로, 구현 PR과 테스트 결과를 연결한 뒤 검증 상태를 갱신한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
