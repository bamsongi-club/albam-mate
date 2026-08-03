# ADR-0042: P1 소셜 로그인에 OAuth2/OIDC와 별도 외부 식별자를 사용

- 상태: 제안됨
- 작성일: 2026-08-03
- 결정일: 미정
- 관련: [AUTH-05](../../p1/social-login.md#auth-05-소셜-로그인계정-연결), [API](../../API.md#auth-05-소셜-로그인계정-연결), [ERD](../../ERD.md#social_accounts), [GitHub Issue #328](https://github.com/bamsongi-club/albam-mate/issues/328)
- 대체 대상: 없음
- 후속 ADR: 없음

> 이 문서는 팀 검토를 위한 제안이다. 승인 전에는 AUTH-05a~AUTH-05d 구현의 정본으로 사용하지 않는다.

## 맥락

P0는 이메일·비밀번호와 서버 세션만 제공하고 외부 신원 연동을 제외했다. P1에서 Google·Naver·Kakao 로그인을 추가하려면 제공자마다 다른 응답 구조를 하나의 사용자 계정에 연결하면서 기존 `JSESSIONID`·CSRF와 공개 API 계약을 유지해야 한다.

외부 이메일은 동의 여부에 따라 없을 수 있고 바뀔 수 있으며 같은 문자열만으로 두 계정의 소유자가 같다고 확정할 수 없다. OAuth callback에는 외부 token과 authorization code가 존재하지만 알밤메이트는 로그인 뒤 제공자 API를 호출하지 않으므로 이를 장기 저장할 이유가 없다. 또한 로그인 callback은 Spring MVC Controller가 아니라 Spring Security filter가 처리하므로 기존 API 정책 등록부와의 경계를 명시해야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 제공자 이메일을 사용자 키로 자동 병합 | 별도 연결 화면과 테이블이 단순해진다. | 이메일 누락·변경·재사용과 제공자별 검증 차이로 다른 계정을 잘못 합칠 수 있다. | 제외 |
| `users`에 제공자별 ID 컬럼을 추가 | 초기 파일 수가 적다. | 제공자 추가마다 사용자 스키마가 바뀌고 한 사용자의 여러 제공자 연결을 제약하기 어렵다. | 제외 |
| 별도 `social_accounts`에 `provider + subject`를 저장하고 명시적으로 연결 | 제공자 식별과 알밤메이트 사용자를 분리하고 DB 유일 제약으로 중복을 막는다. | 연결 유스케이스와 nullable 비밀번호 계약이 추가된다. | 선택 |
| callback을 직접 구현하고 token 교환·검증을 수동 처리 | 모든 경로를 Controller로 만들 수 있다. | `state`, OIDC 검증과 provider 오류 처리를 직접 재구현해 보안 표면이 커진다. | 제외 |
| Spring Security OAuth2 Client로 Authorization Code 흐름을 처리 | 검증된 `state`·OIDC·OAuth2 구성과 확장 지점을 재사용한다. | Naver의 중첩 사용자 응답과 앱 세션 변환을 별도로 매핑해야 한다. | 선택 |
| 외부 authorized client와 token을 세션·DB에 저장 | 이후 제공자 API 호출을 쉽게 추가할 수 있다. | 현재 필요 없는 민감정보와 갱신·폐기 책임이 생긴다. | 제외 |

## 결정

Spring Security OAuth2 Client의 Authorization Code 흐름을 사용한다. Google과 Kakao는 OpenID Connect의 `sub`, Naver는 회원 프로필의 안정적인 `id`를 provider subject로 매핑한다. 모든 외부 식별자는 `SOCIAL_ACCOUNTS`에 저장하고 `(provider, provider_subject)`와 `(user_id, provider)`를 각각 유일하게 유지한다.

이메일은 [AUTH-05의 제공자별 신뢰 조건](../../p1/social-login.md#제공자-이메일-매핑)을 통과한 선택 정보만 저장한다. Google은 `email_verified`, Kakao는 `is_email_valid`와 `is_email_verified`가 모두 참이어야 하며, 현재 사용하는 Naver 회원 프로필 응답은 별도 검증 상태가 없으므로 이메일 없음으로 매핑한다. 비로그인 첫 로그인에서 처음 보는 외부 식별자의 신뢰 가능한 이메일이 기존 사용자와 같을 때만 자동 병합하지 않고 기존 계정 로그인 뒤 명시적 연결을 요구한다. 인증된 명시적 연결은 이메일 중복을 판정하지 않고 현재 세션 사용자를 대상으로 처리한다. `password_hash`가 없는 소셜 전용 사용자는 제공자 이메일이 저장돼 있어도 이메일 자격증명 조회에서 미존재로 취급해 기존 더미 bcrypt와 `INVALID_CREDENTIALS` 계약을 유지한다. 연결 시작은 인증·CSRF가 필요한 API로 제한하고, callback은 세션의 일회성 연결 의도와 OAuth `state`를 모두 검증한다.

OAuth 성공 뒤 외부 principal을 애플리케이션 권한 주체로 유지하지 않고 기존 `CurrentUserPrincipal`로 바꾼다. `JSESSIONID`를 교체하고 CSRF 토큰을 무효화하며 이후 요청은 기존 서버 세션 계약을 따른다. 외부 authorized client, access·refresh·ID token과 authorization code는 DB나 서버 세션에 저장하지 않는다.

브라우저가 사용하는 authorization·callback 경로는 `/api/auth/social/**` 아래의 Spring Security filter가 소유한다. 이 두 경로는 JSON MVC API가 아니므로 `ApiEndpointPolicyRegistry`의 MVC 대조 대상에는 넣지 않고 `SecurityConfig`의 정확한 matcher와 OAuth 흐름 테스트로 고정한다. 제공자 목록과 연결 시작처럼 Controller가 소유하는 `/api/**` 경로는 기존 정책 등록부에 등록한다.

## 결과

- 얻는 것: 세 제공자의 이메일 제공 여부와 무관한 안정적인 사용자 식별, 명시적 연결, 기존 세션·CSRF 호환과 token 비저장 경계
- 감수할 비용·위험: 사용자·소셜 계정 트랜잭션, 제공자별 속성 매핑과 filter 전용 경로 검증이 추가된다.
- 후속 작업: AUTH-05 명세·API·ERD에 맞는 Flyway·JPA·OAuth 설정·프론트엔드와 자동 검증을 구현하고, 운영 전 세 개발자 콘솔의 redirect URI·동의 항목을 설정한다.

## 보류 및 재검토

- 지금 하지 않는 것: 연결 해제·교체, 계정 병합, provider API 장기 호출과 token 갱신
- 보류 이유: 로그인과 명시적 연결에는 필요하지 않고 개인정보·복구·감사 정책이 추가로 필요하다.
- 다시 검토할 조건: 제공자 캘린더·친구·프로필 API처럼 로그인 이후 권한이 필요한 기능을 승인하거나 실제 계정 병합·연결 해제 요구가 확인될 때

## 참고 자료

- [Spring Security OAuth2](https://docs.spring.io/spring-security/reference/servlet/oauth2/)
- [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
- [Naver Login API](https://developers.naver.com/docs/login/api/)
- [Kakao Login REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api)

## 검증

- 상태: 미검증
- 근거:
    - 계약: AUTH-05 명세, API와 ERD가 provider subject, 명시적 연결, 서버 세션과 token 비저장 경계를 정의한다.
- 미검증:
    - Flyway·JPA·OAuth provider·이메일 신뢰 상태 매핑과 실제 HTTP·PostgreSQL 검증
    - Google·Naver·Kakao 개발자 콘솔을 사용한 운영 redirect·동의 항목 수동 QA

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
