# P1 소셜 로그인 명세

이 문서는 Google·Naver·Kakao 로그인과 기존 알밤메이트 계정의 명시적 연결을 독립적으로 구현·검증하기 위한 승인된 기준이다. 공통 P1 범위는 [P1 명세](../P1-spec.md), HTTP·브라우저 리다이렉트 계약은 [API](../API.md), 저장 구조는 [ERD](../ERD.md)를 따른다.

`AUTH-05`는 사용자 관점의 상위 기능이며 `AUTH-05a`~`AUTH-05d`는 하나의 이슈·브랜치·PR로 구현·검증할 하위 완료 단위다. 상위 기능은 네 하위 단위와 상위 통합 완료 기준을 모두 충족해야 완료된다. 각 하위 구현은 해당 이슈에서 사람이 승인한 최신 전체 `T1`…`Tn` 목록을 착수 테스트 계약으로 사용한다.

## AUTH-05 소셜 로그인·계정 연결

### 구현 컨텍스트

| 구분 | 기준 |
| --- | --- |
| 제품 범위 | [P1 포함 범위](../P1-spec.md#포함-범위) |
| HTTP·리다이렉트 | [AUTH-05 API 계약](../API.md#auth-05-소셜-로그인계정-연결) |
| 데이터 모델 | [USERS](../ERD.md#users), [SOCIAL_ACCOUNTS](../ERD.md#social_accounts) |
| 기술 결정 | [ADR-0042](../adr/auth/0042-p1-oauth-social-identity-and-session-integration.md) |
| 결정·승인 이슈 | [GitHub Issue #328](https://github.com/bamsongi-club/albam-mate/issues/328) |

### 기능 규칙

- 지원 제공자는 `GOOGLE`, `NAVER`, `KAKAO`다. 현재 실행 환경에 Client ID와 Client Secret이 모두 설정된 제공자만 로그인·연결 진입점에 노출한다.
- 외부 사용자는 이메일이 아니라 `provider + providerSubject` 조합으로 식별한다. `providerSubject`는 해당 제공자가 발급한 변경하지 않는 사용자 식별자이며 다른 제공자의 값과 합치지 않는다.
- 첫 로그인에서 같은 외부 식별자가 이미 연결돼 있으면 연결된 알밤메이트 사용자로 로그인한다.
- 비로그인 첫 로그인에서 처음 보는 외부 식별자는 신뢰 조건을 통과한 제공자 이메일이 없거나 다른 사용자와 겹치지 않을 때 새 사용자를 만든다. 이메일은 제공자가 돌려준 시점의 선택 정보일 뿐 이후 로그인 식별자나 자동 연결 근거로 사용하지 않는다.
- 비로그인 첫 로그인에서 처음 보는 외부 식별자의 신뢰 가능한 정규화 이메일이 기존 사용자와 같으면 새 사용자나 연결을 만들지 않고 `link-required` 결과로 돌아간다. 사용자는 기존 계정으로 로그인한 뒤 명시적 연결을 시작해야 한다.
- 제공자 닉네임이 현재 닉네임 계약을 통과하면 첫 사용자 닉네임으로 사용한다. 없거나 유효하지 않으면 제공자별 `Google 사용자`, `Naver 사용자`, `Kakao 사용자`를 사용하며 이후 AUTH-04로 변경할 수 있다.
- 로그인 사용자는 지원·설정된 제공자에 대해 계정 연결을 시작할 수 있다. 시작 요청은 현재 인증과 CSRF가 필요하며 서버 세션에 일회성 연결 의도를 저장한다.
- 연결 callback은 OAuth `state`와 연결 의도에 저장된 제공자·현재 사용자 모두가 일치할 때만 처리한다. 이때 제공자 이메일은 연결 대상을 고르거나 이메일 중복을 판정하는 데 사용하지 않는다. 외부 식별자가 다른 사용자에게 연결돼 있으면 기존 연결을 보존하고 `link-conflict`로 돌아가며, 같은 사용자에게 이미 연결된 식별자는 중복 행 없이 성공한다.
- 사용자 한 명은 제공자마다 외부 계정 하나만 연결할 수 있다. 같은 제공자의 다른 계정으로 교체하거나 연결을 해제하는 기능은 AUTH-05에 포함하지 않는다.
- 로그인·연결 성공 뒤 알밤메이트 인증은 기존 `CurrentUserPrincipal`과 `JSESSIONID`만 사용한다. 세션 ID를 교체하고 기존 CSRF 토큰을 무효화하므로 다음 상태 변경 전에 AUTH-01을 다시 호출한다.
- 로그인 실패·취소는 인증 세션과 사용자를 만들지 않는다. 연결 실패·취소는 기존 알밤메이트 로그인 상태를 유지하되 연결 의도와 OAuth 임시 상태를 폐기한다.
- authorization code, access token, refresh token, ID token과 Client Secret은 사용자·소셜 계정·서버 세션에 저장하거나 API·리다이렉트 URL·로그에 노출하지 않는다. callback 처리 중 사용자 식별에 필요한 최소 시간 동안만 메모리에서 사용한다.
- 이메일·비밀번호 회원가입·로그인·로그아웃과 보호 API의 세션·CSRF 계약은 그대로 유지한다. 소셜 전용 사용자는 비밀번호 로그인을 할 수 없다. 제공자 이메일이 저장돼 있어도 `password_hash`가 없는 사용자는 이메일 자격증명 조회에서 미존재와 동일하게 처리해 더미 bcrypt 검증 뒤 `401 INVALID_CREDENTIALS`를 반환한다.

#### 제공자 이메일 매핑

제공자 응답의 이메일은 아래 신뢰 조건을 통과하고, 앞뒤 공백 제거·소문자 변환 뒤 기존 이메일 형식과 255자 제한을 만족할 때만 `USERS.email` 저장과 비로그인 첫 로그인 충돌 판정에 사용한다.

| 제공자 | 신뢰 조건 |
|---|---|
| Google | 검증된 ID token에 `email`이 있고 `email_verified == true` |
| Naver | 현재 사용하는 회원 프로필 응답에 별도 검증 상태가 없으므로 `response.email`을 사용하지 않음 |
| Kakao | 사용자 정보에 `kakao_account.email`이 있고 `is_email_valid == true`, `is_email_verified == true` |

이메일이나 신뢰 상태가 누락됐거나 조건이 거짓이거나 정규화 뒤 형식·길이 계약을 통과하지 못하면 이메일 없음인 `null`로 매핑한다. 이 경우 이메일을 저장하거나 기존 사용자와의 충돌을 판정하지 않는다. 인증된 명시적 연결은 이메일 값과 무관하게 현재 세션 사용자를 대상으로 처리한다.

### 상위 통합 완료 기준

`AUTH-05`는 `AUTH-05a`~`AUTH-05d`의 완료 기준과 아래 상위 기준을 모두 충족해야 완료된다.

- `AUTH-05-AC1` 설정된 Google·Naver·Kakao가 제공자 목록과 로그인 화면에 노출되고, 설정되지 않은 제공자는 숨겨지며 애플리케이션 기동을 막지 않는다.
- `AUTH-05-AC2` 세 제공자의 첫 로그인과 재로그인이 `provider + providerSubject`로 같은 사용자를 식별하고 사용자·소셜 계정을 중복 생성하지 않는다.
- `AUTH-05-AC3` 제공자별 이메일 신뢰 상태와 선택 이메일·닉네임 누락, 유효하지 않은 이메일·닉네임을 정한 규칙대로 처리하며 필수 subject 누락은 저장 변경 없이 실패한다.
- `AUTH-05-AC4` 비로그인 첫 로그인에서 신뢰 가능한 이메일이 같은 기존 계정을 자동 병합하지 않고 `link-required`로 안내하며, 인증·CSRF를 통과한 명시적 연결은 제공자 이메일 중복과 무관하게 현재 사용자에 외부 식별자를 추가한다.
- `AUTH-05-AC5` 이미 다른 사용자에게 연결된 외부 식별자와 사용자·제공자 중복 연결을 DB 제약과 애플리케이션 규칙이 함께 차단한다.
- `AUTH-05-AC6` 누락·불일치·재사용한 OAuth `state`와 사용자 취소가 로그인·가입·연결을 만들지 않으며 안전한 고정 결과로 프론트엔드에 돌아간다.
- `AUTH-05-AC7` 소셜 로그인 성공 뒤 세션 교체, CSRF 재발급, 보호 API 호출과 로그아웃 뒤 기존 세션 거절이 현재 인증 계약과 일치한다.
- `AUTH-05-AC8` 외부 token·authorization code·secret이 DB·세션·응답·리다이렉트·로그에 남지 않는다.
- `AUTH-05-AC9` 같은 외부 식별자의 동시 첫 로그인에서도 사용자와 소셜 계정이 하나만 남고 이후 재로그인이 같은 사용자로 수렴한다.
- `AUTH-05-AC10` 기존 이메일 회원가입·로그인·로그아웃과 AUTH-04 프로필 기능의 성공·실패 계약이 회귀하지 않는다.

### 하위 구현 완료 단위

#### AUTH-05a 외부 신원 저장 모델·사용자 계약

`USERS` 자격증명 nullability, `SOCIAL_ACCOUNTS` 저장 구조와 `auth → user.contract`에서 호출할 첫 로그인·연결 업무 계약을 담당한다.

- `AUTH-05a-AC1` 전진 Flyway와 JPA가 `USERS`의 nullable 이메일·비밀번호 및 `SOCIAL_ACCOUNTS`의 FK·provider CHECK·두 유일 제약과 일치한다.
- `AUTH-05a-AC2` `provider + providerSubject` 조회, 신규 사용자·소셜 계정의 원자적 생성과 같은 식별자의 재조회가 한 사용자로 수렴한다.
- `AUTH-05a-AC3` 비로그인 첫 로그인에서 처음 보는 외부 식별자의 신뢰 가능한 이메일이 기존 사용자와 같을 때만 새 사용자를 만들지 않고 `link-required` 업무 결과를 반환한다. 인증된 명시적 연결은 이메일 중복을 판정하지 않는다.
- `AUTH-05a-AC4` 같은 외부 식별자의 동시 첫 처리와 중복 연결에서도 DB 제약을 깨거나 기존 연결을 덮어쓰지 않는다.
- `AUTH-05a-AC5` 외부 token·authorization code·secret은 사용자 모듈 계약·Entity·Repository에 들어가지 않는다.
- `AUTH-05a-AC6` 이메일 자격증명 조회는 `password_hash IS NULL`인 사용자를 자격증명 미존재로 반환하고, 이메일 로그인은 기존 더미 bcrypt·요청 제한을 거쳐 `401 INVALID_CREDENTIALS`로 수렴하며 `500`이나 계정 존재 여부를 노출하지 않는다.

#### AUTH-05b 세 제공자 OAuth 로그인·앱 세션 전환

설정된 제공자 목록, authorization·callback, 제공자별 subject 매핑과 소셜 로그인 성공 뒤 기존 앱 세션 전환을 담당한다.

- `AUTH-05b-AC1` Client ID·Secret이 모두 설정된 Google·Naver·Kakao만 활성화하며 설정이 없어도 애플리케이션이 기동한다.
- `AUTH-05b-AC2` Google·Kakao `sub`와 Naver `response.id`를 공통 외부 신원으로 변환한다. Google `email_verified`, Kakao `is_email_valid`·`is_email_verified`가 모두 필요한 값과 검증 상태 누락·거짓, 별도 검증 상태가 없는 Naver 이메일을 제공자 이메일 매핑 규칙대로 처리하고 닉네임 누락은 정한 fallback으로 처리한다.
- `AUTH-05b-AC3` 누락·불일치·재사용한 `state`와 사용자 취소는 사용자·소셜 계정·로그인 세션을 만들지 않는다.
- `AUTH-05b-AC4` callback은 허용된 고정 `socialAuth` 값만 same-site로 리다이렉트하고 code·token·provider 설명·사용자 속성을 복사하지 않는다.
- `AUTH-05b-AC5` 성공 뒤 `CurrentUserPrincipal`, 교체된 `JSESSIONID`, 새 CSRF, 보호 API와 로그아웃이 기존 인증 계약대로 동작한다.
- `AUTH-05b-AC6` 외부 authorized client·principal·token을 DB나 서버 세션에 남기지 않는다.

#### AUTH-05c 로그인 사용자의 명시적 계정 연결

인증·CSRF가 필요한 연결 시작, 일회성 연결 의도와 callback의 충돌·실패 처리를 담당한다.

- `AUTH-05c-AC1` 연결 시작은 로그인·CSRF·설정된 제공자를 검증하고 현재 사용자·제공자의 일회성 의도와 authorization URI만 반환한다.
- `AUTH-05c-AC2` callback은 OAuth `state`와 연결 의도의 제공자·사용자를 함께 검증한 경우에만 연결하며 제공자 이메일 중복과 무관하게 현재 세션 사용자를 연결 대상으로 유지한다.
- `AUTH-05c-AC3` 같은 사용자의 같은 외부 식별자는 한 행으로 수렴하고, 다른 사용자의 외부 식별자나 같은 제공자의 다른 계정은 기존 연결을 보존한 채 `link-conflict`가 된다.
- `AUTH-05c-AC4` 연결 성공은 세션 ID와 CSRF를 교체하고, 실패·취소는 기존 로그인 상태를 유지하면서 연결 의도를 폐기한다.
- `AUTH-05c-AC5` 제공자 목록의 연결 상태, 연결 시작 응답과 JSON 오류가 API·엔드포인트 정책 계약과 일치한다.

#### AUTH-05d 웹 로그인·연결 UX와 실행 구성

로그인 화면, callback 결과 안내, 마이페이지 연결 UI와 환경별 제공자 설정 전달을 담당한다.

- `AUTH-05d-AC1` 로그인 화면은 설정된 제공자만 고정 순서로 표시하고 authorization 경로로 전체 페이지 이동한다.
- `AUTH-05d-AC2` 프론트엔드는 허용된 `socialAuth` 결과만 일반 텍스트로 안내하고 URL에서 제거하며 code·token·provider 오류 설명을 저장하거나 렌더링하지 않는다.
- `AUTH-05d-AC3` 마이페이지는 제공자별 연결 상태를 표시하고 미연결 제공자만 명시적 연결할 수 있으며 연결된 계정의 교체·해제를 제공하지 않는다.
- `AUTH-05d-AC4` 로컬 Vite·Compose와 운영 Compose가 same-site callback Host와 선택적인 여섯 credential 환경 변수를 secret 노출 없이 전달한다.
- `AUTH-05d-AC5` 프론트엔드 빌드와 환경별 callback URI 설정 절차가 재현되고 기존 이메일 로그인·회원가입 화면이 회귀하지 않는다.

### 제외 범위

- JWT·Bearer 인증 전환과 외부 API를 계속 호출하기 위한 token 보관
- 제공자 계정 전체 로그아웃, 연결 해제·교체와 탈퇴 시 제공자 unlink
- 이메일 인증, 비밀번호 추가·재설정과 서로 분리된 기존 사용자 계정의 병합
- 제공자 프로필 이미지·이름의 로그인별 동기화
- 네이티브 Android·iOS SDK와 모바일 딥링크

### 실행 설정

각 제공자는 Client ID·Client Secret 두 값이 모두 있을 때만 활성화한다. 실제 값은 저장소에 커밋하지 않고 로컬 `.env` 또는 운영 비밀 저장소에서 다음 환경 변수로 주입한다.

| 제공자 | Client ID | Client Secret | callback URI |
| --- | --- | --- | --- |
| Google | `ALBAM_MATE_GOOGLE_OAUTH_CLIENT_ID` | `ALBAM_MATE_GOOGLE_OAUTH_CLIENT_SECRET` | `{WEB_BASE_URL}/api/auth/social/callback/google` |
| Naver | `ALBAM_MATE_NAVER_OAUTH_CLIENT_ID` | `ALBAM_MATE_NAVER_OAUTH_CLIENT_SECRET` | `{WEB_BASE_URL}/api/auth/social/callback/naver` |
| Kakao | `ALBAM_MATE_KAKAO_OAUTH_CLIENT_ID` | `ALBAM_MATE_KAKAO_OAUTH_CLIENT_SECRET` | `{WEB_BASE_URL}/api/auth/social/callback/kakao` |

`WEB_BASE_URL`은 사용자가 접속하는 same-site 웹 주소다. 기본 로컬 Vite는 `http://localhost:5173`, 운영은 실제 HTTPS 도메인을 사용한다. 로컬 Vite 프록시는 원래 Host를 보존하고 운영 Nginx는 `X-Forwarded-Host`·`X-Forwarded-Proto`를 전달해 서버가 같은 callback URI를 계산한다. Google·Naver·Kakao 개발자 콘솔에는 환경별 callback URI를 정확히 등록하고, Kakao는 OpenID Connect와 필요한 닉네임·이메일 동의 항목을 활성화한다. 이메일·닉네임은 선택 정보로 취급한다.

## 공통 검증 규칙

- OAuth 제공자 통신은 실제 secret 없이 고정된 provider 응답으로 검증한다. Google·Kakao의 이메일 신뢰 상태가 참·거짓·누락인 응답과 Naver 이메일 응답이 저장·충돌 판정 규칙에 맞게 매핑되는지 포함하고, 운영 수동 QA는 각 개발자 콘솔에 등록한 redirect URI와 동의 항목이 준비된 뒤 별도로 수행한다.
- PostgreSQL 테스트는 `(provider, provider_subject)`와 `(user_id, provider)` 유일 제약, 기존 사용자 마이그레이션 보존과 동시 첫 로그인을 검증한다.
- 실제 HTTP 서버와 쿠키 저장소로 authorization 시작부터 callback, 새 CSRF 조회, 보호 API, 로그아웃까지 한 흐름을 확인한다.
- 프론트엔드는 URL의 고정 `socialAuth` 결과만 해석하고 provider 오류 설명, code와 token을 브라우저 저장소나 화면에 남기지 않는다.
