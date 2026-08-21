# 알밤메이트 API 명세서

- 문서 상태: **현재 제공하는 P0·P1·RANK-02(P2)·AI-01a T1~T5 HTTP·WebSocket 인터페이스 계약 (정본) · P2 `AI-01`~`AI-03`·`MATCH-01`의 승인된 목표 API 계약 포함**. 기능 전체의 계약·구현·검증·배포·실측 현재 상태는 [P2 기능 상태](p2/README.md#기능별-현재-상태)를 따른다.
- 기준 문서: [PRD](PRD.md), [P2 공통 명세](P2-spec.md), [P2 기능 상태](p2/README.md), [P1 종료 명세](archive/p1/README.md), [P0 완료 명세](archive/p0/P0-spec.md), [ERD](ERD.md)

### 이 문서의 범위

| 구분 | 내용 |
|---|---|
| 이 문서가 정본인 것 | 클라이언트와 서버 사이 HTTP·WebSocket 계약 — 경로, 인증·CSRF, handshake, 요청·응답·이벤트 스키마, 쿼리 파라미터, 상태, 오류 코드와 판정 순서 |
| 이 문서가 담지 않는 것 | 제품 규칙의 배경(→ [P2-spec](P2-spec.md), [P2 기능 문서](p2/README.md), [P1 종료 명세](archive/p1/README.md), [P0 완료 명세](archive/p0/P0-spec.md)), 저장 구조·계산식(→ [ERD](ERD.md)), 되돌리기 어려운 기술 결정과 근거(→ [ADR](adr/README.md)) |
| 변경 시 함께 갱신 | API 계약을 바꾸면 같은 변경에서 이 문서와 [엔드포인트별 오류 매트릭스](#11-부록-엔드포인트별-오류-매트릭스)를 함께 갱신하고, 관련 P2 기능 명세·[ERD](ERD.md)·[ADR](adr/README.md)과의 정합을 확인한다. 상세 규칙은 [CONVENTIONS](CONVENTIONS.md#api-응답)를 따른다. |

> `P0`, `P1`, `P2`는 API가 도입되는 제품 단계이며 현재 구현 상태값이 아니다. P0·P1·RANK-02(P2) 계약은 현재 제공 인터페이스로 유지한다. P2 AI 기능은 기능별 제공 상태를 따른다. 현재 `AI-01a`의 동의·추천 orchestration T1~T5와 `AI-03a`의 초안·확인 T1~T6 범위는 제공하며, AI-02 provider 운영 세부·AI-04와 후속 범위는 `구현 예정`이므로 현재 요청에 사용하거나 현재 응답으로 기대하면 안 된다. P1 종료 상태는 [P1 기능 종료 상태](archive/p1/README.md#기능별-종료-상태), P2 진행 상태는 [P2 기능 상태](p2/README.md#기능별-현재-상태)를 따른다.

### 도입 단계와 제공 상태

현재 제공 항목과 목표 항목이 같은 상세 표에 섞이면 `도입 단계`와 `제공 상태`를 행마다 구분한다.

| 구분·값 | 의미 |
|---|---|
| `도입 단계` | 해당 필드·파라미터가 최초로 속한 제품 단계. `P0`, `P1`, `P2`처럼 기록하며 제공 상태가 바뀌어도 유지한다. |
| `제공` | `develop`에 구현이 반영되고 해당 API 계약 검증을 통과해 현재 요청에 사용하거나 현재 응답에서 기대할 수 있다. |
| `구현 예정` | 승인된 목표 계약이지만 아직 현재 요청에 사용하거나 현재 응답에서 기대하면 안 된다. |
| `검토 예정` | 후속 후보로 검토 중이며 목표 계약도 확정되지 않았다. 현재 요청·응답 계약으로 사용하지 않는다. |
| `Deprecated` | 현재 제공하지만 대체 계약으로의 전환 대상이다. 신규 사용자는 사용하지 않는다. |
| `제거` | 현재 요청에서 허용하거나 현재 응답으로 반환하지 않는다. 변경 이력·전환 문맥이 필요할 때만 남긴다. |

`구현 예정`·`검토 예정` 행의 `필수`·`nullable`·기본값은 `제공`으로 전환된 뒤의 목표 스키마를 뜻한다. 구현과 계약 검증을 완료하면 `도입 단계`는 유지하고 `제공 상태`만 `구현 예정`에서 `제공`으로 바꾼다. 하나의 도입 단계와 제공 상태만 담는 절은 필요한 경우 절 설명에서 기본값을 선언하고 두 열을 생략할 수 있다.

이 표의 `제공 상태`는 HTTP·WebSocket 요청·응답에 해당 항목을 현재 적용할 수 있는지만 나타낸다. 기능 전체의 계약 준비·생산 코드·자동 검증·배포·실측 상태는 P1 항목은 [P1 기능 종료 상태](archive/p1/README.md#기능별-종료-상태), P2 항목은 [P2 기능 상태](p2/README.md#기능별-현재-상태)에서 별도로 관리한다.

### 대표 흐름으로 읽기

P0는 `게임부터 찾기`, `사람부터 만나기`, `방 만들기` 세 흐름을 지원한다(→ [P0-spec 핵심 사용자 흐름](archive/p0/P0-spec.md#핵심-사용자-흐름)). 아래는 `게임부터 찾기 → 참가`를 API 호출 순서로 옮긴 예시다. CSRF 토큰을 언제 다시 받아야 하는지까지 포함한다.

~~~text
1.  GET  /api/games?keyword=스플렌더        게임 탐색 (비로그인)
2.  GET  /api/games/{gameId}                게임 상세
3.  GET  /api/rooms?type=GAME_FOCUSED&gameId={gameId}   그 게임의 방 목록
4.  GET  /api/rooms/{roomId}                방 상세 (비로그인 → PublicRoomResponse)
5.  GET  /api/auth/csrf                     CSRF 토큰 조회 (세션 생성 안 됨)
6.  POST /api/auth/signup                   신규 사용자만 계정 생성
7.  POST /api/auth/login                    신규·기존 사용자 로그인
8.  GET  /api/auth/csrf                     로그인 뒤 CSRF 토큰 재조회
9.  POST /api/rooms/{roomId}/participants   선착순 참가
10. GET  /api/users/me/rooms?role=joined    참가한 방 확인
~~~

## 목차

- [1. 공통 계약](#1-공통-계약)
- [2. API 인덱스](#2-api-인덱스)
- [3. Enum](#3-enum)
- [4. 공통 스키마](#4-공통-스키마)
- [5. 인증·프로필 API](#5-인증프로필-api)
- [6. 게임 API](#6-게임-api)
- [7. 방 API](#7-방-api)
- [AI 기능군 API](#ai-기능군-api)
- [8. 참가·대기·내 모임 API](#8-참가대기내-모임-api)
- [9. 알림·채팅 API](#9-알림채팅-api)
- [MATCH-01 실시간 파티 매칭 API](#match-01-실시간-파티-매칭-api)
- [10. 오류 코드](#10-오류-코드)
- [11. 부록: 엔드포인트별 오류 매트릭스](#11-부록-엔드포인트별-오류-매트릭스)

## 1. 공통 계약

### 1.1 HTTP·WebSocket과 데이터 형식

| 항목 | 계약 |
|---|---|
| API prefix | `/api` |
| 요청·응답 본문 | 본문이 있는 HTTP API는 `application/json`; 본문이 없는 API의 `Content-Type` 계약은 각 엔드포인트 명세를 따른다. WebSocket은 Upgrade 뒤 서버 발신 JSON 텍스트 프레임 |
| JSON 필드명 | camelCase |
| 식별자 | JSON에서는 integer, 경로에서는 1 이상의 10진 정수. 형식·범위를 벗어난 경로 값은 대상을 조회하기 전에 `400 VALIDATION_ERROR`로 거절한다. 생성 전략은 [ADR-0006](adr/platform/0006-p0-bigint-identity-ids.md)과 [ERD](ERD.md#테이블-명세)를 따른다 |
| 요청 시각 | RFC 3339 기반 서비스 프로필의 `date-time`. `T`/`t` 구분자와 `Z`/`z` UTC 표기를 허용하며, `±HH:MM` 오프셋도 허용한다. 초는 필수이고 `00`~`59` 또는 윤초 `60`을 허용한다. 윤초 `60`은 Java 21 `Instant`가 표현할 수 있는 직전 `:59` 시각의 `Instant`로 정규화한다 |
| 응답 시각 | RFC 3339 `date-time`, `Asia/Seoul` 기준 `+09:00` |

- 요청 시각의 오프셋이 없거나 형식을 해석할 수 없으면 `400 VALIDATION_ERROR`다. 응답은 `+09:00`으로 반환한다. 내부 저장·비교 기준은 [ADR-0009](adr/platform/0009-utc-time-standard.md)를 따른다.
- `gameId`는 BoardGameGeek의 `bggId`가 아니라 알밤메이트 내부 게임 ID다. 자세한 구분은 [4.4 GameSummary](#44-gamesummary)를 따른다.
이 문서의 API는 다음 HTTP 상태 코드를 사용한다.

| HTTP | 용도 |
|---:|---|
| `101` | WebSocket 프로토콜 전환 |
| `400` | 검증 오류 |
| `401` | 미인증 |
| `403` | 권한 없음 |
| `404` | 대상 없음 |
| `405` | 허용되지 않은 메서드 |
| `406` | 응답 미디어 타입 협상 실패 |
| `409` | 상태·정합성 충돌 |
| `410` | 만료된 리소스 |
| `415` | 지원하지 않는 요청 미디어 타입 |
| `429` | 요청 한도 초과 |
| `500` | 처리하지 않은 서버 오류 |
| `503` | 서비스 일시 사용 불가 |

- 요청 본문으로 기존 리소스의 일부를 수정하는 API는 `PATCH`를 사용한다. 클라이언트가 리소스 전체 표현을 결정해 교체하거나, 경로와 메서드만으로 전체 목표 상태가 결정되는 관계 리소스의 존재를 멱등하게 확정할 때 `PUT`을 사용한다. 따라서 `SEARCH-03` 표시는 request body 없는 `PUT`을 사용한다. 세부 기준과 방 종료 명령의 재시도 기준은 [ADR-0047](adr/platform/0047-http-method-and-target-state-idempotency.md)을 따른다.

JSON 필드는 camelCase를 사용한다. 저장 컬럼(snake_case)과의 대응은 [ERD 테이블 명세](ERD.md#테이블-명세)를 정본으로 한다.

### 1.2 인증·세션·CSRF

P0와 P1은 서버 세션 인증을 사용한다. Bearer access token과 refresh token은 애플리케이션 인증에 사용하지 않는다. AUTH-05는 외부 token을 callback 처리 중에만 사용하고 저장·노출하지 않는다. 현재 세션 기준은 [ADR-0003](adr/auth/0003-p0-server-session-spring-security.md), 소셜 로그인 통합 기준은 승인된 [ADR-0042](adr/auth/0042-p1-oauth-social-identity-and-session-integration.md)를 따른다.

| 항목 | 계약 |
|---|---|
| 인증 쿠키 | `JSESSIONID`; `Path=/`, `HttpOnly`, `SameSite=Lax` |
| CSRF 쿠키 | Host-only `XSRF-TOKEN`; `Path=/`, `HttpOnly`, `SameSite=Lax` |
| 운영 HTTPS | 두 쿠키에 `Secure` 추가 |
| CSRF 헤더 | `GET /api/auth/csrf` 응답의 `headerName` 값 |
| 배포 전제 | 웹과 API의 same-site 배포. cross-site 배포는 지원하지 않는다 |

요청 종류별 세션·CSRF 요구는 다음과 같다.

| 요청 종류 | 세션 | CSRF |
|---|:---:|:---:|
| 공개 `GET` | 불필요 | 불필요 |
| 보호 `GET` | 필요 | 불필요 |
| 보호 WebSocket handshake `GET` | 필요 | 불필요. 허용된 `Origin`을 별도로 검증 |
| 공개 `POST`·`PUT`·`PATCH`·`DELETE` | 불필요 | 필요 |
| 보호 `POST`·`PUT`·`PATCH`·`DELETE` | 필요 | 필요 |

- 상태 변경 요청은 자동 전송되는 `XSRF-TOKEN` 쿠키와, `headerName`이 지정한 헤더에 담은 `token` 값을 함께 전달한다. 클라이언트는 회원가입·로그인 전에 공개 API인 `GET /api/auth/csrf`를 먼저 호출한다. 비로그인 CSRF 조회는 `JSESSIONID`와 서버 세션을 생성하지 않는다.
- 로그인 성공 시 세션 ID를 교체하고 새 `JSESSIONID`를 설정한다. 로그아웃은 서버 세션과 인증 상태를 무효화하고 `JSESSIONID`를 만료시킨다.
- 이메일·소셜 로그인, 소셜 계정 연결과 로그아웃 성공 시 기존 CSRF 토큰이 무효화되므로, 다음 상태 변경 요청 전에 `GET /api/auth/csrf`를 다시 호출한다.
- 소셜 authorization 시작은 `state` 검증을 위해 인증 전에도 임시 서버 세션을 만들 수 있다. 로그인 성공 시 해당 세션 ID를 교체하며 실패·취소 시 임시 인증 상태를 폐기한다.
- 세션이 없거나 만료·무효화된 상태로 보호 API를 호출하면 `UNAUTHENTICATED`, CSRF 토큰이 없거나 유효하지 않으면 `CSRF_TOKEN_INVALID`를 반환한다.
- **오류 우선순위:** 보호 API에서 유효한 세션이 없으면 CSRF 토큰의 유효 여부와 무관하게 `UNAUTHENTICATED`를 `CSRF_TOKEN_INVALID`보다 우선한다.

### 1.3 공통 응답

일반 HTTP API는 공통 응답 객체를 반환한다. `status`는 실제 HTTP 상태 코드와 같으며, 생성 성공 API는 HTTP `201 Created`와 `"status": 201`을 사용한다. WebSocket의 `101 Switching Protocols`와 Upgrade 이후 텍스트 프레임에는 이 envelope를 적용하지 않는다. handshake가 Upgrade 전에 실패하면 공통 오류 응답을 사용한다.

성공 응답:

~~~json
{
  "status": 200,
  "data": {}
}
~~~

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `status` | integer | Y | N | 실제 HTTP 상태 코드와 같은 값 |
| `data` | object | Y | N | 엔드포인트별 응답 모델. 반환할 값이 없으면 빈 객체 `{}` |

실패 응답:

~~~json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "요청값 검증에 실패했습니다.",
  "data": null
}
~~~

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `status` | integer | Y | N | 실제 HTTP 상태 코드와 같은 값 |
| `code` | string | Y | N | [오류 코드](#10-오류-코드)의 코드 |
| `message` | string | Y | N | 오류 코드의 한국어 기본 메시지 |
| `data` | null | Y | Y | 실패 응답에서는 항상 `null` |

클라이언트는 `message`가 아니라 `code`로 오류를 구분한다.

### 1.4 페이지네이션

목록 API는 다음 쿼리 파라미터를 공통으로 사용한다.

| 이름 | 타입 | 필수 | 기본값 | 검증 |
|---|---|:---:|---:|---|
| `page` | integer | N | `0` | 0 이상. 음수면 `VALIDATION_ERROR` |
| `size` | integer | N | `10` | 1 이상 100 이하. 범위 밖이면 `VALIDATION_ERROR` |

`PageResponse<T>`는 `GET /api/games`를 제외한 페이지 번호 기반 목록 API의 응답이다.

~~~json
{
  "status": 200,
  "data": {
    "content": [],
    "page": 0,
    "size": 10,
    "totalElements": 120,
    "totalPages": 12,
    "hasNext": true
  }
}
~~~

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `content` | T[] | Y | N | 현재 페이지 항목. 결과가 없으면 `[]` |
| `page` | integer | Y | N | 0부터 시작하는 현재 페이지 번호 |
| `size` | integer | Y | N | 적용된 페이지 크기 |
| `totalElements` | integer | Y | N | 전체 항목 수 |
| `totalPages` | integer | Y | N | 전체 페이지 수 |
| `hasNext` | boolean | Y | N | 다음 페이지 존재 여부 |

- `GET /api/games`도 같은 `page`·`size` 요청 파라미터를 사용하지만, 전체 건수 없이 다음 항목 존재 여부만 반환하는 `GameListSliceResponse<T>`를 사용한다. 응답에는 `content`, `page`, `size`, `hasNext`만 포함하며 `totalElements`와 `totalPages`는 없다. 상세 계약은 [GAME-01](#game-01-게임-목록검색)을 따른다.
- 클라이언트 지정 `sort`와 응답 필드 `first`, `last`는 지원하지 않는다.
- 목록 API는 아래 고정 정렬을 적용한다. 모든 정렬은 마지막에 내부 `id`를 고유 tie-breaker로 사용해 같은 DB 상태에서 페이지 이동 중 순서가 임의로 바뀌지 않게 한다.

| API | 도입 단계 | 제공 상태 | 고정 정렬 |
|---|:---:|:---:|---|
| `GET /api/games` | P0·P1·P2 | 제공 | `popularity_score DESC, name ASC, id ASC` |
| `GET /api/rooms` | P0·P1 | 제공 | 고정된 `requestTime`의 유효 상태와 필터를 적용한 뒤 `startsAt ASC, id ASC` |
| `GET /api/users/me/rooms` | P0·P1 | 제공 | 고정된 `requestTime`의 유효 상태, `role` 필터와 중복 제거를 적용한 뒤 `startsAt DESC, id DESC` |
| `GET /api/users/me/notifications` | P0·P1 | 제공 | `createdAt DESC, id DESC` |

P1 채팅 이력은 페이지 번호가 아니라 메시지 ID 커서를 사용한다. `beforeMessageId`가 없으면 최신 메시지부터 반환하고, 값이 있으면 해당 ID보다 이전에 저장된 메시지를 반환한다. 한 번에 반환하는 `size`는 1 이상 100 이하이며, 다음 구간이 있으면 `nextBeforeMessageId`와 `hasNext`를 함께 반환한다.

## 2. API 인덱스

기능 ID는 엔드포인트가 아니라 기능 단위다. 로그인·로그아웃은 함께 `AUTH-03`, 프로필 조회·수정은 함께 `AUTH-04`, 방 취소·종료는 함께 `ROOM-05`, 알림 목록·미확인 개수는 함께 `NOTI-02`, 단건·일괄 읽음은 함께 `NOTI-03`, 채팅 전송·이력 조회는 함께 `CHAT-02`에 속한다. P1 기능의 제품 규칙 정본과 P0 도입 당시의 완료 기록을 인덱스에서 구분해 링크한다. P0 완료 기록은 현재 HTTP 계약이나 새 구현 범위의 정본이 아니다.

`단계`는 API 도입 제품 단계다(→ [PRD 로드맵](PRD.md#6-단계별-로드맵)). `P0·P1`은 P0에 도입한 경로를 P1에서 확장한다는 뜻이다. 단계가 늘어도 HTTP 계약인 이 파일을 나누지 않고 표에 행·단계 값을 더한다. 단계 표시는 구현 여부에 따라 바꾸지 않으며, P1 기능은 [P1 기능 종료 상태](archive/p1/README.md#기능별-종료-상태), P2 기능은 [P2 기능 상태](p2/README.md#기능별-현재-상태)에서 현재 제공 여부를 판정한다.

| # | 단계 | 기능 ID | Method | Path | 인증 | CSRF | 성공 |
|---:|:---:|---|---|---|:---:|:---:|:---:|
| 1 | P0 | [AUTH-01](#auth-01-csrf-토큰-조회) · [P0 완료 기록](archive/p0/auth-profile.md#auth-01-csrf-토큰-조회) | GET | `/api/auth/csrf` | N | N | 200 |
| 2 | P0 | [AUTH-02](#auth-02-회원가입) · [P0 완료 기록](archive/p0/auth-profile.md#auth-02-회원가입) | POST | `/api/auth/signup` | N | Y | 201 |
| 3 | P0 | [AUTH-03](#auth-03-로그인) · [P0 완료 기록](archive/p0/auth-profile.md#auth-03-로그인로그아웃) | POST | `/api/auth/login` | N | Y | 200 |
| 4 | P0 | [AUTH-03](#auth-03-로그아웃) · [P0 완료 기록](archive/p0/auth-profile.md#auth-03-로그인로그아웃) | POST | `/api/auth/logout` | Y | Y | 200 |
| 5 | P0 | [AUTH-04](#auth-04-내-프로필-조회) · [P0 완료 기록](archive/p0/auth-profile.md#auth-04-내-프로필-조회수정) | GET | `/api/users/me` | Y | N | 200 |
| 6 | P0 | [AUTH-04](#auth-04-내-프로필-수정) · [P0 완료 기록](archive/p0/auth-profile.md#auth-04-내-프로필-조회수정) | PATCH | `/api/users/me` | Y | Y | 200 |
| 6.1 | P1 | [AUTH-04](#auth-04-프로필-이미지-업로드) · [정본](archive/p1/social-login.md) | POST | `/api/users/me/profile-image` | Y | Y | 200 |
| 6.2 | P1 | [AUTH-04](#auth-04-프로필-이미지-삭제) · [정본](archive/p1/social-login.md) | DELETE | `/api/users/me/profile-image` | Y | Y | 200 |
| 7 | P0·P1·P2 | [GAME-01](#game-01-게임-목록검색) · [RANK-02 정본](p2/game-popularity.md#rank-02) · [P0 완료 기록](archive/p0/game-catalog.md#game-01-게임-목록검색) · [SEARCH-01 정본](archive/p1/search.md#search-01-게임-조건-검색) · [SEARCH-03 정본](archive/p1/search.md#search-03-사용자별-해-본-게임) | GET | `/api/games` | 선택 | N | 200 |
| 8 | P0·P1 | [GAME-02](#game-02-게임-상세-조회) · [P0 완료 기록](archive/p0/game-catalog.md#game-02-게임-상세-조회) · [SEARCH-01 정본](archive/p1/search.md#search-01-게임-조건-검색) · [SEARCH-03 정본](archive/p1/search.md#search-03-사용자별-해-본-게임) | GET | `/api/games/{gameId}` | 선택 | N | 200 |
| 9 | P0 | [ROOM-03](#room-03-방-생성) · [P0 완료 기록](archive/p0/room.md#room-03-방-생성) | POST | `/api/rooms` | Y | Y | 201 |
| 10 | P0·P1 | [ROOM-01](#room-01-방-목록-조회) · [P0 완료 기록](archive/p0/room.md#room-01-방-탐색) · [SEARCH-02 정본](archive/p1/search.md#search-02-방-조건-검색) · [ROOM-08 정본](archive/p1/room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리) | GET | `/api/rooms` | 선택 | N | 200 |
| 11 | P0·P1 | [ROOM-02](#room-02-방-상세-조회) · [P0 완료 기록](archive/p0/room.md#room-02-방-상세) · [ROOM-08 정본](archive/p1/room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리) | GET | `/api/rooms/{roomId}` | 선택 | N | 200 |
| 12 | P0 | [ROOM-04](#room-04-방-수정) · [P0 완료 기록](archive/p0/room.md#room-04-방-수정) | PATCH | `/api/rooms/{roomId}` | Y | Y | 200 |
| 13 | P0 | [ROOM-05](#room-05-방-취소) · [P0 완료 기록](archive/p0/room.md#room-05-방-취소종료) | DELETE | `/api/rooms/{roomId}` | Y | Y | 200 |
| 14 | P0 | [ROOM-05](#room-05-방-종료) · [P0 완료 기록](archive/p0/room.md#room-05-방-취소종료) | PATCH | `/api/rooms/{roomId}/status` | Y | Y | 200 |
| 15 | P0 | [PART-01](#part-01-방-참가재참가) · [P0 완료 기록](archive/p0/participation.md#part-01-방-참가재참가) | POST | `/api/rooms/{roomId}/participants` | Y | Y | 201 |
| 16 | P0·P1 | [PART-02](#part-02-참가-취소) · [P0 완료 기록](archive/p0/participation.md#part-02-참가-취소) · [PART-04 정본](archive/p1/room.md#part-04-선착순-대기열과-자동-승격) | DELETE | `/api/rooms/{roomId}/participants/me` | Y | Y | 200 |
| 17 | P0·P1 | [PART-03](#part-03-내-모임-조회) · [P0 완료 기록](archive/p0/participation.md#part-03-내-모임-조회) · [ROOM-08 정본](archive/p1/room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리) · [CHAT-05 정본](archive/p1/chatting.md#chat-05-내-모임-채팅-진입) | GET | `/api/users/me/rooms` | Y | N | 200 |
| 18 | P1 | [PART-04](#part-04-대기-등록재신청) · [정본](archive/p1/room.md#part-04-선착순-대기열과-자동-승격) | POST | `/api/rooms/{roomId}/waitlist` | Y | Y | 201·200 |
| 19 | P1 | [PART-04](#part-04-본인-대기-상태-조회) · [정본](archive/p1/room.md#part-04-선착순-대기열과-자동-승격) | GET | `/api/rooms/{roomId}/waitlist/me` | Y | N | 200 |
| 20 | P1 | [PART-04](#part-04-대기-취소) · [정본](archive/p1/room.md#part-04-선착순-대기열과-자동-승격) | DELETE | `/api/rooms/{roomId}/waitlist/me` | Y | Y | 200 |
| 21 | P1 | [NOTI-02](#noti-02-내-알림-목록) · [정본](archive/p1/notification.md#noti-02-내-알림-목록미확인-개수) | GET | `/api/users/me/notifications` | Y | N | 200 |
| 22 | P1 | [NOTI-02](#noti-02-내-미확인-알림-수) · [정본](archive/p1/notification.md#noti-02-내-알림-목록미확인-개수) | GET | `/api/users/me/notifications/unread-count` | Y | N | 200 |
| 23 | P1 | [NOTI-03](#noti-03-내-알림-단건-읽음) · [정본](archive/p1/notification.md#noti-03-알림-읽음-처리) | PATCH | `/api/users/me/notifications/{notificationId}` | Y | Y | 200 |
| 24 | P1 | [NOTI-03](#noti-03-내-알림-일괄-읽음) · [정본](archive/p1/notification.md#noti-03-알림-읽음-처리) | PATCH | `/api/users/me/notifications` | Y | Y | 200 |
| 25 | P1 | [CHAT-02](#chat-02-메시지-전송) · [P1 종료 기록](archive/p1/chatting.md#chat-02-메시지-전송이력-조회) | POST | `/api/rooms/{roomId}/chat/messages` | Y | Y | 201·200 |
| 26 | P1 | [CHAT-02](#chat-02-메시지-이력-조회) · [P1 종료 기록](archive/p1/chatting.md#chat-02-메시지-전송이력-조회) | GET | `/api/rooms/{roomId}/chat/messages` | Y | N | 200 |
| 27 | P1 | [CHAT-03](#chat-03-실시간-메시지-구독) · [P1 종료 기록](archive/p1/chatting.md#chat-03-실시간-전달재연결-복구) | GET (Upgrade) | `/api/rooms/{roomId}/chat/ws` | Y | N | 101 |
| 28 | P1 | [AUTH-05](#auth-05-소셜-로그인계정-연결) · [정본](archive/p1/social-login.md#auth-05-소셜-로그인계정-연결) | GET | `/api/auth/social/providers` | 선택 | N | 200 |
| 29 | P1 | [AUTH-05](#소셜-로그인-authorization-시작) · [정본](archive/p1/social-login.md#auth-05-소셜-로그인계정-연결) | GET | `/api/auth/social/authorization/{provider}` | N | N | 302 |
| 30 | P1 | [AUTH-05](#소셜-callback과-고정-결과) · [정본](archive/p1/social-login.md#auth-05-소셜-로그인계정-연결) | GET | `/api/auth/social/callback/{provider}` | N | N | 302 |
| 31 | P1 | [AUTH-05](#소셜-계정-연결-시작) · [정본](archive/p1/social-login.md#auth-05-소셜-로그인계정-연결) | POST | `/api/users/me/social-accounts/{provider}/link` | Y | Y | 200 |
| 32 | P1 | [SEARCH-03](#search-03-해-본-게임-표시) · [정본](archive/p1/search.md#search-03-사용자별-해-본-게임) | PUT | `/api/users/me/played-games/{gameId}` | Y | Y | 200 |
| 33 | P1 | [SEARCH-03](#search-03-해-본-게임-표시-취소) · [정본](archive/p1/search.md#search-03-사용자별-해-본-게임) | DELETE | `/api/users/me/played-games/{gameId}` | Y | Y | 200 |
| 34 | P1 | [GAME-03](#game-03-게임-메커니즘-선택지-조회) · [SEARCH-01 정본](archive/p1/search.md#search-01-게임-조건-검색) | GET | `/api/game-mechanisms` | N | N | 200 |
| 35 | P1 | [GAME-04](#game-04-게임-카테고리-선택지-조회) · [SEARCH-01 정본](archive/p1/search.md#search-01-게임-조건-검색) | GET | `/api/game-categories` | N | N | 200 |
| 36 | P1 | [GAME-05](#game-05-게임-테마-선택지-조회) · [SEARCH-01 정본](archive/p1/search.md#search-01-게임-조건-검색) | GET | `/api/game-themes` | N | N | 200 |
| 37 | P1 | [RANK-01](#rank-01-인기-게임-랭킹-조회) · [정본](archive/p1/ranking.md#rank-01-인기-게임-랭킹) | GET | `/api/game-rankings` | N | N | 200 |
| 37.1 | P2 | [AI-01](#ai-01-동의-조회) · [정본](p2/assistant.md#ai-01-ai-모임-도우미) · API 계약 확정·AI-01a T1~T5 검증 범위 제공 | GET | `/api/assistant/consent` | Y | N | 200 |
| 37.2 | P2 | [AI-01](#ai-01-동의-변경) · [정본](p2/assistant.md#ai-01-ai-모임-도우미) · API 계약 확정·AI-01a T1~T5 검증 범위 제공 | PUT | `/api/assistant/consent` | Y | Y | 200 |
| 37.3 | P2 | [AI-02](#ai-02-자연어-추천) · [정본](p2/assistant.md#ai-02-ai-의도-추출추천provider-운영) · API 계약 확정·AI-01a T1~T5 검증 범위 제공 | POST | `/api/assistant/recommendations` | Y | Y | 200 |
| 37.4 | P2 | [AI-03](#ai-03-초안-생성) · [정본](p2/assistant.md#ai-03-ai-초안확인형-room-생성) · API 계약 확정·AI-03a T1~T6 검증 범위 제공 | POST | `/api/assistant/drafts` | Y | Y | 201 |
| 37.5 | P2 | [AI-03](#ai-03-활성-초안-조회) · [정본](p2/assistant.md#ai-03-ai-초안확인형-room-생성) · API 계약 확정·AI-03a T1~T6 검증 범위 제공 | GET | `/api/assistant/drafts/active` | Y | N | 200·204 |
| 37.6 | P2 | [AI-03](#ai-03-초안-수정) · [정본](p2/assistant.md#ai-03-ai-초안확인형-room-생성) · API 계약 확정·AI-03a T1~T6 검증 범위 제공 | PATCH | `/api/assistant/drafts/{draftId}` | Y | Y | 200 |
| 37.7 | P2 | [AI-03](#ai-03-초안-폐기) · [정본](p2/assistant.md#ai-03-ai-초안확인형-room-생성) · API 계약 확정·AI-03a T1~T6 검증 범위 제공 | DELETE | `/api/assistant/drafts/{draftId}` | Y | Y | 200 |
| 37.8 | P2 | [AI-03](#ai-03-초안-확인과-room-생성) · [정본](p2/assistant.md#ai-03-ai-초안확인형-room-생성) · API 계약 확정·AI-03a T1~T6 검증 범위 제공 | POST | `/api/assistant/drafts/{draftId}/confirm` | Y | Y | 201·200 |
| 38 | P2 | [MATCH-01](#match-01-현재-상태-조회) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | GET | `/api/matches/current` | Y | N | 200 |
| 39 | P2 | [MATCH-01](#match-01-매칭-요청-등록) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | POST | `/api/matches/requests` | Y | Y | 201·200 |
| 40 | P2 | [MATCH-01](#match-01-매칭-요청-취소) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | DELETE | `/api/matches/requests/me` | Y | Y | 200 |
| 41 | P2 | [MATCH-01](#match-01-제안-응답) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | POST | `/api/matches/proposals/{proposalId}/responses` | Y | Y | 200 |
| 42 | P2 | [MATCH-01](#match-01-매칭-채팅-이력-조회) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | GET | `/api/matches/parties/{partyId}/chat/messages` | Y | N | 200 |
| 43 | P2 | [MATCH-01](#match-01-매칭-채팅-메시지-전송) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | POST | `/api/matches/parties/{partyId}/chat/messages` | Y | Y | 201·200 |
| 44 | P2 | [MATCH-01](#match-01-매칭-채팅-실시간-구독) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | GET (Upgrade) | `/api/matches/parties/{partyId}/chat/ws` | Y | N | 101 |
| 45 | P2 | [MATCH-01](#match-01-차단-목록-조회) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | GET | `/api/matches/blocks` | Y | N | 200 |
| 46 | P2 | [MATCH-01](#match-01-사용자-차단) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | PUT | `/api/matches/parties/{partyId}/participants/{participantRef}/block` | Y | Y | 200 |
| 47 | P2 | [MATCH-01](#match-01-차단-해제) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | DELETE | `/api/matches/blocks/{blockId}` | Y | Y | 200 |
| 48 | P2 | [MATCH-01](#match-01-신고-접수) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | POST | `/api/matches/parties/{partyId}/reports` | Y | Y | 201·200 |
| 49 | P2 | [MATCH-01](#match-01-성공-파티-나가기) · [정본](p2/matching.md#match-01-실시간-파티-매칭) · API 계약 준비 완료·구현 예정 | DELETE | `/api/matches/parties/{partyId}/participants/me` | Y | Y | 200 |
| 50 | P2 | [CHAT-07](#chat-07-채팅방-읽음-처리) · [정본](p2/chat.md#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태) · API 계약 준비 완료·제공 | POST | `/api/rooms/{roomId}/chat/read` | Y | Y | 200 |
| 51 | P2 | [CHAT-07](#chat-07-내-미읽음-채팅방-요약) · [정본](p2/chat.md#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태) · API 계약 준비 완료·구현 예정 | GET | `/api/users/me/chat/unread-summary` | Y | N | 200 |

`GET /api/games`, `GET /api/games/{gameId}`, `GET /api/rooms`, `GET /api/rooms/{roomId}`와 `GET /api/auth/social/providers`의 인증은 "선택"이다. 비로그인도 호출할 수 있고, 유효한 세션이 있으면 요청자 기준 값을 계산한다. 단, `GET /api/games`의 유효한 `playedFilter`는 로그인을 요구한다.

## 3. Enum

### RoomType

| 값 | 의미 |
|---|---|
| `GAME_FOCUSED` | 특정 게임 중심 방 |
| `PERSON_FOCUSED` | 사람·분위기 중심 방. 게임 선택은 선택 사항 |

### ExperienceLevel

| 값 | 의미 |
|---|---|
| `ALL_LEVELS` | 모든 경험 수준 환영 |
| `BEGINNER_WELCOME` | 초보자 환영 |
| `EXPERIENCED_PREFERRED` | 경험자 선호 |

권장 표시값이며 P1 방 목록에서 검색 조건으로 사용할 수 있지만 참가 자격 제한으로 사용하지 않는다.

### RoomStatus

| 값 | 의미 |
|---|---|
| `RECRUITING` | 모집 중이며 시작 전 참가할 수 있는 상태 |
| `CLOSED` | 정원 충족 또는 시작 시각 도달로 모집이 종료된 상태 |
| `CANCELED` | 주최자가 취소한 최종 상태 |
| `FINISHED` | 종료된 최종 상태 |

클라이언트가 관찰하는 현재 상태 변화 계약은 이 절이 소유한다. P0 도입 당시의 규칙은 [P0 완료 기록](archive/p0/P0-spec.md#방-상태roomstatus)으로만 참조하고, 목록·내 모임의 조회 유효 상태와 저장 상태 보정 책임은 [ADR-0055](adr/room/0055-room-query-effective-status-and-persistence-correction.md)를 따른다.

| 조건 또는 요청 | 이전 상태 | 이후 상태 | 단계 |
|---|---|---|:---:|
| 방 생성 성공 | 생성 전 | `RECRUITING` | P0 |
| 모집 인원 충족 | `RECRUITING` | `CLOSED` | P0 |
| 현재 시각이 `startsAt`에 도달 | `RECRUITING` | `CLOSED` | P0 |
| 시작 전 참가 취소로 빈자리 발생, 활성 대기자 있음 | `CLOSED` | 첫 대기자 승격 후 `CLOSED` 유지 | P1 |
| 시작 전 참가 취소로 빈자리 발생, 활성 대기자 없음 | `CLOSED` | `RECRUITING` | P0·P1 |
| 주최자가 방 취소 | `RECRUITING` 또는 `CLOSED` | `CANCELED` | P0 |
| 주최자가 시작 시각 이후 방 종료 | `CLOSED` | `FINISHED` | P0 |
| 현재 시각이 `startsAt + 24시간`에 도달 | `CLOSED` | `FINISHED` | P0 |

`CANCELED`와 `FINISHED`는 최종 상태다. 수동 모집 마감·재오픈과 최종 상태 철회는 지원하지 않는다.

### Region

> **도입 단계: P2** · **기능: AI-02·AI-03** · **API 계약 상태: 계약 확정** · **제공 상태: AI-03a T1~T6 검증 범위 제공·AI-02 후속 구현 보류**

| 값 | 의미 |
|---|---|
| `홍대` | 홍대 생활권 |
| `강남` | 강남 생활권 |
| `건대` | 건대 생활권 |
| `잠실` | 잠실 생활권 |

AI-03 초안 요청에서 `region`을 생략하면 호환 기간 동안 `홍대`로 해석한다. 기존 직접 Room 생성 API는 현재 계약을 유지하며, 지역을 포함한 확인형 생성은 AI-03 초안 계약으로만 제공한다. 호환 기간 종료 뒤 필수 전환은 별도 승인한다.

### AI 기능군 목표 enum

> **도입 단계: P2** · **기능: AI-01·AI-02·AI-03** · **API 계약 상태: 계약 확정** · **제공 상태: AI-01a T1~T5와 AI-03a T1~T6 구현·검증 완료**

| 이름 | 값 | 의미 |
|---|---|---|
| `AssistantConsentStatus` | `NOT_GRANTED`, `GRANTED`, `REVOKED` | 외부 AI 처리 동의 상태. 행이 없으면 `NOT_GRANTED` |
| `AssistantConsentDecision` | `GRANT`, `REVOKE` | 동의 저장 또는 철회 요청 |
| `AssistantRecommendationState` | `NEEDS_INPUT`, `RECOMMENDED`, `NO_CANDIDATES`, `UNSUPPORTED` | 누락 조건 추가 질문, 후보 추천, 후보 없음, 지원하지 않는 요청 |
| `AssistantMissingField` | `GAME_STYLE`, `GAME`, `PLAYER_COUNT`, `STARTS_AT`, `REGION` | 액션별 누락 조건. `GAME_STYLE`은 `RECOMMEND`의 추천 검색 조건이고 `GAME`·`PLAYER_COUNT`·`STARTS_AT`·`REGION`은 `CREATE_ROOM`의 방 생성 필드다. 한 응답에 두 집합을 섞지 않는다 |
| `AssistantDraftStatus` | `ACTIVE`, `CONFIRMED`, `DISCARDED` | 임시 초안의 논리 상태 |

### ParticipationStatus

| 값 | 의미 |
|---|---|
| `ACTIVE` | 현재 참가 중 |
| `CANCELED` | 참가 취소 |

### MyRoomRole

`GET /api/users/me/rooms`의 `role` 쿼리 파라미터 값이다.

| 값 | 의미 |
|---|---|
| `all` | `joined`와 `hosted`의 중복 없는 합집합 |
| `joined` | 취소하지 않은 `ACTIVE` 참가 관계의 방. `FINISHED` 방을 포함하고 `CANCELED` 방은 제외 |
| `hosted` | 본인이 개설한 방 |

### MyRole

`MyRoomListItem.myRole`과 `ParticipantRoomResponse.myRole` 값이다.

| 값 | 의미 |
|---|---|
| `HOST` | 방 주최자 |
| `JOINED` | 현재 참가자 |

### WaitlistStatus

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `PART-04`](archive/p1/README.md#기능별-종료-상태)

본인의 ROOM별 최신 대기 결과다.

| 값 | 의미 | `position` |
|---|---|---|
| `WAITING` | 현재 대기 중 | 조회 시점의 1 이상 순번 |
| `PROMOTED` | 빈자리가 생겨 참가자로 자동 승격됨 | `null` |
| `CANCELED` | 사용자가 직접 대기를 취소함 | `null` |
| `EXPIRED` | 모임 시작 시각까지 승격되지 못해 대기가 종료됨 | `null` |
| `ROOM_CANCELED` | 주최자가 방을 취소해 대기가 종료됨 | `null` |

`PROMOTED`는 오류가 아니다. 클라이언트는 표시 문구가 아니라 이 상태값으로 참가 목록에 추가된 결과를 안내한다.

### GamePlayTimeFilter

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `SEARCH-01`](archive/p1/README.md#기능별-종료-상태)

`GET /api/games`의 플레이 시간 구간 값이다. 검증된 최대 플레이 시간을 기준으로 판정한다.

| 값 | 의미 |
|---|---|
| `UP_TO_10` | 10분 이내 |
| `OVER_10_TO_20` | 10분 초과 20분 이하 |
| `OVER_20_TO_30` | 20분 초과 30분 이하 |
| `OVER_30_TO_60` | 30분 초과 60분 이하 |
| `OVER_60_UNDER_90` | 60분 초과 90분 미만 |
| `AT_LEAST_90` | 90분 이상 |

### PlayedFilter

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `SEARCH-03`](archive/p1/README.md#기능별-종료-상태)

`GET /api/games`의 사용자별 해 본 게임 관계 필터다.

| 값 | 의미 |
|---|---|
| `PLAYED_ONLY` | 현재 사용자가 해 본 게임으로 표시한 결과만 반환 |
| `EXCLUDE_PLAYED` | 현재 사용자가 해 본 게임으로 표시한 결과를 제외 |

두 값 모두 로그인한 사용자만 사용할 수 있다. 필터를 생략하면 로그인 여부와 관계없이 사용자 관계로 결과를 제한하지 않는다.

### ThemeMatch

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `SEARCH-01`](archive/p1/README.md#기능별-종료-상태)

`GET /api/games`의 반복 `theme` 조건 결합 방식이다. `theme`을 생략하면 두 값 모두 결과에 영향을 주지 않는다.

| 값 | 의미 |
|---|---|
| `ANY` | 전달한 테마 중 하나라도 포함한 게임 |
| `ALL` | 전달한 테마를 모두 포함한 게임 |

### MechanismMatch

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `SEARCH-01`](archive/p1/README.md#기능별-종료-상태)

`GET /api/games`의 반복 `mechanism` 조건 결합 방식이다. `mechanism`을 생략하면 두 값 모두 결과에 영향을 주지 않는다.

| 값 | 의미 |
|---|---|
| `ANY` | 전달한 공개 메커니즘 중 하나라도 포함한 게임 |
| `ALL` | 전달한 공개 메커니즘을 모두 포함한 게임 |

### SocialProvider

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `AUTH-05`](archive/p1/README.md#기능별-종료-상태)

| 값 | 경로값 | 의미 |
|---|---|---|
| `GOOGLE` | `google` | Google OpenID Connect |
| `NAVER` | `naver` | Naver Login OAuth2 |
| `KAKAO` | `kakao` | Kakao Login OpenID Connect |

### NotificationType

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `NOTI-01`~`NOTI-03`](archive/p1/README.md#기능별-종료-상태)

| 값 | 수신자에게 표시하는 의미 |
|---|---|
| `PARTICIPANT_JOINED` | 모임에 새 참가자가 있음 |
| `PARTICIPANT_CANCELED` | 모임에 빈자리가 생김 |
| `WAITLIST_PROMOTED` | 대기에서 참가자로 자동 확정됨 |
| `ROOM_CANCELED` | 참가 중인 모임이 취소됨 |

`PARTICIPANT_JOINED`는 최초 참가와 취소 뒤 재참가를 구분하지 않는다. `WAITLIST_PROMOTED`는 실제 자동 승격된 사용자에게만 생성되며 주최자용 빈자리 알림과 함께 생성되지 않는다. 알림 응답은 참가자의 닉네임·사용자 ID·이메일을 포함하지 않으며, 클라이언트는 `type`으로 표시 문구·방식과 동작을 렌더링한다.

`NotificationListItem`에는 `message` 필드가 없고 서버도 표시 문구를 생성하거나 저장하지 않는다. P1 웹 클라이언트는 `type`과 `roomTitle`로 문구를 만들며, 정확한 기본 문구와 텍스트 렌더링 규칙은 [알림 프론트엔드 UX 계약](archive/p1/notification.md#프론트엔드-ux-계약)을 따른다. 이 규칙은 참가자 식별자를 문구에 복원하거나 추론하는 근거가 아니다.

### ChatMessageType

> **도입 단계: P2** · **기능: CHAT-06** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 값 | 의미 |
|---|---|
| `USER` | 사용자가 메시지 전송 API로 저장한 메시지 |
| `SYSTEM` | 서버가 참가·참가 취소 확정에 따라 남긴 입장·퇴장 안내 |

`ChatMessageType`은 P1 ROOM 채팅의 종류이며 MATCH 전용 [MatchChatMessageType](#matchchatmessagetype)과 값·저장·멱등성 근거를 공유하지 않는다.

### ChatSystemEventKey

> **도입 단계: P2** · **기능: CHAT-06** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 값 | 의미 |
|---|---|
| `PARTICIPANT_ENTERED` | 참가 확정으로 채팅방에 들어옴. 최초 참가와 취소 뒤 재참가를 구분하지 않음 |
| `PARTICIPANT_LEFT` | 참가 취소 확정으로 채팅방에서 나감 |

대기열 자동 승격, 방 취소, 방 종료는 `CHAT-06` 사건이 아니므로 이 enum 값을 갖지 않는다. 사건 경계는 [CHAT-06 안내를 남기는 사건](p2/chat.md#안내를-남기는-사건)을 따른다.

### MatchCurrentState

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

`GET /api/matches/current`의 현재 화면 상태다. 현재 매칭·성공 파티가 없으면 응답 필드는 `null`이며 이 enum 값을 반환하지 않는다.

| 값 | 의미 |
|---|---|
| `WAITING` | 현재 매칭 요청이 후보를 기다림 |
| `PROPOSED` | 응답 기한 안의 열린 제안이 있음 |
| `PAUSED` | 본인이 응답 기한까지 응답하지 않아 다시 찾기를 기다림 |
| `PREPARING` | 전원 수락 뒤 MATCH 채팅을 준비·복구 중이며 아직 접근할 수 없음 |
| `ACTIVE` | MATCH 전용 채팅이 열려 handoff 정보를 사용할 수 있음 |

### MatchProposalResponseAction

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 값 | 의미 |
|---|---|
| `ACCEPT` | 현재 열린 제안을 수락 |
| `REQUEUE` | 현재 제안을 끝내고 새 대기 시도로 재대기 |
| `CANCEL` | 현재 매칭 요청을 취소 |

### MatchProposalMyResponse

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 값 | 의미 |
|---|---|
| `PENDING` | 아직 이 제안에 유효 응답을 보내지 않음 |
| `ACCEPTED` | 수락을 기록했으며 다른 사용자의 응답 또는 다음 상태 전이를 기다림 |

### MatchChatMessageType

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 값 | 의미 |
|---|---|
| `USER` | 사용자가 HTTP 전송으로 저장한 메시지 |
| `SYSTEM` | 채팅 활성화·종료 예정 알림을 알리는 시스템 메시지 |

### MatchReportReason

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 값 | 의미 |
|---|---|
| `ABUSE_OR_HARASSMENT` | 학대 또는 괴롭힘 |
| `HATE_OR_DISCRIMINATION` | 혐오 또는 차별 |
| `SEXUAL_CONTENT` | 성적 콘텐츠 |
| `SPAM_OR_SCAM` | 스팸 또는 사기 |
| `OTHER_RULE_VIOLATION` | 그 밖의 운영 규칙 위반 |

## 4. 공통 스키마

응답 스키마 표에서 `필수 Y`는 필드가 응답에 항상 포함됨을, `nullable Y`는 값으로 JSON `null`을 허용함을 뜻한다. 이 절의 필드는 모두 응답 값이며, 계산으로 도출하는 필드의 계산식 정본은 [ERD 정원·참가자 표시 규칙](ERD.md#정원참가자-표시-규칙)과 [서비스 규칙](ERD.md#서비스-규칙)이다. 혼합 스키마의 `단계` 열은 필드가 도입되는 제품 단계를 나타내며 구현 상태에 따라 바꾸지 않는다.

### 4.1 UserSummary

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `id` | integer | Y | N | 사용자 ID |
| `nickname` | string | Y | N | 표시 닉네임, 1~50자 |
| `profileImageUrl` | string | Y | Y | 프로필 이미지 URL. 없으면 `null` |

P0 프로필은 닉네임만 제공·수정한다. P1부터 프로필 이미지 URL을 제공한다. 이메일과 인증 정보는 응답에 포함하지 않는다.

### 4.2 NicknameSummary

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `nickname` | string | Y | N | 표시 닉네임 |
| `profileImageUrl` | string | Y | Y | 현재 공개 프로필 이미지 URL. 없으면 `null` |

다른 사용자를 표시할 때 사용하며 사용자 ID는 포함하지 않는다.

### 4.3 CsrfTokenResponse

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `headerName` | string | Y | N | 다음 상태 변경 요청에 토큰을 담을 HTTP 헤더 이름 |
| `token` | string | Y | N | 현재 `XSRF-TOKEN` 쿠키에 대응하는 토큰. 로그인·로그아웃 뒤에는 재사용하지 않는다 |

### 4.4 GameSummary

방 응답의 `game` 요약에 사용한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `id` | integer | Y | N | 알밤메이트 내부 게임 ID |
| `bggId` | integer | Y | N | BoardGameGeek가 부여한 외부 식별자 |
| `name` | string | Y | N | 게임명 |

`/api/games/{gameId}`와 방 생성·수정 요청의 `gameId`에는 내부 `id`를 사용한다. `GameListItem`, `GameDetail`, 방 응답의 `game`은 같은 게임에 동일한 `bggId`를 반환한다.

### 4.5 GameListItem

| 필드 | 타입 | 필수 | nullable | 도입 단계 | 제공 상태 | 설명 |
|---|---|:---:|:---:|:---:|:---:|---|
| `id` | integer | Y | N | P0 | 제공 | 알밤메이트 내부 게임 ID |
| `bggId` | integer | Y | N | P0 | 제공 | BoardGameGeek 식별자 |
| `name` | string | Y | N | P0 | 제공 | 게임명 |
| `englishName` | string | Y | N | P0 | 제공 | 영문명 |
| `imageUrl` | string | Y | Y | P0 | 제공 | 대표 이미지 URL |
| `supportedPlayerCount` | string | Y | N | P0 | 제공 | 표시용 가능 인원. 게임 규칙상 플레이 가능한 범위 (예: `2~4명`) |
| `tag` | string | Y | N | P0 | 제공 | 표시용 게임 스타일 태그. 목록 필터로 사용하지 않는다 |
| `estimatedPlayTime` | string | Y | N | P0 | 제공 | 표시용 예상 시간 (예: `30분`) |
| `complexity` | number | Y | Y | P0 | 제공 | 난이도 표시값 |
| `releaseYear` | integer | Y | Y | P1 | 제공 | BGG 기준 CSV의 `yearpublished`. 미상 값은 `null` |
| `minAge` | integer | Y | Y | P1 | 제공 | BGG thing XML의 `minage`. 누락 또는 `0`은 `null` |
| `upcomingRoomCount` | integer | Y | N | P0 | 제공 | 미래 시점의 `GAME_FOCUSED` 방 중 `CANCELED`·`FINISHED`가 아닌 건수 |
| `playedByMe` | boolean | Y | Y | P1 | 제공 | 유효한 세션에서 본인 표시 관계가 있으면 `true`, 없으면 `false`; 비로그인이면 `null` |

### 4.6 GameDetail

`GameListItem`의 모든 필드와 다음 필드를 포함한다. 따라서 `playedByMe`도 같은 로그인·비로그인 의미로 반환한다.

| 필드 | 타입 | 필수 | nullable | 도입 단계 | 제공 상태 | 설명 |
|---|---|:---:|:---:|:---:|:---:|---|
| `alias` | string | Y | Y | P0 | 제공 | 게임 별칭 |
| `description` | string | Y | N | P0 | 제공 | 간단 설명 |
| `detailDescription` | string | Y | N | P0 | 제공 | 상세 설명 |
| `mechanisms` | GameMechanismSummary[] | Y | N | P1 | 제공 | 연결된 공개 메커니즘의 `nameKo ASC, code ASC` 배열. 관계가 없으면 빈 배열 |

#### GameMechanismSummary

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `code` | string | N | 표시명과 분리된 안정적인 내부 코드 |
| `nameKo` | string | N | 검수된 한국어 표시명 |
| `nameEn` | string | N | BGG 영문명 |

### 4.7 PublicRoomResponse

방을 탐색·참가 판단하는 데 필요한 비식별 정보만 반환한다. `place`, 주최자·참가자 목록과 사용자 ID는 포함하지 않는다.

| 필드 | 타입 | 필수 | nullable | 도입 단계 | 제공 상태 | 설명 |
|---|---|:---:|:---:|:---:|:---:|---|
| `id` | integer | Y | N | P0 | 제공 | 방 ID |
| `roomType` | RoomType | Y | N | P0 | 제공 | 방 유형 |
| `title` | string | Y | N | P0 | 제공 | 방 제목 |
| `description` | string | Y | Y | P0 | 제공 | 모임 소개 |
| `game` | GameSummary | Y | Y | P0 | 제공 | `GAME_FOCUSED`는 필수, `PERSON_FOCUSED`는 `null` 가능 |
| `experienceLevel` | ExperienceLevel | Y | N | P0 | 제공 | 권장 경험 수준 |
| `isRulemasterLed` | boolean | Y | N | P0 | 제공 | 룰마스터 진행 여부 |
| `startsAt` | string(date-time) | Y | N | P0 | 제공 | 시작 시각 |
| `region` | string | Y | N | P0 | 제공 | 고정값 `홍대` |
| `recruitmentCapacity` | integer | Y | N | P0 | 제공 | 주최자를 제외한 모집 인원, 1~10 |
| `participantCount` | integer | Y | N | P0 | 제공 | 주최자 1명 + 현재 `ACTIVE` 참가 관계 수 |
| `remainingRecruitmentSeats` | integer | Y | N | P0 | 제공 | `recruitmentCapacity − 현재 ACTIVE 참가 관계 수` |
| `status` | RoomStatus | Y | N | P0 | 제공 | 목록·내 모임에서는 고정된 `requestTime`의 유효 상태, 상세에서는 대상 ROOM 보정 뒤 저장 상태 |
| `joinable` | boolean | Y | N | P0 | 제공 | 현재 요청자의 참가 가능 여부. 판정 규칙은 아래 참고 |
| `waitlistable` | boolean | Y | N | P1 | 제공 | 현재 요청자의 대기 신청 가능 여부. 판정 규칙은 아래 참고 |

`joinable`과 `waitlistable`은 서버의 같은 행동 가능성 판정에서 계산하며 동시에 `true`일 수 없다. 목록·내 모임 조회의 `status`, `joinable`, `waitlistable`과 내 모임의 `chatAvailable`은 [ADR-0055](adr/room/0055-room-query-effective-status-and-persistence-correction.md)의 하나의 고정된 `requestTime`과 [ADR-0056](adr/room/0056-postgresql-room-query-snapshot-without-global-pre-correction.md)의 같은 PostgreSQL snapshot 관계 사실을 사용한다. DTO 조립 단계에서 현재 시각이나 ROOM·참가·대기 관계를 다시 읽어 다른 시점의 값을 섞지 않는다.

`joinable`은 다음을 **모두** 만족할 때만 `true`이고, 그 외에는 `false`다.

1. 요청자가 로그인했다.
2. 요청자가 주최자도, 현재 `ACTIVE` 참가자도, 현재 `WAITING` 대기자도 아니다.
3. 방 상태가 `RECRUITING`이다.
4. 요청 기준 시각이 `startsAt`보다 이르다(`requestTime < startsAt`).
5. `remainingRecruitmentSeats`가 1 이상이다.

기존 `CANCELED` 참가 관계를 가진 사용자도 위 조건을 만족하면 재참가할 수 있어 `true`다.

`waitlistable`은 다음을 **모두** 만족할 때만 `true`이고, 그 외에는 `false`다.

1. 요청자가 로그인했다.
2. 요청자가 주최자도, 현재 `ACTIVE` 참가자도, 현재 `WAITING` 대기자도 아니다.
3. 방 상태가 정원 충족으로 `CLOSED`다.
4. 요청 기준 시각이 `startsAt`보다 이르다(`requestTime < startsAt`).
5. `remainingRecruitmentSeats`가 `0`이다.

직접 참가 또는 대기를 취소한 사용자는 현재 조건을 다시 충족하면 각각 참가하거나 대기할 수 있다. 직접 참가 요청이 좌석 경합으로 실패해도 서버는 해당 요청으로 대기 관계를 만들지 않는다.

이미 현재 `WAITING`인 요청자는 두 값이 모두 `false`이며, 현재 `WAITING`은 독립적인 `joinable=true` 조건이 아니다. 위에서 정의한 직접 참가·대기 신청 조건을 충족하지 않는 조합도 두 값이 모두 `false`다.

### 4.8 ParticipantRoomResponse

주최자 또는 현재 `ACTIVE` 참가자에게 반환하며, `PublicRoomResponse`의 모든 필드에 다음을 추가한다.

상속 필드의 `도입 단계`와 `제공 상태`는 `PublicRoomResponse` 표를 따른다. 따라서 `waitlistable`은 현재 `ParticipantRoomResponse`에도 포함된다. 아래 추가 필드는 모두 `P0`에 도입되어 현재 `제공` 중이므로 두 열을 생략한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `myRole` | MyRole | Y | N | 요청자와 방의 관계 |
| `place` | string | Y | N | 정확한 장소 |
| `host` | NicknameSummary | Y | N | 주최자 |
| `participants` | NicknameSummary[] | Y | N | 주최자와 현재 `ACTIVE` 참가자 |

`host`와 `participants`는 사용자 ID를 포함하지 않으므로, 클라이언트는 `myRole`로 요청자의 역할을 판정한다. ROOM-02 상세 조회는 주최자에게 `HOST`, 현재 `ACTIVE` 참가자에게 `JOINED`를 반환한다. 주최자 전용인 ROOM-03 생성과 ROOM-04 수정 응답은 항상 `HOST`다.

### 4.9 RoomParticipationResponse

참가·참가 취소 요청의 응답이다. 모든 값은 참가 관계 변경과 모집 상태 전이가 끝난 뒤의 값이다.

> 필드 구조는 P0 계약이고, 활성 대기자 자동 승격 뒤 최종 값을 반환하는 동작은 [P1 `PART-04` 계약](archive/p1/room.md#part-04-선착순-대기열과-자동-승격)이다. 현재 상태는 [P1 기능 종료 상태](archive/p1/README.md#기능별-종료-상태)을 따른다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 방 ID |
| `participationStatus` | ParticipationStatus | Y | N | 변경 후 참가 상태. 참가 성공은 `ACTIVE`, 취소 성공은 `CANCELED` |
| `roomStatus` | RoomStatus | Y | N | 변경 후 방 상태 |
| `participantCount` | integer | Y | N | 변경 후 전체 참가자 수 |
| `remainingRecruitmentSeats` | integer | Y | N | 변경 후 남은 모집 자리 |

마지막 좌석을 채우는 참가라면 `roomStatus`·`participantCount`·`remainingRecruitmentSeats`는 각각 `CLOSED`, 최종 참가자 수, `0`이 된다. 시작 전 참가 취소 시 활성 대기자가 있으면 첫 대기자 한 명의 승격까지 완료한 뒤의 최종 값을 반환하며, 승격 여부와 승격된 사용자 신원은 응답에 추가하지 않는다.

### 4.10 MyRoomListItem

`GET /api/users/me/rooms`의 각 항목이며, `PublicRoomResponse`의 모든 필드에 다음을 추가한다. 정확한 `place`와 참가자 목록은 내 모임 이력에도 포함하지 않는다.

> 상속 필드의 상태는 `PublicRoomResponse` 표를 따른다.

| 필드 | 타입 | 필수 | nullable | 도입 단계 | 제공 상태 | 설명 |
|---|---|:---:|:---:|:---:|:---:|---|
| `myRole` | MyRole | Y | N | P0 | 제공 | `HOST` 또는 `JOINED` |
| `participationStatus` | ParticipationStatus | Y | Y | P0 | 제공 | `myRole = JOINED`이면 항상 `ACTIVE`, `HOST`이면 `null` |
| `chatAvailable` | boolean | Y | N | P1 | 제공 | 현재 요청자가 채팅 API에 접근할 수 있는지. `HOST` 또는 `ACTIVE` 참가자이고 응답 유효 상태가 `RECRUITING`·`CLOSED`일 때만 `true`. 프론트엔드의 직접 진입점은 모임 상세이며 내 모임 목록에서는 이 필드로 채팅 버튼을 표시하지 않는다 |
| `lastMessagePreview` | string | Y | Y | P2 `CHAT-07` | 제공 | 채팅방의 마지막 메시지 본문(`SYSTEM`이면 조립된 안내 문장). 메시지가 없거나 `chatAvailable = false`이면 `null` |
| `lastMessageAt` | string(date-time) | Y | Y | P2 `CHAT-07` | 제공 | 마지막 메시지의 저장 시각. 메시지가 없으면 `null` |
| `unreadCount` | integer | Y | N | P2 `CHAT-07` | 제공 | 요청자가 이 방에서 아직 읽지 않은 메시지 수. 본인이 보낸 `USER` 메시지와 본인이 대상인 `SYSTEM` 메시지는 세지 않는다. `chatAvailable = false`이면 `0` |

`joinable`과 `waitlistable`은 `PublicRoomResponse`와 같은 요청자 기준 값이다. 내 모임은 주최·참가 ROOM만 반환하므로 두 값은 항상 `false`이고, 대기 중인 ROOM을 조회 대상에 추가하지 않는다. `chatAvailable`은 서버 접근 가능성의 계약 일치를 위한 값이며, 채팅 버튼은 모임 상세의 `myRole`·대상 ROOM 보정 뒤 저장 상태 기준으로 표시한다. 내 모임 목록에는 중복 채팅 진입을 표시하지 않으며, 직접 채팅 API를 호출해도 서버가 같은 관계·상태 규칙으로 거절한다.

`lastMessagePreview`·`lastMessageAt`·`unreadCount`는 [#862](https://github.com/bamsongi-club/albam-mate/pull/862)가 구현해 `GET /api/users/me/rooms`가 제공한다. 계산 방식·저장 구조는 [CHAT-07 계약](#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태-계약)이 소유한다.

### 4.11 RoomStatusResponse

방 취소·종료 응답이다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 방 ID |
| `roomStatus` | RoomStatus | Y | N | 변경 후 상태 |

### 4.12 NotificationListItem

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `NOTI-02`·`NOTI-03`](archive/p1/README.md#기능별-종료-상태)

본인 알림 목록과 단건 읽음 응답에 사용한다. 물리 필드와 조회·읽음 제약은 [ERD의 NOTIFICATIONS](ERD.md#notifications)를 따른다. 저장된 알림은 관련 방에 대한 접근 권한이 아니며, 클라이언트가 `roomId`로 이동할 때 `GET /api/rooms/{roomId}`의 현재 권한과 존재 여부 은닉 계약을 다시 적용한다. 원인 이벤트 시각에 [운영 정본의 `NOTIFICATION_RETENTION`](guides/NOTIFICATION_OPERATIONS.md#현재-운영-파라미터-정본)을 더한 시각이 지난 알림은 물리 삭제 전에도 이 응답 대상이 아니다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `id` | integer | Y | N | 알림 ID |
| `type` | NotificationType | Y | N | 알림 유형 |
| `roomId` | integer | Y | N | 관련 방 ID. 방 조회 권한을 부여하지 않음 |
| `roomTitle` | string | Y | N | 조회 시점의 현재 방 제목, 최대 100자. 원인 이벤트 시점 스냅샷이 아님 |
| `readAt` | string(date-time) | Y | Y | 단건·일괄 읽음 PostgreSQL 문장이 고정한 최초 `operationTime`. 미확인이면 `null` |
| `createdAt` | string(date-time) | Y | N | 원인 Command Coordinator가 최초 시도 전에 고정한 `requestTime`. relay 처리·Notification 기록 시각이 아님 |

### 4.13 UnreadNotificationCountResponse

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `NOTI-02`](archive/p1/README.md#기능별-종료-상태)

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `unreadCount` | integer | Y | N | 보존 기간 안의 본인 알림 중 `readAt = null`인 건수, 0 이상 |

### 4.14 NotificationBulkReadResponse

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `NOTI-03`](archive/p1/README.md#기능별-종료-상태)

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `updatedCount` | integer | Y | N | 이번 요청에서 처음 읽음 처리한 보존 기간 안의 알림 수, 0 이상 |
| `readAt` | string(date-time) | Y | N | 이번 일괄 읽음 SQL 내부에서 `clock_timestamp()`를 한 번 평가해 고정한 `operationTime`. 변경 대상이 없어도 반환 |
| `boundaryNotificationId` | integer | Y | Y | 요청이 고정한 보존 기간 안의 본인 알림 집합에서 가장 큰 알림 ID. 대상 알림이 없으면 `null` |

### 4.15 ChatMessage

> **단계: P1 계약 + P2 `CHAT-06` 확장** · 현재 상태: [P1 기능 종료 상태의 `CHAT-02`](archive/p1/README.md#기능별-종료-상태)와 [P2 기능 상태의 `CHAT-06`](p2/README.md#기능별-현재-상태)

채팅 이력과 메시지 전송 성공 응답에 사용한다. 메시지 본문은 일반 텍스트로만 반환하며 HTML·스크립트로 해석하지 않는다.

| 필드 | 타입 | 필수 | nullable | 도입 단계 | 제공 상태 | 설명 |
|---|---|:---:|:---:|:---:|:---:|---|
| `messageId` | integer | Y | N | P1 | 제공 | 서버가 저장 순서에 사용하는 메시지 ID |
| `roomId` | integer | Y | N | P1 | 제공 | 채팅 대상 방 ID |
| `messageType` | ChatMessageType | Y | N | P2 `CHAT-06` | 구현 예정 | 사용자 메시지와 입장·퇴장 안내를 구분하는 종류 |
| `clientMessageId` | string | Y | Y | P1 | 제공 | 클라이언트가 재시도 멱등성에 사용하는 1~100자 식별자. `messageType = SYSTEM`이면 `null` |
| `sender` | NicknameSummary | Y | Y | P1 | 제공 | 작성자 표시명. `messageType = SYSTEM`이면 `null` |
| `isMine` | boolean | Y | N | P1 | 제공 | 서버가 현재 요청자와 발신자가 같은지 계산한 값. 사용자 ID는 노출하지 않으며 `messageType = SYSTEM`이면 항상 `false` |
| `systemEvent` | ChatSystemEventKey | Y | Y | P2 `CHAT-06` | 구현 예정 | 안내를 만든 사건. `messageType = USER`이면 `null` |
| `subject` | NicknameSummary | Y | Y | P2 `CHAT-06` | 구현 예정 | 안내 대상 사용자의 현재 표시명. `messageType = USER`이면 `null` |
| `content` | string | Y | N | P1 | 제공 | `USER`는 앞뒤 공백 제거 후 1~500자의 일반 텍스트, `SYSTEM`은 서버가 읽기 시점에 조립한 안내 문장 |
| `createdAt` | string(date-time) | Y | N | P1 | 제공 | 서버가 저장한 시각 |

`messageType`·`systemEvent`·`subject`는 `CHAT-06`의 목표 계약이며 현재 제공 필드가 아니다. `clientMessageId`·`sender`의 `null` 허용도 `SYSTEM` 메시지에만 해당하므로, `CHAT-06` 구현 전까지 모든 메시지는 `USER`이고 두 필드는 `null`이 되지 않는다. `SYSTEM` 메시지의 `content`는 저장된 값이 아니라 서버가 사건 키와 대상 사용자의 현재 공개 닉네임으로 조립한 문장이며, 문구와 대체 표시명의 정본은 [입장·퇴장 시스템 메시지 계약](#chat-06-입장퇴장-시스템-메시지-계약)이다. 저장 계층에는 완성 문장과 닉네임 사본을 두지 않는다([ADR-0078](adr/chat/0078-chat-system-message-storage-and-read-time-composition.md)).

### 4.16 ChatMessagePage

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `CHAT-02`](archive/p1/README.md#기능별-종료-상태)

`GET /api/rooms/{roomId}/chat/messages`의 응답이다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `messages` | ChatMessage[] | Y | N | 최신 메시지부터 `messageId` 내림차순으로 반환한 구간 |
| `nextBeforeMessageId` | integer | Y | Y | 다음 과거 구간을 조회할 커서. 더 없으면 `null` |
| `hasNext` | boolean | Y | N | 더 과거 메시지 존재 여부 |

### 4.17 ChatMessageEvent

> **단계: P1 계약 + P2 `CHAT-06` 확장** · 현재 상태: [P1 기능 종료 상태의 `CHAT-03`](archive/p1/README.md#기능별-종료-상태)와 [P2 기능 상태의 `CHAT-06`](p2/README.md#기능별-현재-상태)

`GET /api/rooms/{roomId}/chat/ws`로 Upgrade한 WebSocket이 보내는 서버 발신 텍스트 이벤트다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `eventId` | integer | Y | N | 중복 제거와 재연결 기준으로 사용하는 `messageId` |
| `type` | string | Y | N | `MESSAGE_CREATED`만 사용 |
| `message` | ChatMessage | Y | N | 커밋된 사용자 메시지 또는 입장·퇴장 안내 |

`CHAT-06`의 입장·퇴장 안내도 같은 `MESSAGE_CREATED` 이벤트로 전달하며 별도 이벤트 타입을 만들지 않는다. `eventId = messageId` 불변식과 재연결 커서를 하나로 유지하고 클라이언트가 종류별 수신 경로를 나누지 않게 하기 위해, 두 종류의 구분은 `message.messageType`으로만 판정한다.

### 4.18 MyRoomWaitlistResponse

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `PART-04`](archive/p1/README.md#기능별-종료-상태)

`PART-04` 대기 등록·조회 응답이다. 서버는 상태 조회에 필요한 ROOM·사용자별 최신 대기 결과를 보존한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 방 ID |
| `waitlistStatus` | WaitlistStatus | Y | N | 본인의 최신 대기 상태 |
| `position` | integer | Y | Y | `WAITING`일 때만 조회 시점의 1 이상 순번, 그 외에는 `null` |

### 4.19 SocialProviderItem

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `provider` | SocialProvider | Y | N | 설정된 제공자 |
| `authorizationUri` | string | Y | N | same-site `/api/auth/social/authorization/{provider}` 경로 |
| `linked` | boolean | Y | N | 로그인 사용자는 현재 연결 여부, 비로그인 사용자는 `false` |

### 4.20 SocialAuthorizationResponse

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `authorizationUri` | string | Y | N | 연결 인증을 계속할 same-site authorization 경로 |

### 4.21 PlayedGameStateResponse

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `SEARCH-03`](archive/p1/README.md#기능별-종료-상태)

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `gameId` | integer | Y | N | 표시하거나 표시를 취소한 알밤메이트 내부 게임 ID |
| `playedByMe` | boolean | Y | N | 표시 성공은 `true`, 표시 취소 성공은 `false` |

### 4.22 CurrentMatchStateResponse

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

PostgreSQL에 커밋된 매칭 요청·제안·성공 파티·채팅 접근 관계를 하나의 읽기 snapshot에서 조합한 현재 화면 복구 정본이다. `operationTime`은 snapshot을 고정한 시각이며 항상 반환하고, 그 밖의 상태별 필드만 아래 표에 따라 `null`이 될 수 있다. 현재 매칭 요청과 성공 파티가 모두 없으면 `operationTime`을 제외한 모든 필드가 `null`이다. 만료된 `OPEN` 제안과 `PREPARING` 기한 초과 Party는 이 응답에서 살아 있는 상태로 반환하지 않으며, 먼저 해당 recovery·terminal Executor가 최신 저장 상태를 확정한 뒤 snapshot을 시작한다.

| `state` | `request` | `proposal` | `preparing` | `chat` |
|---|---|---|---|---|
| `WAITING` | `MatchRequestSummary` | `null` | `null` | `null` |
| `PROPOSED` | `MatchRequestSummary` | `MatchProposalSummary` | `null` | `null` |
| `PAUSED` | `MatchRequestSummary` | `null` | `null` | `null` |
| `PREPARING` | `null` | `null` | `MatchPreparingSummary` | `null` |
| `ACTIVE` | `null` | `null` | `null` | `MatchChatHandoff` |
| 현재 대상 없음 | `null` | `null` | `null` | `null` |

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `operationTime` | string(date-time) | Y | N | current-state read transaction이 첫 업무 조회 전에 `transaction_timestamp()`로 고정한 snapshot 기준 시각 |
| `state` | MatchCurrentState | Y | Y | 현재 화면 상태. 현재 대상이 없으면 `null` |
| `request` | MatchRequestSummary | Y | Y | `WAITING`·`PROPOSED`·`PAUSED`에서만 현재 요청 |
| `proposal` | MatchProposalSummary | Y | Y | `PROPOSED`에서만 열린 제안 |
| `preparing` | MatchPreparingSummary | Y | Y | `PREPARING`에서만 준비 상태. 채팅 경로는 포함하지 않음 |
| `chat` | MatchChatHandoff | Y | Y | `ACTIVE`에서만 연결 정보를 제공 |

### 4.23 MatchRequestSummary

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `minPlayers` | integer | Y | N | `1`~`32767` 범위에서 사용자가 등록한 희망 인원 범위의 하한 |
| `maxPlayers` | integer | Y | N | `minPlayers` 이상 `32767` 이하인 희망 인원 범위의 상한 |
| `queuedAt` | string(date-time) | Y | N | 현재 대기 시도를 시작한 시각 |

### 4.24 MatchProposalSummary

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

`members`에는 공개 프로필 이미지와 실제 파티 인원만 담으며 닉네임·사용자 ID·이메일·매칭 조건을 포함하지 않는다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `proposalId` | integer | Y | N | 열린 제안 ID |
| `partySize` | integer | Y | N | 연결된 요청 인원 범위 교집합의 하한으로 고정한 실제 파티 인원 |
| `members` | MatchProposalMemberPreview[] | Y | N | 제안 참가 예정자의 공개 프로필 이미지 |
| `respondBy` | string(date-time) | Y | N | 제안 생성 시 고정한 응답 기한. 기한 규칙은 [MATCH-01 후보 파티와 제안](p2/matching.md#후보-파티와-제안)을 따름 |
| `myResponse` | MatchProposalMyResponse | Y | N | 요청자의 현재 유효 응답 |

### 4.25 MatchProposalMemberPreview

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `profileImageUrl` | string | Y | Y | 공개 프로필 이미지 URL. 없으면 `null` |

### 4.26 MatchPreparingSummary

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `preparingStartedAt` | string(date-time) | Y | N | 전원 수락으로 성공 파티를 확정한 시각 |
| `prepareUntil` | string(date-time) | Y | N | 채팅 생성·복구를 시도하는 제품 기한. 계산 규칙은 [MATCH-01 성공 파티 채팅](p2/matching.md#성공-파티-채팅)을 따름 |

### 4.27 MatchChatHandoff

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

`ACTIVE` 상태에서만 반환한다. `partyId`와 세 경로는 MATCH 전용이며 기존 `/api/rooms/{roomId}/chat/**` 경로나 ROOM 접근 규칙을 재사용하지 않는다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `partyId` | integer | Y | N | 성공 매칭 파티 ID |
| `members` | MatchPartyMember[] | Y | N | 확정된 성공 파티의 현재 공개 프로필 목록. 사용자 ID·이메일·인증 정보는 포함하지 않음 |
| `chatOpenedAt` | string(date-time) | Y | N | 채팅이 처음 사용 가능해진 시각 |
| `closesAt` | string(date-time) | Y | N | 예약 종료 시각 |
| `historyPath` | string | Y | N | `GET /api/matches/parties/{partyId}/chat/messages` 경로 |
| `sendPath` | string | Y | N | `POST /api/matches/parties/{partyId}/chat/messages` 경로 |
| `webSocketPath` | string | Y | N | `GET /api/matches/parties/{partyId}/chat/ws` Upgrade 경로 |

### 4.28 MatchChatMessage

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `messageId` | integer | Y | N | 서버 저장 순서·재연결 커서에 쓰는 메시지 ID |
| `partyId` | integer | Y | N | 성공 매칭 파티 ID |
| `type` | MatchChatMessageType | Y | N | 사용자 또는 시스템 메시지 |
| `clientMessageId` | string | Y | Y | `USER` 메시지의 전송 멱등성 식별자. `SYSTEM`이면 `null` |
| `sender` | MatchChatSender | Y | Y | `USER` 작성자의 Party-scoped opaque 식별자와 현재 공개 닉네임. `SYSTEM`이면 `null` |
| `isMine` | boolean | Y | N | 현재 요청자가 작성한 `USER` 메시지이면 `true`; 시스템 메시지는 `false` |
| `content` | string | Y | N | 일반 텍스트 메시지 |
| `createdAt` | string(date-time) | Y | N | 서버 저장 시각 |

`MatchChatSender`는 다음 필드만 제공한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `participantRef` | string | Y | N | 해당 Party에서만 의미 있는 opaque participant reference. 사용자 ID가 아님 |
| `nickname` | string | Y | N | 현재 공개 닉네임 |

### 4.29 MatchChatMessagePage

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `messages` | MatchChatMessage[] | Y | N | 최신 메시지부터 `messageId` 내림차순으로 반환한 구간 |
| `nextBeforeMessageId` | integer | Y | Y | 다음 과거 구간의 커서. 더 없으면 `null` |
| `hasNext` | boolean | Y | N | 더 과거 메시지 존재 여부 |

### 4.30 MatchChatMessageEvent

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `eventId` | integer | Y | N | 중복 제거와 재연결 기준으로 쓰는 `messageId` |
| `type` | string | Y | N | `MESSAGE_CREATED` |
| `message` | MatchChatMessage | Y | N | 커밋된 사용자 또는 시스템 메시지 |

### 4.31 MatchBlockListItem

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `blockId` | integer | Y | N | 차단 관계 ID. 차단 해제에 사용하며 사용자 ID가 아님 |
| `blockedUser` | MatchBlockedUserSummary | Y | N | 차단 목록 표시용 현재 공개 프로필. 사용자 ID는 포함하지 않음 |
| `blockedAt` | string(date-time) | Y | N | 차단 관계를 처음 설정한 시각 |

`MatchBlockedUserSummary`는 다음 필드만 제공한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `nickname` | string | Y | N | 현재 공개 닉네임 |
| `profileImageUrl` | string | Y | Y | 현재 공개 프로필 이미지 URL. 없으면 `null` |

### 4.32 MatchReportReceipt

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `receivedAt` | string(date-time) | Y | N | 이번 신고 receipt의 접수 시각 |
| `alreadyReceived` | boolean | Y | N | 같은 신고자·피신고자 조합의 보존 중인 기존 접수면 `true`. 보존 규칙은 [MATCH-01 신고와 차단](p2/matching.md#신고와-차단)을 따름 |

### 4.33 MatchPartyMember

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

`ACTIVE` 성공 파티의 `MatchChatHandoff.members` 항목이다. 현재 요청자도 포함하며, 재접속·이벤트 유실 뒤에도 이 목록으로 성공 파티의 공개 프로필을 복구한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `participantRef` | string | Y | N | 해당 Party에서만 의미 있는 opaque participant reference. 사용자 ID가 아님 |
| `nickname` | string | Y | N | 현재 공개 닉네임 |
| `profileImageUrl` | string | Y | Y | 현재 공개 프로필 이미지 URL. 없으면 `null` |
| `isMine` | boolean | Y | N | 현재 요청자의 항목이면 `true` |

### 4.34 AssistantConsentResponse

> **도입 단계: P2** · **기능: AI-01** · **API 계약 상태: 계약 확정** · **제공 상태: AI-01a T1~T5 검증 범위 제공**

외부 provider로 자연어를 전송하기 전에 사용자에게 보여줄 현재 동의·정책 상태다. 동의 원문이나 사용자 입력은 응답에 포함하지 않는다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `status` | AssistantConsentStatus | Y | N | 현재 동의 상태 |
| `provider` | string | Y | N | 승인된 provider. `OPENAI` 고정 |
| `consentVersion` | string | Y | N | 동의문 버전 |
| `policyVersion` | string | Y | N | 확인한 provider 정책 버전 |
| `policyUrl` | string(uri) | Y | N | 확인한 provider 정책 주소 |
| `store` | boolean | Y | N | provider 요청 저장 옵션. 항상 `false` |
| `grantedAt` | string(date-time) | Y | Y | 동의 시각. 동의 전·철회 상태에서는 `null` |
| `revokedAt` | string(date-time) | Y | Y | 철회 시각. 현재 철회 이력이 없으면 `null` |

### 4.35 AssistantConditionSummary

> **도입 단계: P2** · **기능: AI-02** · **API 계약 상태: 계약 확정** · **제공 상태: AI-01a T1~T5 검증 범위 제공**

서버가 provider 결과를 검증·정규화한 조건이다. 모델 원문·prompt·tool 인자는 반환하지 않는다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `categories` | string[] | Y | N | 추천 검색 조건. 고정 카테고리 code 목록. 없으면 `[]` |
| `mechanisms` | string[] | Y | N | 추천 검색 조건. 공개 메커니즘 내부 code 목록. 없으면 `[]` |
| `themes` | string[] | Y | N | 추천 검색 조건. 테마 code 목록. 없으면 `[]` |
| `complexityMax` | number | Y | Y | 추천 검색 조건. `1.00`~`5.00` 난이도 상한. 없으면 `null` |
| `playTimeMax` | GamePlayTimeFilter | Y | Y | 추천 검색 조건. 최대 플레이 시간 구간. 없으면 `null` |
| `gameId` | integer | Y | Y | 방 생성 필드. 서버가 확인한 게임 ID. 후보가 여러 개면 `null` |
| `playerCount` | integer | Y | Y | 방 생성 필드. 서버가 확인한 총 플레이 인원. 주최자를 포함한 2~11명이며 게임 후보의 `min_players`~`max_players` 판정도 같은 기준을 쓴다 |
| `startsAt` | string(date-time) | Y | Y | 방 생성 필드. 요청한 모임 시작 시각 |
| `region` | Region | Y | Y | 방 생성 필드. 서버가 정규화한 지역 |
| `experienceLevel` | ExperienceLevel | Y | Y | 추천에 사용한 경험 수준. 없으면 `null` |

`categories`·`mechanisms`·`themes`·`complexityMax`·`playTimeMax`는 승인된 게임 목록 검색의 같은 code 집합과 값 범위를 그대로 쓴다. 세 배열은 각각 목록 안 `ANY`로 결합하고 서로 다른 조건 종류끼리는 `AND`로 결합한다. AI 경로는 게임 목록 검색의 `mechanismMatch`·`themeMatch`에 해당하는 선택지를 노출하지 않고 항상 `ANY`로 고정하므로, 후보를 좁히는 판단은 결합 모드가 아니라 조건 종류를 늘리는 방식으로만 한다. **provider 기반 일반 추천 경로**는 `categories`·`mechanisms`·`themes` 가운데 하나 이상이 있어야 후보를 조회하며, 하나도 없으면 `GAME_STYLE`만 담은 `NEEDS_INPUT`으로 끝낸다. `complexityMax`·`playTimeMax`는 선택 정제 조건이라 누락으로 요구하지 않는다. 이미 확인된 `gameId`·`playerCount`는 후보 조회의 추가 `AND` 필터로 쓸 수 있지만 `RECOMMEND`의 누락 조건으로 요구하지 않는다.

[ADR-0085](adr/platform/0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md)의 정확 게임명 직접 경로는 위 일반 추천의 예외다. 정규화한 단독 입력이 카탈로그 `Game.name` 하나와 유일하게 일치하면 서버가 그 ID를 `gameId`에 넣고 후보 한 건을 반환한다. 이 경로는 스타일 조건·`RANK-01` 필터를 적용하지 않는다. `conditions`는 서버가 같은 schema로 다시 검증하며, 유일 매치가 아닌 경우에는 `gameId`를 이 경로에서 설정하지 않는다.

### 4.36 AssistantRecommendationResponse

> **도입 단계: P2** · **기능: AI-02** · **API 계약 상태: 계약 확정** · **제공 상태: AI-01a T1~T5와 #951 후보 전용 DTO·정확 게임명 직접 조회 검증 범위 제공**

`RECOMMEND` 흐름의 결과다. 이 응답은 Room·ChatRoom·임시 초안을 만들지 않는다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `state` | AssistantRecommendationState | Y | N | 추가 질문, 추천, 지원하지 않는 요청 |
| `conditions` | AssistantConditionSummary | Y | N | 서버가 검증한 구조화 조건. 미확정 필드는 `null` |
| `missingFields` | AssistantMissingField[] | Y | N | `NEEDS_INPUT`일 때 필요한 필드 집합. 그 밖에는 `[]` |
| `candidates` | AssistantRecommendationCandidate[] | Y | N | 후보 최대 10건. 없으면 `[]` |

후보는 provider가 반환한 게임 식별자를 신뢰하지 않고 서버가 `game.contract`로 다시 조회한다. 공개 `RANK-01` 상위 결과나 `DISCOVERY-01`의 `SEARCH-04` tool을 사용하지 않는다. provider 기반 일반 추천의 후보는 AND 필터와 내부 `RANK-01` 정렬 뒤 상위 10건으로 절단하며, 동점은 게임 ID 오름차순으로 끊는다. [ADR-0085](adr/platform/0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md)의 유일한 정확 게임명 직접 경로는 위 정렬을 거치지 않고 일치한 후보 1건만 반환한다. 절단 사실을 알리는 총 개수 필드나 pagination은 제공하지 않는다.

### 4.36.1 AssistantRecommendationCandidate

`AI-02` 추천 카드 전용 projection이다. `GameSummary`는 Room 등 다른 응답의 공유 DTO이므로 이 화면 요구로 확장하지 않는다. 상세 화면 이동은 이 DTO가 아니라 기존 `#/game/:id` route가 소유한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `id` | integer | Y | N | 내부 게임 ID. 상세 route와 `GAME_FOCUSED` 초안의 `gameId`에 사용 |
| `name` | string | Y | N | 카탈로그 정식 게임명 |
| `imageUrl` | string | Y | Y | 대표 이미지 URL. `null`이면 클라이언트가 이미지 없이 카드 fallback을 표시 |
| `description` | string | Y | N | `GET /api/games/{gameId}`의 간단 설명과 같은 공개 값. 상세 설명·BGG 원문은 포함하지 않음 |

### 4.37 AssistantDraftResponse

> **도입 단계: P2** · **기능: AI-03** · **API 계약 상태: 계약 확정** · **제공 상태: AI-03a T1~T6 검증 범위 제공**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `draftId` | integer | Y | N | 서버 임시 초안 ID |
| `draftVersion` | integer | Y | N | 수정·확인 동시성을 판정하는 버전 |
| `status` | AssistantDraftStatus | Y | N | 현재 초안 상태 |
| `input` | AssistantRoomDraftInput | Y | N | 현재 서버 저장 초안. `place`는 확인 전 `null`일 수 있음 |
| `result` | AssistantRoomCreationResult | Y | Y | `CONFIRMED`일 때만 Room·ChatRoom 결과. 그 밖에는 `null` |

응답에는 만료 시각이나 남은 시간을 포함하지 않는다. 활성 초안 조회는 유효한 `ACTIVE` 초안만 이 응답으로 반환하며, 만료 초안은 요청 시작 시각에 판정해 `410 ASSISTANT_DRAFT_EXPIRED`로 처리한다.

### 4.38 AssistantRoomDraftInput

> **도입 단계: P2** · **기능: AI-03** · **API 계약 상태: 계약 확정** · **제공 상태: AI-03a T1~T6 검증 범위 제공**

확인형 Room command에 전달할 서버 검증 입력이다. `POST /api/rooms`의 기존 요청과 같은 Room 불변식을 사용하지만, AI 초안에는 `region`이 포함되고 확인 전 `place`가 비어 있을 수 있다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomType` | RoomType | Y | N | `GAME_FOCUSED` 또는 `PERSON_FOCUSED` |
| `title` | string | Y | N | 앞뒤 공백 제거 후 1~100자 |
| `description` | string | N | Y | 최대 255자 |
| `gameId` | integer | N | Y | `GAME_FOCUSED`면 존재하는 양의 정수 |
| `experienceLevel` | ExperienceLevel | Y | N | 기존 Room 생성 규칙과 동일 |
| `isRulemasterLed` | boolean | Y | N | 룰마스터 진행 자기신고 |
| `startsAt` | string(date-time) | Y | N | 미래 시각, 오프셋 필수 |
| `region` | Region | Y | N | 요청 누락 시 `홍대`로 정규화 |
| `place` | string | Y | Y | 확인 전 `null` 허용. 확인 시 1~100자 필수 |
| `recruitmentCapacity` | integer | Y | N | 개설자 제외 1~10명. AI 초안은 `AssistantConditionSummary.playerCount`를 `recruitmentCapacity = playerCount - 1`로 변환해 채운다 |

### 4.39 AssistantRoomCreationResult

> **도입 단계: P2** · **기능: AI-03** · **API 계약 상태: 계약 확정** · **제공 상태: AI-03a T1~T6 검증 범위 제공**

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 확인형 command로 생성된 Room ID |
| `chatRoomId` | integer | Y | N | 같은 트랜잭션에서 생성된 ChatRoom ID |

`room.contract` 확인형 command는 식별자만 반환하고 Room 상세 HTTP DTO를 반환하지 않는다. 상세가 필요한 화면은 `roomId`로 기존 `GET /api/rooms/{roomId}`를 호출한다.

### 4.40 ChatRoomUpdatedEvent

> **도입 단계: P2** · **기능: CHAT-08** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 백엔드 제공(`#918`), 프런트엔드 소비 구현 중(`#919`)**

`GET /api/users/me/chat/ws`로 Upgrade한 사용자 단위 WebSocket이 보내는 서버 발신 텍스트 이벤트다. [ChatMessageEvent](#417-chatmessageevent)와 달리 메시지 본문이나 시스템 메시지 조립 결과를 담지 않는 최소 신호다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 새 메시지가 커밋된 방 ID |
| `messageId` | integer | Y | N | 커밋된 메시지의 `messageId`. 클라이언트는 이 값을 화면에 직접 반영하지 않는다 |

클라이언트는 이 이벤트를 수신하면 [CHAT-07 채팅 목록 마지막 메시지·방별 미읽음 상태 계약](#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태-계약)의 배치 조회로 해당 방의 최신 값을 다시 가져온 뒤 화면을 갱신한다. 이 이벤트는 표시할 데이터의 정본이 아니며, 유실·중복·순서 역전이 있어도 재조회 결과가 항상 최종값이다.

## 5. 인증·프로필 API

### 인증 요청 남용 제한

회원가입·로그인 요청에는 아래 요청 한도를 적용한다. 아래 표와 오류는 현재 구현의 공개 계약이다. 비밀번호 저장·인증 요청 제한의 승인 기준과 내부 적용 순서는 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)을 따른다.

| 대상 | 제한 키 | 허용량 |
|---|---|---|
| 회원가입 | 원격 IP | 10분 이동 창당 5회. 사전 검증을 통과한 요청은 성공·실패 모두 계산 |
| 로그인 | 원격 IP | 10분 이동 창당 30회. 사전 검증을 통과한 요청은 성공·실패 모두 계산 |
| 로그인 실패 | 정규화 이메일 + 원격 IP | 10분 이동 창당 5회. 로그인 성공 시 실패 횟수 초기화 |

다음 경우 `429 RATE_LIMIT_EXCEEDED`를 반환한다.

- 요청 횟수 제한 초과
- 동일한 정규화 이메일·원격 IP 조합의 로그인 검증 진행 중
- 동시 해시 실행 슬롯 부족

`Retry-After`에는 다시 요청할 수 있을 때까지의 초를 담는다. 실패 한도 초과 시 가장 오래된 실패가 이동 창을 벗어날 때까지 남은 초, 동일 키 검증 진행 중이나 슬롯 부족 시 `1`이다. 클라이언트는 이 값에 따라 재시도한다.

`max-ip-keys`(기본 10000)는 같은 원격 IP의 signup·login 물리 bucket을 합쳐 논리 IP 한 개로, `max-failure-keys`(기본 10000)는 같은 정규화 이메일·원격 IP의 실패 bucket과 로그인 gate를 합쳐 논리 주체 한 개로 센다. 만료된 상태만 등록부에서 회수하며 유효 상태를 축출하지 않는다. 상한이 찬 상태에서는 기존 논리 주체를 위 표의 429 규칙으로 계속 평가하고, 새 논리 주체만 사용자 조회·생성·비밀번호 해시 전에 `503 SERVICE_UNAVAILABLE`로 거절한다. 이 503에는 `Retry-After`를 포함하지 않는다.

`local`과 `production`의 인증 요청 제한 상태는 모든 애플리케이션 인스턴스가 같은 Redis namespace에서 Lua 원자 연산으로 확인·기록한다. Redis 서버 시각으로 등록부의 만료 항목을 회수하고, 기존 member 확인·신규 상한 판정·물리 상태 처리·등록부 TTL score 갱신을 함께 수행한다. Redis 연결·명령 또는 원자 연산 결과를 확인할 수 없으면 회원가입·로그인은 사용자 조회·생성이나 비밀번호 해시 전에 `503 SERVICE_UNAVAILABLE`을 반환하며, 인메모리 fallback과 `Retry-After` 헤더는 사용하지 않는다. `test`와 `postgresTest`는 격리된 인메모리 구현을 사용한다.

- 존재하지 않는 이메일과 잘못된 비밀번호는 계정 유무를 구분하지 않고 같은 `INVALID_CREDENTIALS`로 응답한다.
- 원격 IP를 바꿔 가며 같은 계정을 노리는 분산 추측은 현재 제한 범위 밖이다. 수용한 위험과 재검토 조건은 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)을 따른다.

### AUTH-01 CSRF 토큰 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/auth/csrf` |
| 인증 / CSRF | 불필요 / 불필요 |
| 성공 | `200 OK`, `data`: `CsrfTokenResponse` |

Path variable·query parameter·body는 없다. 비로그인 호출을 허용하며 서버 세션 없이 쿠키 기반 CSRF 토큰을 반환한다.

응답 헤더:

~~~http
Set-Cookie: XSRF-TOKEN={token}; Path=/; HttpOnly; SameSite=Lax
~~~

비로그인 요청에도 `JSESSIONID`는 발급하지 않는다.

**오류:** 계약된 오류가 없다.

### AUTH-02 회원가입

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/auth/signup` |
| 인증 / CSRF | 불필요 / 필요 |
| 성공 | `201 Created`, `data`: `UserSummary` |

계정만 생성하며 로그인 세션은 만들지 않는다.

#### Request Body — SignupRequest

| 필드 | 타입 | 필수 | nullable | 정규화·검증 |
|---|---|:---:|:---:|---|
| `email` | string | Y | N | 앞뒤 공백 제거 후 소문자로 변환. 이메일 형식이어야 하며 정규화 뒤 255자 이하. 중복도 정규화된 값으로 판정 |
| `password` | string | Y | N | Unicode code point 15개 이상 64개 이하이면서 UTF-8 인코딩 결과 72바이트 이하. Unicode와 공백을 허용하며 원문을 변경하지 않음 |
| `nickname` | string | Y | N | 앞뒤 공백 제거 후 1~50자, 제어문자 금지 |

- 비밀번호는 Unicode와 공백을 허용한다. 길이는 UTF-16 code unit이나 grapheme cluster가 아니라 Unicode code point로 계산한다.
- 앞뒤 공백 제거·Unicode 정규화·자동 잘라내기를 하지 않는다.
- UTF-8 인코딩 결과가 72바이트를 넘는 비밀번호는 `VALIDATION_ERROR`로 거절한다.
- 비밀번호 원문은 응답에 포함하지 않는다. 저장 방식은 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)을 따른다.

> [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)의 회원가입 비밀번호 15~64 Unicode code point, UTF-8 72바이트 이하와 공백·Unicode 원문 보존 계약을 적용한다. 다중 인스턴스 확장 전 공유 제한 저장소나 게이트웨이를 별도로 결정해야 하는 항목은 현재 제한기가 인스턴스별 메모리에 상태를 저장하므로 남아 있다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 요청값 검증 실패 | 400 | `VALIDATION_ERROR` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 이메일 중복 | 409 | `EMAIL_ALREADY_EXISTS` |
| 요청 남용 제한 초과 | 429 | `RATE_LIMIT_EXCEEDED` |

### AUTH-03 로그인

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/auth/login` |
| 인증 / CSRF | 불필요 / 필요 |
| 성공 | `200 OK`, `data`: `UserSummary` |

성공 시 세션 ID를 교체하고 새 `JSESSIONID`를 설정한다.

#### Request Body

| 필드 | 타입 | 필수 | nullable | 정규화·검증 |
|---|---|:---:|:---:|---|
| `email` | string | Y | N | 회원가입과 같은 방식으로 정규화(앞뒤 공백 제거 후 소문자) |
| `password` | string | Y | N | Unicode code point 1개 이상 64개 이하이면서 UTF-8 72바이트 이하. 공백 포함 원문을 변경·잘라내지 않음 |

- 필드 누락·`null`·빈 문자열·길이·바이트 한도 초과는 `VALIDATION_ERROR`다.
- 필드 형식은 유효하지만 자격증명이 일치하지 않으면 `INVALID_CREDENTIALS`다. 존재하지 않는 이메일과 잘못된 비밀번호는 계정 유무를 구분하지 않고 동일하게 `INVALID_CREDENTIALS`로 응답한다(→ [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)).
- `password_hash`가 없는 소셜 전용 사용자의 저장된 제공자 이메일도 자격증명 미존재로 처리한다. 존재하지 않는 이메일과 같은 더미 bcrypt·요청 제한 경로를 거쳐 `401 INVALID_CREDENTIALS`를 반환하며 `500`이나 소셜 계정 존재 여부를 노출하지 않는다.

응답 헤더:

~~~http
Set-Cookie: JSESSIONID={newSessionId}; Path=/; HttpOnly; SameSite=Lax
~~~

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 요청값 검증 실패 | 400 | `VALIDATION_ERROR` |
| 이메일 또는 비밀번호 불일치 | 401 | `INVALID_CREDENTIALS` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 요청 남용 제한 초과 | 429 | `RATE_LIMIT_EXCEEDED` |

### AUTH-03 로그아웃

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/auth/logout` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `{}` |

Request body는 없다. 성공 시 서버 세션과 인증 상태를 무효화하고 `JSESSIONID`를 만료시킨다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

### AUTH-04 내 프로필 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/users/me` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `UserSummary` |

Path variable·query parameter·body는 없다. 본인만 조회한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |

### AUTH-04 내 프로필 수정

| 항목 | 값 |
|---|---|
| Method / Path | `PATCH /api/users/me` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `UserSummary` |

P0에서는 닉네임만 수정한다.

#### Request Body — ProfileUpdateRequest

| 필드 | 타입 | 필수 | nullable | 정규화·검증 |
|---|---|:---:|:---:|---|
| `nickname` | string | Y | N | 앞뒤 공백 제거 후 1~50자, 제어문자 금지 |

빈 객체, 빈 문자열과 검증 실패는 `VALIDATION_ERROR`다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 요청값 검증 실패 | 400 | `VALIDATION_ERROR` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

### AUTH-04 프로필 이미지 업로드

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/users/me/profile-image` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `UserSummary` |

multipart/form-data 형식으로 `file` 파라미터에 이미지를 전송한다. 성공 시 변경된 프로필 정보(새 `profileImageUrl` 포함)를 반환한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 파일 누락, 크기 초과(5MB), 지원하지 않는 형식 | 400 | `VALIDATION_ERROR` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

### AUTH-04 프로필 이미지 삭제

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/users/me/profile-image` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `UserSummary` |

현재 설정된 프로필 이미지를 삭제하고 `profileImageUrl`을 `null`로 만든다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

### AUTH-05 소셜 로그인·계정 연결

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `AUTH-05`](archive/p1/README.md#기능별-종료-상태)

이 절은 #328에서 승인된 계약이다. 제품 규칙은 [P1 소셜 로그인 명세](archive/p1/social-login.md), 외부 식별자·세션 결정은 [ADR-0042](adr/auth/0042-p1-oauth-social-identity-and-session-integration.md)를 따른다. 경로의 `{provider}`는 [SocialProvider](#socialprovider)의 소문자 경로값만 허용한다.

#### 설정된 소셜 제공자 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/auth/social/providers` |
| 인증 / CSRF | 선택 / 불필요 |
| 성공 | `200 OK`, `data`: `SocialProviderItem[]` |

Client ID와 Client Secret이 모두 설정된 제공자만 `GOOGLE`, `NAVER`, `KAKAO` 순서로 반환한다. 설정된 제공자가 없으면 빈 배열이다. 유효한 로그인 세션이 있으면 각 제공자의 현재 연결 여부를 `linked`로 반환하고, 비로그인은 모두 `false`다. query parameter와 body는 없다.

**오류:** 계약된 오류가 없다.

#### 소셜 로그인 authorization 시작

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/auth/social/authorization/{provider}` |
| 인증 / CSRF | 불필요 / 불필요 |
| 성공 | `302 Found`, 제공자의 authorization endpoint로 리다이렉트 |

Spring Security filter가 OAuth2 Authorization Code 요청과 추측하기 어려운 일회성 `state`를 만들고 서버 세션에 authorization request만 임시 저장한다. 공통 JSON envelope를 사용하지 않는다. 지원하지 않거나 설정되지 않은 제공자는 외부로 보내지 않고 `/?socialAuth=provider-unavailable#/auth`로 리다이렉트한다.

#### 소셜 callback과 고정 결과

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/auth/social/callback/{provider}` |
| 인증 / CSRF | 불필요 / 불필요 |
| 결과 | `302 Found`, same-site 프론트엔드의 고정 결과로 리다이렉트 |

제공자가 보내는 `code`, `state`, `error` 외의 사용자 입력을 응답 URL에 복사하지 않는다. `code`, token, provider 오류 설명과 사용자 속성은 리다이렉트·본문·로그에 넣지 않는다. 허용 결과는 다음뿐이다.

| `socialAuth` | 의미 | 기본 화면 |
|---|---|---|
| `login-success` | 신규·기존 소셜 로그인 성공 | `#/home` |
| `link-success` | 로그인 사용자의 명시적 연결 성공 | `#/profile` |
| `link-required` | 비로그인 첫 로그인에서 처음 보는 외부 신원의 신뢰 가능한 이메일과 같은 기존 사용자가 있어 자동 병합하지 않음 | `#/auth` |
| `link-conflict` | 외부 계정 또는 같은 제공자의 다른 계정이 이미 연결됨 | 로그인 연결 시도는 `#/profile`, 그 외 `#/auth` |
| `canceled` | 사용자가 제공자 동의를 취소함 | 시도 모드에 따라 `#/auth` 또는 `#/profile` |
| `invalid-state` | `state` 누락·불일치·재사용 | 시도 모드에 따라 `#/auth` 또는 `#/profile` |
| `provider-unavailable` | 지원하지 않거나 현재 설정되지 않은 제공자 | `#/auth` |
| `failed` | 필수 subject 누락 또는 처리 실패 | 시도 모드에 따라 `#/auth` 또는 `#/profile` |

로그인 성공은 `/?socialAuth=login-success#/home`, 연결 성공은 `/?socialAuth=link-success#/profile`처럼 query 뒤에 hash route를 붙인다. 로그인 성공은 새 `CurrentUserPrincipal`을 저장하고, 연결 성공은 기존 사용자를 유지한다. 두 성공 모두 세션 ID를 교체하고 기존 CSRF 토큰을 무효화한다. 로그인 실패·취소는 사용자를 만들거나 인증하지 않고, 연결 실패·취소는 기존 로그인 상태를 복구·유지하며 일회성 연결 의도를 폐기한다.

`link-required`는 비로그인 첫 로그인에서만 반환한다. 인증된 명시적 연결 callback은 제공자 이메일의 일치·중복과 무관하게 현재 세션 사용자를 연결 대상으로 유지하며, 외부 식별자 또는 사용자·제공자 유일 제약이 충돌할 때만 `link-conflict`로 돌아간다. 제공자별 이메일 신뢰 조건과 `null` 매핑은 [P1 소셜 로그인 명세](archive/p1/social-login.md#제공자-이메일-매핑)를 따른다.

#### 소셜 계정 연결 시작

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/users/me/social-accounts/{provider}/link` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `SocialAuthorizationResponse` |

request body와 query parameter는 없다. 현재 사용자와 제공자를 일회성 연결 의도로 서버 세션에 저장하고 same-site `authorizationUri`를 반환한다. 클라이언트는 성공 응답 뒤 해당 URI로 전체 페이지 이동한다. 이미 같은 제공자 연결이 있으면 기존 연결을 교체하지 않는다.

#### 연결 시작 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 지원하지 않거나 설정되지 않은 제공자 | 404 | `SOCIAL_PROVIDER_NOT_AVAILABLE` |
| 사용자가 같은 제공자 계정을 이미 연결함 | 409 | `SOCIAL_ACCOUNT_ALREADY_LINKED` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

## 6. 게임 API

게임 데이터는 운영자가 준비한다. 사용자용 게임 생성·수정·삭제 API는 제공하지 않는다(→ [GAME-01 P0 완료 기록](archive/p0/game-catalog.md#game-01-게임-목록검색), 게임 목록 출처 [ADR-0015](adr/game/0015-bgg-baseline-team-collected-game-list.md)).

### GAME-01 게임 목록·검색

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/games` |
| 인증 / CSRF | 선택 / 불필요. 유효한 `playedFilter` 사용 시 인증 필요 |
| 성공 | `200 OK`, `data`: `GameListSliceResponse<GameListItem>` |

#### Query Parameters

> 아래 표의 파라미터는 모두 현재 `제공` 상태다. 이후 목표 항목을 추가하면 행의 `제공 상태`로 현재 사용 가능 여부를 구분한다.

| 이름 | 타입 | 필수 | 기본값 | 도입 단계 | 제공 상태 | 의미 |
|---|---|:---:|---|:---:|:---:|---|
| `keyword` | string | N | 검색 없음 | P0 | 제공 | 게임명 부분 일치 |
| `upcomingOnly` | boolean | N | `false` | P0 | 제공 | `true`이면 예정 모임이 한 개 이상인 게임만 반환 |
| `playerCount` | integer | N | 검색 없음 | P1 | 제공 | `1`~`9`는 해당 인원을 포함하는 게임, `10`은 최대 가능 인원이 10 이상인 게임 |
| `playerCountMin` | integer | N | 검색 없음 | P1 | 제공 | 1 이상, 찾는 인원 범위의 최소값 |
| `playerCountMax` | integer | N | 검색 없음 | P1 | 제공 | 1 이상, 찾는 인원 범위의 최대값 |
| `playerCountExact` | boolean | N | `false` | P1 | 제공 | `true`이면 전달한 인원 경계를 정확히 일치시킨다 |
| `exclusivePlayerCount` | integer | N | 검색 없음 | P1 | 제공 | 반복 전달 가능한 전용 인원. 허용값은 `1`, `2`. 목록 안 OR |
| `playTime` | GamePlayTimeFilter | N | 검색 없음 | P1 | 제공 | 반복 전달 가능한 검증된 최대 플레이 시간 구간. 목록 안 OR |
| `youngestPlayerAge` | integer | N | 검색 없음 | P1 | 제공 | 1 이상. 최연소 참여자의 나이가 게임의 권장 최소 연령 이상인 게임만 반환 |
| `complexityMin` | number | N | 검색 없음 | P1 | 제공 | `1.00`~`5.00`, 난이도 닫힌 구간의 하한 |
| `complexityMax` | number | N | 검색 없음 | P1 | 제공 | `1.00`~`5.00`, 난이도 닫힌 구간의 상한 |
| `playedFilter` | PlayedFilter | N | 검색 없음 | P1 | 제공 | 단일 값. `PLAYED_ONLY` 또는 `EXCLUDE_PLAYED`; 사용 시 로그인 필요 |
| `mechanism` | string | N | 검색 없음 | P1 | 제공 | 반복 전달 가능한 공개 메커니즘 내부 코드. mechanismMatch에 따라 ANY 또는 ALL |
| `mechanismMatch` | MechanismMatch | N | `ANY` | P1 | 제공 | 단일 값. `ANY` 또는 `ALL`; mechanism이 없어도 유효 |
| `category` | string | N | 검색 없음 | P1 | 제공 | 반복 전달 가능한 고정 카테고리 code. 목록 안 OR |
| `theme` | string | N | 검색 없음 | P1 | 제공 | 반복 전달 가능한 테마 code. themeMatch에 따라 ANY 또는 ALL |
| `themeMatch` | ThemeMatch | N | `ANY` | P1 | 제공 | 단일 값. `ANY` 또는 `ALL`; theme이 없어도 유효 |
| `recommendedPlayerCount` | integer | N | 검색 없음 | P1 | 제공 | 반복 전달 가능한 양의 추천 인원. 목록 안 OR |
| `bestPlayerCount` | integer | N | 검색 없음 | P1 | 제공 | 반복 전달 가능한 양의 베스트 인원. 목록 안 OR |
| `page` | integer | N | `0` | P0 | 제공 | 페이지 번호 |
| `size` | integer | N | `10` | P0 | 제공 | 페이지 크기, 1~100 |

- 서로 다른 필터 종류는 AND로 결합한다. 반복 전달을 허용한 필터는 같은 값을 반복해도 한 번 전달한 것과 결과가 같다. `playedFilter`는 단일 값이므로 같은 값도 중복 전달하면 검증 오류다.
- `playerCount=10`은 정확히 10명만 뜻하지 않고 최대 가능 인원이 10 이상인 게임을 뜻한다.
- 인원 조건은 범위 계열(`playerCountMin`, `playerCountMax`, `playerCountExact`)과 전용 인원(`exclusivePlayerCount`)으로 나뉜다. `playerCountMin`이나 `playerCountMax`를 `exclusivePlayerCount`와 함께 전달하면 검증 오류다.
- `playerCountExact`를 생략하거나 `false`로 두면 범위 판정은 다음과 같다. 게임이 요청 범위 전체를 지원해야 한다.

| 전달한 값 | 판정 |
|---|---|
| 최소·최대 모두 | `min_players <= playerCountMin AND max_players >= playerCountMax` |
| 최소만 | `max_players >= playerCountMin` |
| 최대만 | `min_players <= playerCountMax` |

- `playerCountExact=true`는 전달한 경계를 정확히 맞춘다. 최소·최대 모두 전달하면 `min_players = playerCountMin AND max_players = playerCountMax`, 최소만 전달하면 `min_players = playerCountMin`, 최대만 전달하면 `max_players = playerCountMax`다. 맞출 경계가 없으면, 즉 최소·최대를 모두 생략하면 인원 조건을 적용하지 않는다.
- `exclusivePlayerCount=1`은 `min_players = max_players = 1`, `exclusivePlayerCount=2`는 `min_players = max_players = 2`다. 두 값을 함께 전달하면 OR로 결합하고 결과에 같은 게임을 중복해 담지 않는다.
- `playTime`은 검증된 `max_play_time_minutes` 한 값으로 아래 6구간을 판정하며, 경계값은 정확히 한 구간에만 속한다. 여러 값을 전달하면 OR로 결합한다.

| 값 | 판정 |
|---|---|
| `UP_TO_10` | `<= 10` |
| `OVER_10_TO_20` | `> 10 AND <= 20` |
| `OVER_20_TO_30` | `> 20 AND <= 30` |
| `OVER_30_TO_60` | `> 30 AND <= 60` |
| `OVER_60_UNDER_90` | `> 60 AND < 90` |
| `AT_LEAST_90` | `>= 90` |

- `youngestPlayerAge`는 양의 정수로 전달한다. 게임의 `min_age <= youngestPlayerAge`일 때 포함하며 `min_age`가 `NULL`인 게임은 이 필터를 적용할 때 제외한다. 생략하면 권장 최소 연령이 없다는 이유로 게임을 제외하지 않는다.
- 이전 `playTime` 값 `SHORT`, `MEDIUM`, `LONG`은 제거했다. 단독으로 전달하거나 새 값과 섞어 전달하면 검증 오류이며 조용히 무시하지 않는다.
- 복잡도는 전달한 하한 이상·상한 이하의 닫힌 구간으로 판정한다. 두 값을 함께 전달할 때 하한이 상한보다 크면 검증 오류다.
- `PLAYED_ONLY`는 현재 사용자의 표시 관계가 있는 게임만, `EXCLUDE_PLAYED`는 그 관계가 없는 게임만 반환한다. 관계가 없다는 사실을 실제 미플레이로 해석하지 않는다.
- `playedFilter`를 생략하면 관계 필터를 적용하지 않는다. 잘못된 값이나 중복 전달은 로그인 여부와 관계없이 먼저 `400 VALIDATION_ERROR`, 유효한 값을 비로그인으로 전달하면 `401 UNAUTHENTICATED`다.
- `mechanism`은 [GAME-03](#game-03-게임-메커니즘-선택지-조회)의 공개 `code`를 정확히 전달한다. `mechanismMatch=ANY`는 하나 이상, `mechanismMatch=ALL`은 모든 고유 code 관계를 요구한다. 같은 코드를 반복해도 결과를 중복하지 않는다.
- 존재하지 않거나 비공개인 메커니즘 코드는 전체 요청을 `VALIDATION_ERROR`로 거절한다. 일부 유효 코드가 함께 있어도 잘못된 코드를 조용히 무시하지 않는다.
- `category`는 [GAME-04](#game-04-게임-카테고리-선택지-조회)의 code를 반복 전달하고 같은 목록 안에서 OR다. `theme`은 [GAME-05](#game-05-게임-테마-선택지-조회)의 code를 반복 전달하며, `themeMatch=ANY`는 하나 이상, `themeMatch=ALL`은 모든 고유 code 관계를 요구한다.
- `recommendedPlayerCount`와 `bestPlayerCount`는 각각 BGG 투표에서 정규화한 양의 인원을 반복 전달하며 같은 목록 안에서 OR다. 가능 인원과 다른 의미이며 `4+` 결과는 해당 게임의 검증된 최대 가능 인원까지 확장된 관계로 판정한다.
- `themeMatch`와 `mechanismMatch`는 각각 생략하면 `ANY`이고 대응하는 선택 코드 없이 보내도 유효하다. 두 모드는 독립적이며 테마·메커니즘 그룹과 다른 필터 종류 사이는 `AND`로 결합한다. 중복되거나 잘못된 match 값, 존재하지 않는 category/theme code, 0 이하 인원은 일부 유효 값이 함께 있어도 전체 요청을 `VALIDATION_ERROR`로 거절한다.
- 인원·시간·최연소 참여자 나이·복잡도·카테고리·테마·추천/베스트·메커니즘 필터를 적용하면 해당 조건을 판정할 검증값이나 관계가 없는 게임은 제외한다. 필터를 생략하면 누락값이나 관계 부재만으로 제외하지 않는다.
- 모든 필터를 적용한 뒤 `popularity_score DESC, name ASC, id ASC` 고정 정렬과 페이지를 계산한다. 다음 항목 존재 여부는 size+1 Slice 조회의 `hasNext`로 반환하며 전체 건수는 계산하거나 노출하지 않는다. `popularity_score`는 응답에 노출하지 않는 저장 파생값이다.

#### GameListSliceResponse

| 필드 | 타입 | 설명 |
|---|---|---|
| `content` | GameListItem[] | 현재 페이지 항목. 결과가 없으면 `[]` |
| `page` | integer | 0부터 시작하는 현재 페이지 번호 |
| `size` | integer | 적용된 페이지 크기 |
| `hasNext` | boolean | 다음 페이지 존재 여부 |

`totalElements`와 `totalPages`는 게임 목록 응답에 포함하지 않는다. 이 게임 전용 계약은 공통 `PageResponse`와 비게임 목록 API를 변경하지 않는다.

`tag` 필터와 클라이언트 지정 `sort`는 지원하지 않는다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| query parameter 검증 실패 | 400 | `VALIDATION_ERROR` |
| 유효한 `playedFilter`를 인증 없이 사용 | 401 | `UNAUTHENTICATED` |
| 존재하지 않거나 비공개인 `mechanism` 코드 | 400 | `VALIDATION_ERROR` |
| 존재하지 않는 `category` 또는 `theme` code, 중복·잘못된 `themeMatch` 또는 `mechanismMatch`, 0 이하 추천·베스트 인원 | 400 | `VALIDATION_ERROR` |

### GAME-02 게임 상세 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/games/{gameId}` |
| 인증 / CSRF | 선택 / 불필요 |
| 성공 | `200 OK`, `data`: `GameDetail` |

유효한 세션이 있으면 `playedByMe`는 본인 표시 관계에 따라 `true` 또는 `false`, 비로그인이면 `null`이다. 어느 경우에도 다른 사용자의 관계를 공개하지 않는다.

#### Metadata Fields

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `categories` | GameCategorySummary[] | N | displayOrder ASC의 `code`, `nameKo`, `nameEn` 배열 |
| `themes` | GameThemeSummary[] | N | `nameKo ASC, code ASC`의 `code`, `nameKo`, `nameEn` 배열 |
| `mechanisms` | GameMechanismSummary[] | N | 연결된 공개 메커니즘의 `nameKo ASC, code ASC` `code`, `nameKo`, `nameEn` 배열. 관계가 없으면 빈 배열 |
| `recommendedPlayerCounts` | integer[] | N | 오름차순 추천 인원. 관계가 없으면 빈 배열 |
| `bestPlayerCounts` | integer[] | N | 오름차순 베스트 인원. 관계가 없으면 빈 배열 |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `gameId` | integer | Y | 1 이상의 알밤메이트 내부 게임 ID |

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 게임이 없음 | 404 | `GAME_NOT_FOUND` |

### SEARCH-03 해 본 게임 표시

| 항목 | 값 |
|---|---|
| Method / Path | `PUT /api/users/me/played-games/{gameId}` |
| 인증 / CSRF | 필요 / 필요 |
| Request Body | 없음 |
| 성공 | `200 OK`, `data`: `PlayedGameStateResponse` (`playedByMe=true`) |

존재하는 게임을 본인의 해 본 게임으로 표시한다. 관계가 이미 있어도 새 행을 만들지 않고 같은 `200 OK` 응답을 반환한다. `created_at`은 최초로 관계가 생성된 표시 시각이며 응답에 노출하지 않는다.

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `gameId` | integer | Y | 1 이상의 알밤메이트 내부 게임 ID |

#### 오류 판정 순서

1. 세션이 없거나 유효하지 않으면 `401 UNAUTHENTICATED`
2. CSRF 토큰이 없거나 유효하지 않으면 `403 CSRF_TOKEN_INVALID`
3. path ID 형식·범위가 잘못되면 `400 VALIDATION_ERROR`
4. 게임이 없으면 `404 GAME_NOT_FOUND`

### SEARCH-03 해 본 게임 표시 취소

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/users/me/played-games/{gameId}` |
| 인증 / CSRF | 필요 / 필요 |
| Request Body | 없음 |
| 성공 | `200 OK`, `data`: `PlayedGameStateResponse` (`playedByMe=false`) |

본인의 표시 관계를 삭제한다. 관계가 없어도 같은 `200 OK` 응답을 반환한다. 게임 존재 여부는 확인하므로 없는 게임 ID는 멱등 성공이 아니라 `GAME_NOT_FOUND`다.

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `gameId` | integer | Y | 1 이상의 알밤메이트 내부 게임 ID |

#### 오류 판정 순서

1. 세션이 없거나 유효하지 않으면 `401 UNAUTHENTICATED`
2. CSRF 토큰이 없거나 유효하지 않으면 `403 CSRF_TOKEN_INVALID`
3. path ID 형식·범위가 잘못되면 `400 VALIDATION_ERROR`
4. 게임이 없으면 `404 GAME_NOT_FOUND`

별도 `GET /api/users/me/played-games`는 제공하지 않는다. 본인이 표시한 게임 목록은 `GET /api/games?playedFilter=PLAYED_ONLY`로 조회한다.

### GAME-03 게임 메커니즘 선택지 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/game-mechanisms` |
| 인증 / CSRF | 불필요 / 불필요 |
| 성공 | `200 OK`, `data`: `GameMechanismOption[]` |

검수 후 공개된 항목만 반환한다. `featuredOrder`가 있는 대표 8개를 오름차순으로 먼저 반환하고, 나머지는 `nameKo ASC, code ASC`로 반환한다. 데이터베이스 내부 ID, BGG ID, 검수자·검수일과 출처 기록은 응답에 노출하지 않는다.

#### GameMechanismOption

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `code` | string | N | 표시명과 분리된 안정적인 내부 코드 |
| `nameKo` | string | N | 검수된 한국어 표시명 |
| `nameEn` | string | N | BGG 영문명 |
| `featuredOrder` | integer | Y | 대표 8개는 `1`~`8`, 나머지는 `null` |
| `descriptionKo` | string | N | 검수된 한국어 메커니즘 설명 |

대표 8개는 아래 이름과 순서를 사용한다.

| featuredOrder | nameKo |
|---:|---|
| 1 | 핸드 관리 |
| 2 | 주사위 굴림 |
| 3 | 셋 컬렉션 |
| 4 | 협력 게임 |
| 5 | 타일 놓기 |
| 6 | 조립 보드 |
| 7 | 솔로/솔로테어 게임 |
| 8 | 일꾼 놓기 |

### GAME-04 게임 카테고리 선택지 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/game-categories` |
| 인증 / CSRF | 불필요 / 불필요 |
| 성공 | `200 OK`, `data`: `GameCategoryOption[]` |

고정 8개 카테고리를 `displayOrder ASC`로 반환한다. 내부 ID와 CSV rank 값은 응답에 노출하지 않는다.

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `code` | string | N | 변경하지 않는 내부 category code |
| `nameKo` | string | N | 화면 표시 한글명 |
| `nameEn` | string | N | BGG subdomain의 영문 그룹명 |
| `displayOrder` | integer | N | 화면 고정 노출 순서 1~8 |

### GAME-05 게임 테마 선택지 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/game-themes` |
| 인증 / CSRF | 불필요 / 불필요 |
| 성공 | `200 OK`, `data`: `GameThemeOption[]` |

검수된 한글명과 안정 code가 있는 테마만 `nameKo ASC, code ASC`로 반환한다. 내부 ID와 BGG 원본 ID는 응답에 노출하지 않는다.

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `code` | string | N | 표시명과 분리된 안정적인 내부 theme code |
| `nameKo` | string | N | 검수된 화면 표시 한글명 |
| `nameEn` | string | N | BGG boardgamecategory 영문명 |

### RANK-01 인기 게임 랭킹 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/game-rankings` |
| 인증 / CSRF | 불필요 / 불필요 |
| 성공 | `200 OK`, `data`: `GameRankingResponse` |

밤송이 내부 방만으로 게임별 모임 수를 세어 `전체`와 `지난 7일` 랭킹을 함께 반환한다. 집계 대상은 `roomType`이 `GAME_FOCUSED`이고 `status`가 `CANCELED`가 아닌 방이다. 두 랭킹은 한 요청에서 고정한 같은 기준 시각을 사용한다. 요청 파라미터가 없고 요청자에 따라 결과가 달라지지 않는다. 집계 대상이 없으면 오류가 아니라 빈 배열을 반환한다.

#### GameRankingResponse

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `overall` | GameRankingItem[] | N | 기간 조건 없는 전체 랭킹. 최대 10개 |
| `pastWeek` | GameRankingItem[] | N | 시작 시각이 `[기준 시각 - 7일, 기준 시각)`인 방만 센 랭킹. 최대 10개 |

#### GameRankingItem

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `rank` | integer | N | 같은 랭킹 안의 순위. `1`부터 순서대로 부여하며 집계 수가 같아도 공유하지 않는다 |
| `gameId` | integer | N | 알밤메이트 내부 게임 ID. `/api/games/{gameId}` 조회에 사용한다 |
| `bggId` | integer | N | BoardGameGeek 식별자 |
| `name` | string | N | 게임명 |
| `englishName` | string | N | 영문 게임명 |
| `releaseYear` | integer | Y | 출시 연도 |
| `imageUrl` | string | Y | 대표 이미지 URL |
| `description` | string | N | 게임 한 줄 설명. `GET /api/games/{gameId}`가 반환하는 값과 같다 |
| `roomCount` | integer | N | 집계 대상 방 수. 내림차순 정렬 기준이며 같은 수에서는 `gameId` 오름차순이다 |

방 제목·장소·시각, 주최자·참가자와 사용자 식별 정보는 반환하지 않는다.

## 7. 방 API

### ROOM-01 방 목록 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/rooms` |
| 인증 / CSRF | 선택 / 불필요 |
| 성공 | `200 OK`, `data`: `PageResponse<PublicRoomResponse>` |

공개 목록은 전역 저장 상태 보정 없이 같은 고정 `requestTime`과 snapshot 사실로 유효 상태·필터·content·count·응답을 구성한다. 유효한 세션이 있으면 이 사실로 요청자 기준 `joinable`을 계산하며, 목록은 대상 ROOM 보정 충돌의 `ROOM_CONCURRENT_MODIFICATION`을 반환하지 않는다.

#### Query Parameters

> 아래 표의 파라미터는 모두 현재 `제공` 상태다. 이후 목표 항목을 추가하면 행의 `제공 상태`로 현재 사용 가능 여부를 구분한다.

| 이름 | 타입 | 필수 | 적용 조건 | 도입 단계 | 제공 상태 | 의미 |
|---|---|:---:|---|:---:|:---:|---|
| `type` | RoomType | N | 전달 시 | P0 | 제공 | 방 유형 |
| `status` | RoomStatus | N | 전달 시 | P1 | 제공 | 모집 상태. 공개 목록 범위(`RECRUITING`, `CLOSED`) 밖의 값을 전달하면 빈 결과를 반환한다 |
| `gameId` | integer | N | 전달 시 | P0 | 제공 | 1 이상의 알밤메이트 내부 게임 ID |
| `keyword` | string | N | 전달 시 | P0 | 제공 | 방 제목 부분 일치 |
| `startsAtFrom` | string(date-time) | N | 전달 시 | P1 | 제공 | `startsAt >= startsAtFrom` |
| `startsAtTo` | string(date-time) | N | 전달 시 | P1 | 제공 | `startsAt < startsAtTo` |
| `minRemainingSeats` | integer | N | 전달 시 | P1 | 제공 | 최소 남은 모집 자리, 1~10 |
| `experienceLevels` | ExperienceLevel | N | 전달 시 | P1 | 제공 | 반복 전달 가능한 권장 경험 수준. 목록 안 OR |
| `rulemasterOnly` | boolean | N | `true`일 때 | P1 | 제공 | 룰마스터 진행 방만 반환 |
| `page` | integer | N | 항상 | P0 | 제공 | 기본값 `0` |
| `size` | integer | N | 항상 | P0 | 제공 | 기본값 `10`, 1~100 |

`type`, `status`, `gameId`, `keyword`와 P1 조건은 서로 독립적인 선택 필터이며, 전달된 서로 다른 조건을 모두 만족하는 방을 반환한다. 반복한 `experienceLevels` 안에서만 OR로 결합하고 같은 값의 중복은 한 번 전달한 것과 같다. 모든 필터를 생략하면 두 유형의 공개 방 전체를 반환한다. `keyword`의 빈 문자열과 공백은 검색 조건 없음으로 처리하며, 제목 부분 일치는 대소문자를 구분하지 않는다.

- 날짜 범위는 시작 경계를 포함하고 종료 경계를 제외하는 `[startsAtFrom, startsAtTo)`다. 한쪽 경계만 전달할 수 있으며 두 값을 함께 전달하면 시작 경계가 종료 경계보다 빨라야 한다.
- 남은 모집 자리는 같은 `requestTime`의 유효 상태와 현재 `ACTIVE` 참가 관계를 기준으로 `recruitmentCapacity - activeParticipantCount`를 계산하고 `minRemainingSeats` 이상인 방만 반환한다.
- 경험 수준은 방의 권장 조건을 검색할 뿐 참가 자격 제한으로 바꾸지 않는다.
- `rulemasterOnly=true`일 때만 룰마스터 진행 여부를 조건으로 적용한다. 생략하거나 `false`이면 해당 조건을 적용하지 않는다.
- 공개 목록은 고정된 `requestTime`의 유효 상태를 적용한 뒤 모든 필터를 적용하고 전체 건수, `startsAt ASC, id ASC` 정렬과 페이지를 계산한다.

- 잘못된 enum·날짜·boolean, 역전된 날짜 범위, `gameId` 0 이하, 숫자 범위·바인딩 실패, `page`·`size` 범위 위반 또는 허용하지 않는 parameter는 `VALIDATION_ERROR`다.
- `keyword`는 방 제목 검색이며, P0에서 제외한 조건 필터가 아니다.
- 공개 목록은 `RECRUITING`, `CLOSED` 방만 반환한다.
- `playerCount`, `playTime`, `region`, `experienceLevel`, `tag`, `categoryIds`, `bggWeightMin`, `bggWeightMax`, `sort`는 방 목록 쿼리 파라미터가 아니다. 경험 수준 다중 선택은 `experienceLevels`만 사용한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| query parameter 검증 실패 | 400 | `VALIDATION_ERROR` |

### ROOM-02 방 상세 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/rooms/{roomId}` |
| 인증 / CSRF | 선택 / 불필요 |
| 성공 | `200 OK`, `data`: `PublicRoomResponse` 또는 `ParticipantRoomResponse` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

#### 응답 범위

| 방 상태 | 요청자 | `data` |
|---|---|---|
| `RECRUITING`, `CLOSED` | 비로그인 또는 관계 없는 사용자 | `PublicRoomResponse` |
| `RECRUITING`, `CLOSED` | 주최자 또는 현재 `ACTIVE` 참가자 | `ParticipantRoomResponse` |
| `CANCELED`, `FINISHED` | 주최자 또는 현재 `ACTIVE` 참가자 | `ParticipantRoomResponse` |
| `CANCELED`, `FINISHED` | 그 외 | `404 ROOM_NOT_FOUND` |

응답 헤더:

~~~http
Cache-Control: private, no-store
Vary: Cookie
~~~

- 같은 URL의 응답이 요청자의 세션과 방 관계에 따라 달라지므로 위 헤더를 반환한다. 참가 취소·로그아웃 뒤의 요청은 관계를 다시 판정하며 이전 `ParticipantRoomResponse`를 재사용하지 않는다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 방이 없거나, 최종 상태 방을 조회할 권한이 없음 | 404 | `ROOM_NOT_FOUND` |
| 동시 변경으로 방 상태를 확인할 수 없음 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### ROOM-03 방 생성

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/rooms` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `201 Created`, `data`: `ParticipantRoomResponse` |

#### Request Body — RoomCreateRequest

| 필드 | 타입 | 필수 | nullable | 검증·의미 |
|---|---|:---:|:---:|---|
| `roomType` | RoomType | Y | N | `GAME_FOCUSED` 또는 `PERSON_FOCUSED` |
| `title` | string | Y | N | 앞뒤 공백 제거 후 1~100자, 제어문자 금지 |
| `description` | string | N | Y | 생략·`null` 허용. 문자열이면 최대 255자, 제어문자 금지 |
| `gameId` | integer | 조건부 | Y | `GAME_FOCUSED`는 존재하는 양의 정수 필수. `PERSON_FOCUSED`는 생략·`null`·존재하는 양의 정수 허용 |
| `experienceLevel` | ExperienceLevel | Y | N | 검색 필터·참가 제한으로 사용하지 않음 |
| `isRulemasterLed` | boolean | Y | N | 모든 로그인 사용자가 설정 가능 |
| `startsAt` | string(date-time) | Y | N | 오프셋 필수. 요청 처리 시점보다 미래여야 함 |
| `place` | string | Y | N | 앞뒤 공백 제거 후 1~100자, 제어문자 금지 |
| `recruitmentCapacity` | integer | Y | N | 주최자를 제외한 모집 인원, 1~10 |

- `startsAt`의 누락·`null`·오프셋 없음·형식 오류·현재·과거 시각은 `VALIDATION_ERROR`다.
- `region`은 요청으로 받지 않으며 응답에서는 항상 `홍대`다. `status`도 요청으로 받지 않으며 생성 결과는 `RECRUITING`이다.
- `PERSON_FOCUSED`에 존재하는 `gameId`가 오면 선택 게임으로 저장한다. 태그·카테고리·BGG Weight는 요청으로 받지 않는다.

#### 요청 예시

게임 중심:

~~~json
{
  "roomType": "GAME_FOCUSED",
  "title": "토요일 저녁 스플렌더",
  "description": "처음 오신 분도 환영합니다.",
  "gameId": 1,
  "experienceLevel": "ALL_LEVELS",
  "isRulemasterLed": true,
  "startsAt": "2099-01-01T19:00:00+09:00",
  "place": "홍대입구역 인근 보드게임 카페",
  "recruitmentCapacity": 3
}
~~~

사람 중심:

~~~json
{
  "roomType": "PERSON_FOCUSED",
  "title": "퇴근 후 가볍게 보드게임 할 분",
  "description": "초보자도 환영합니다.",
  "gameId": null,
  "experienceLevel": "BEGINNER_WELCOME",
  "isRulemasterLed": false,
  "startsAt": "2099-01-01T19:00:00+09:00",
  "place": "홍대입구역 인근",
  "recruitmentCapacity": 3
}
~~~

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 요청값 검증 실패 | 400 | `VALIDATION_ERROR` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 선택한 게임이 없음 | 404 | `GAME_NOT_FOUND` |

### ROOM-04 방 수정

| 항목 | 값 |
|---|---|
| Method / Path | `PATCH /api/rooms/{roomId}` |
| 인증 / CSRF | 필요, 주최자 전용 / 필요 |
| 성공 | `200 OK`, `data`: `ParticipantRoomResponse` |

부분 수정이다. 생략한 필드는 기존 값을 유지한다.

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

#### Request Body — RoomUpdateRequest

| 필드 | 타입 | 필수 | nullable | 규칙 |
|---|---|:---:|:---:|---|
| `title` | string | N | N | 요청에 있으면 앞뒤 공백 제거 후 1~100자, 제어문자 금지 |
| `place` | string | N | N | 요청에 있으면 앞뒤 공백 제거 후 1~100자, 제어문자 금지 |
| `description` | string | N | Y | `null`이면 값 삭제, 문자열이면 최대 255자·제어문자 금지 |
| `gameId` | integer | N | Y | `GAME_FOCUSED`는 수정 후에도 존재하는 양의 정수 필수. `PERSON_FOCUSED`는 `null`로 선택 해제 또는 존재하는 양의 정수로 변경 |
| `experienceLevel` | ExperienceLevel | N | N | 생성과 같은 enum 검증 |
| `isRulemasterLed` | boolean | N | N | 요청에 있으면 `null` 불가 |
| `startsAt` | string(date-time) | N | N | 오프셋 필수. 요청 처리 시점보다 미래여야 함 |
| `recruitmentCapacity` | integer | N | N | 요청에 있으면 1~10 |

- `roomType`, `region`, `status`를 포함하면 `VALIDATION_ERROR`다.
- 수정은 주최자만 할 수 있고, 방의 `startsAt`보다 현재 시각이 이르며 `status = RECRUITING`이고 주최자 외 `ACTIVE` 참가자가 없을 때만 허용한다.
- **오류 우선순위:** 주최자 외 `ACTIVE` 참가자가 있으면 `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS`, 그 밖에 상태·시간 조건을 만족하지 않으면 `INVALID_ROOM_STATUS_TRANSITION`을 반환한다.

#### 요청 예시

~~~json
{
  "title": "토요일 저녁 스플렌더 초보방",
  "description": null,
  "recruitmentCapacity": 4
}
~~~

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 요청자가 주최자가 아님 | 403 | `FORBIDDEN` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 요청에 포함된 `gameId`의 게임이 없음 | 404 | `GAME_NOT_FOUND` |
| 요청값 검증 실패 | 400 | `VALIDATION_ERROR` |
| 주최자 외 `ACTIVE` 참가자가 있음 | 409 | `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS` |
| 현재 상태 또는 시간에 수정할 수 없음 | 409 | `INVALID_ROOM_STATUS_TRANSITION` |
| 동시 변경 충돌 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

`GAME_NOT_FOUND`는 요청에 `gameId`를 포함했을 때만 적용한다.

### ROOM-05 방 취소

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/rooms/{roomId}` |
| 인증 / CSRF | 필요, 주최자 전용 / 필요 |
| 성공 | `200 OK`, `data`: `RoomStatusResponse` (`roomStatus = CANCELED`) |

삭제가 아니라 `CANCELED` 상태 변경이다. Request body는 없다.

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 요청자가 주최자가 아님 | 403 | `FORBIDDEN` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 현재 상태에서 취소할 수 없음 | 409 | `INVALID_ROOM_STATUS_TRANSITION` |
| 동시 변경 충돌 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### ROOM-05 방 종료

| 항목 | 값 |
|---|---|
| Method / Path | `PATCH /api/rooms/{roomId}/status` |
| 인증 / CSRF | 필요, 주최자 전용 / 필요 |
| 성공 | `200 OK`, `data`: `RoomStatusResponse` (`roomStatus = FINISHED`) |

주최자의 종료 요청은 상태 정합화 후 방이 이미 `FINISHED`이면 상태와 버전을 다시 변경하지 않고 멱등 성공한다. 정합화 후 `status = CLOSED && now >= startsAt`이면 `FINISHED`로 변경한다. 같은 요청의 시간 기반 정합화가 먼저 `FINISHED`로 변경한 경우에도 성공하며, 그 정합화 결과를 커밋한다.

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

#### Request Body

| 필드 | 타입 | 필수 | nullable | 허용값 |
|---|---|:---:|:---:|---|
| `status` | RoomStatus | Y | N | `FINISHED`만 허용 |

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 요청자가 주최자가 아님 | 403 | `FORBIDDEN` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 요청 `status`가 누락·`null`이거나 `FINISHED`가 아님 | 400 | `VALIDATION_ERROR` |
| 상태 정합화 후 방이 `CANCELED`이거나 `now < startsAt`이라 종료할 수 없음 | 409 | `INVALID_ROOM_STATUS_TRANSITION` |
| 동시 변경 충돌 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

## AI 기능군 API

> **도입 단계: P2** · **기능: AI-01·AI-02·AI-03** · **API 계약 상태: 계약 확정** · **제공 상태: AI-01a T1~T5와 AI-03a T1~T6 검증 범위 제공**
>
> 이 절의 AI-01 동의·AI-02 자연어 추천과 AI-03 초안·확인 경로는 현재 제공한다. 외부 provider·보존 경계는 [ADR-0074](adr/platform/0074-p2-ai-provider-consent-and-operation-boundary.md), AI-02의 호출 quota·고정 예약 비용·정확 게임명 직접 조회는 [ADR-0085](adr/platform/0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md), 초안·확인·멱등성은 [ADR-0075](adr/room/0075-p2-ai-draft-confirmation-and-idempotent-room-command.md), 지역은 [ADR-0076](adr/room/0076-p2-room-region-closed-set-and-compatibility.md)을 따른다. ADR-0085의 후보 DTO·직접 조회는 #951에서 제공하며, 제공 상태는 [P2 기능 상태](p2/README.md#기능별-현재-상태)에서 판정한다.

모든 AI 기능군 API는 로그인한 현재 사용자만 호출한다. `GET`은 CSRF가 필요 없고 상태 변경 `PUT`·`POST`·`PATCH`·`DELETE`는 세션과 CSRF가 필요하다. 유효한 외부 처리 동의가 없으면 provider 호출·추천·초안 생성·확인을 시작하지 않는다. AI-01은 동의·제품 흐름, AI-02는 자연어 추천, AI-03은 확인형 초안·Room 생성 경로를 소유하며, 기존 `POST /api/rooms` 즉시 생성 경로는 유지한다.

### AI-01 동의 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/assistant/consent` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `AssistantConsentResponse` |

동의가 아직 저장되지 않았으면 `status = NOT_GRANTED`를 반환한다. 이 조회는 provider를 호출하지 않는다. `policyVersion`·`policyUrl`은 현재 배포가 확인한 provider 정책만 반환하며, 확인할 수 없는 정책은 동의 승인 대상이 아니다.

### AI-01 동의 변경

| 항목 | 값 |
|---|---|
| Method / Path | `PUT /api/assistant/consent` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `AssistantConsentResponse` |

#### Request Body — AssistantConsentRequest

~~~json
{
  "decision": "GRANT",
  "consentVersion": "AI-01-CONSENT-V1"
}
~~~

| 필드 | 타입 | 필수 | nullable | 검증·의미 |
|---|---|:---:|:---:|---|
| `decision` | AssistantConsentDecision | Y | N | `GRANT` 또는 `REVOKE` |
| `consentVersion` | string | 조건부 | Y | `GRANT`일 때 현재 동의문 버전과 일치해야 함. `REVOKE`에서는 생략 |

`REVOKE`는 새 provider 호출과 활성 초안 생성을 막고, 현재 활성 초안을 `DISCARDED`로 종결한다. 동의 원문·사용자 자연어·provider token은 저장하지 않는다. `GRANT`는 현재 provider 정책의 no-retention·no-training 확인이 끝난 경우에만 저장한다. 이 전제를 확인할 수 없으면 `503 ASSISTANT_NOT_ENABLED`로 fail-closed 한다. 이 endpoint는 provider를 호출하지 않으므로 `ASSISTANT_PROVIDER_UNAVAILABLE`을 사용하지 않는다. `GRANT`의 판정 순서는 `UNAUTHENTICATED` → `CSRF_TOKEN_INVALID` → `ASSISTANT_NOT_ENABLED` → `VALIDATION_ERROR` → `ASSISTANT_CONSENT_VERSION_MISMATCH`다. `REVOKE`의 판정 순서는 `UNAUTHENTICATED` → `CSRF_TOKEN_INVALID` → `VALIDATION_ERROR`이며 `ASSISTANT_NOT_ENABLED`와 `ASSISTANT_CONSENT_VERSION_MISMATCH`를 적용하지 않는다. 따라서 기능이 비활성이어도 사용자는 항상 동의를 철회할 수 있다.

### AI-02 자연어 추천

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/assistant/recommendations` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `AssistantRecommendationResponse` |

#### Request Body — AssistantRecommendationRequest

~~~json
{
  "message": "초보자와 주말 저녁에 할 협력 게임을 추천해줘",
  "conditions": null
}
~~~

| 필드 | 타입 | 필수 | nullable | 검증·의미 |
|---|---|:---:|:---:|---|
| `message` | string | Y | N | 앞뒤 공백 제거 후 1~2000자, 제어문자 금지. 현재 한 번의 사용자 입력만 전달 |
| `conditions` | AssistantConditionSummary | N | Y | 직전 응답이 반환한 누적 조건. 첫 요청이나 새 대화에서는 `null` |

공통 인증·CSRF·feature gate·유효한 외부 처리 동의를 통과한 뒤, 서버는 provider 전에 `game.contract`의 정확 게임명 resolver를 수행한다. 직접 조회는 `message` 전체가 단독 게임명일 때만 적용하며 `Game.name`과 Unicode NFKC, 앞뒤 공백 제거, 연속 공백 하나로 축약, `Locale.ROOT` 대소문자 정규화 뒤 유일하게 같은지를 판정한다. 문장 부호 제거, 부분 일치, 별칭·영문명·BGG ID, 기본판과 확장판의 자동 통합은 하지 않는다. 유일 매치면 `RECOMMENDED`·후보 1건·해당 `conditions.gameId`를 반환하고 provider 호출, provider quota·비용 예약, provider usage event, 초안·Room·ChatRoom 생성은 모두 0건이다. 0건 또는 복수 매치면 이 직접 경로는 성공으로 처리하지 않고 아래 provider 기반 일반 추천으로 계속한다.

일반 추천에서 서버는 provider 호출 전에 PII·secret·지원하지 않는 지시를 검사한다. provider에는 버전이 지정된 instruction·강제 `propose_game_room_intent` schema·기준 시각·현재 문장·서버가 식별한 누락 필드만 allowlist로 전달하며, 원문 응답·대화 이력·prompt hash는 저장하지 않는다. 서버는 대화 이력과 추천 상태를 저장하지 않으므로 다회 입력 흐름은 클라이언트가 잇는다. `NEEDS_INPUT`을 받은 클라이언트는 다음 요청에 직전 응답의 `conditions`를 그대로 담아 보내고, 서버는 이를 신뢰할 수 없는 구조화 입력으로 다시 검증한 뒤 필드 단위로 병합한다. 이번 문장에서 값을 추출한 필드만 대체하고, 배열이 비어 있거나 스칼라가 `null`인 필드는 이번 문장이 그 조건을 언급하지 않은 것으로 보아 이전 값을 그대로 유지한다. 따라서 후속 문장이 게임 스타일을 다시 말하지 않아도 앞 턴에서 확보한 `categories`·`mechanisms`·`themes`가 지워지지 않는다. 조건을 비우려면 `conditions`를 생략해 새 대화로 시작한다. `conditions`를 생략하면 이번 문장만으로 판정하므로 이전 조건은 이어지지 않는다. provider에는 병합 결과가 아니라 현재 문장과 서버가 식별한 누락 필드만 전달한다. `NEEDS_INPUT`과 `UNSUPPORTED`는 HTTP 성공 결과이며 Room·ChatRoom·초안을 만들지 않는다. 후보가 있으면 서버가 모든 구조화 조건을 `AND`로 적용하고 내부 `RANK-01` 순서로 정렬한다.

### AI-03 초안 생성

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/assistant/drafts` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `201 Created`, `data`: `AssistantDraftResponse` |

#### Request Body — AssistantDraftCreateRequest

`AssistantRoomDraftInput`과 같은 필드를 사용한다. `region`은 생략할 수 있고 `홍대`로 정규화하며, `place`는 확인 카드에서 입력하기 위해 `null`을 허용한다. `roomType`, `title`, `experienceLevel`, `isRulemasterLed`, `startsAt`, `recruitmentCapacity`는 필수이고 `GAME_FOCUSED`의 `gameId`는 필수다. 모든 Room 필드 검증은 기존 `ROOM-03`과 같은 범위를 사용한다.

새 초안을 만들면 같은 사용자의 이전 `ACTIVE` 초안은 `DISCARDED`로 종결한다. 이 endpoint는 provider를 호출하지 않으며 Room·ChatRoom·참가 관계를 만들지 않는다. 초안은 생성 시점부터 15분 동안 유효하지만 응답에는 만료 시각이나 남은 시간을 포함하지 않는다.

추천 후보의 “이 조건으로 만들기”는 별도 서버 기본값 생성 API가 아니라, 클라이언트가 이 `AssistantRoomDraftInput`을 완성해 보내는 UI 흐름이다. `roomType = GAME_FOCUSED`, 선택 후보의 `gameId`, 제목 `"{정식 게임명} 모임"`, `description = null`, 응답 조건의 `experienceLevel`(없으면 `BEGINNER_WELCOME`), `isRulemasterLed = false`, 응답 조건의 `region`(없으면 `홍대`), `place = null`을 사용한다. `playerCount`가 2~11명이고 미래 `startsAt`이 있을 때만 `recruitmentCapacity = playerCount - 1`을 채워 자동 초안을 허용한다. 제목이 100자를 넘거나 위 두 필수 조건이 없으면 자동 초안을 만들지 않고 직접 입력 흐름만 제공한다. “내가 직접 채우기”는 선택 후보를 가진 편집 폼만 열며 제출 전에는 이 endpoint를 호출하지 않는다.

### AI-03 활성 초안 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/assistant/drafts/active` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | 유효한 활성 초안은 `200 OK`, `data`: `AssistantDraftResponse`; 활성 초안이 없으면 `204 No Content` |

현재 사용자의 유효한 `ACTIVE` 초안 하나만 조회해, 새로고침·이탈 뒤 확인 카드를 복구한다. 클라이언트는 `draftId`를 URL이나 브라우저 저장소에 보관하지 않는다. 초안이 없거나 이미 `CONFIRMED`·`DISCARDED`이면 `204 No Content`이며, 다른 사용자의 초안이나 종결 초안은 조회 대상이 아니다. `ACTIVE` 초안의 요청 시작 시각에 `expiresAt`이 지났으면 `410 ASSISTANT_DRAFT_EXPIRED`이고, 조회 자체는 상태를 `DISCARDED`로 바꾸지 않는다. 이 endpoint는 provider를 호출하지 않으므로 feature gate·외부 처리 동의 검사를 적용하지 않고 인증만 요구한다.

### AI-03 초안 수정

| 항목 | 값 |
|---|---|
| Method / Path | `PATCH /api/assistant/drafts/{draftId}` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `AssistantDraftResponse` |

요청 본문은 `draftVersion`과 변경할 `AssistantRoomDraftInput` 필드 중 하나 이상을 받는다. 수정은 `ACTIVE` 초안에만 허용한다. 판정 순서는 `404` → 상태 → 만료 → version이다. 대상이 `CONFIRMED`이거나 `DISCARDED`이면 만료·version 검사 전에 `409 ASSISTANT_DRAFT_CONFLICT`로 끝내고 저장하지 않으므로, 이미 만든 Room·ChatRoom과 초안 `input`이 달라지거나 terminal 상태가 다시 변형되지 않는다. `410 ASSISTANT_DRAFT_EXPIRED`는 `ACTIVE` 초안에만 적용한다. `draftVersion`이 현재 값과 다르면 `409 ASSISTANT_DRAFT_CONFLICT`이며 저장하지 않는다. `place`는 이 경로에서 사용자가 직접 입력·수정하며 provider 결과나 raw prompt에서 채우지 않는다. 수정 성공 시 버전을 1 증가시키고 활성 초안의 만료 기준은 생성 시각을 유지한다.

### AI-03 초안 폐기

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/assistant/drafts/{draftId}` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `{}` |

현재 사용자 소유 `ACTIVE` 초안을 `DISCARDED`로 만든다. 판정 순서는 `404` → 상태 → 만료다. 이미 `DISCARDED`인 같은 초안에 대한 반복 요청은 만료 여부와 무관하게 새 부수효과 없이 `200 OK`로 수렴하고, `CONFIRMED` 초안은 만료 검사 전에 `409 ASSISTANT_DRAFT_CONFLICT`로 거절한다. 따라서 이 API로 확인된 초안의 Room을 취소하지 않는다. `410 ASSISTANT_DRAFT_EXPIRED`는 `ACTIVE` 초안에만 적용한다.

### AI-03 초안 확인과 Room 생성

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/assistant/drafts/{draftId}/confirm` |
| 인증 / CSRF | 필요 / 필요 |
| 필수 헤더 | `Idempotency-Key` |
| 성공 | 최초 생성은 `201 Created`, 같은 키·같은 의미 재시도는 `200 OK`; `data`: `AssistantRoomCreationResult` |

#### Request Body — AssistantDraftConfirmRequest

~~~json
{
  "draftVersion": 2
}
~~~

| 필드 | 타입 | 필수 | nullable | 검증·의미 |
|---|---|:---:|:---:|---|
| `draftVersion` | integer | Y | N | 확인 카드가 읽은 최신 초안 버전 |

`Idempotency-Key`는 앞뒤 공백 없는 1~100자의 ASCII printable 문자다. 서버는 SHA-256 hash만 저장한다. 멱등성 범위는 `(currentUserId, draftId, DRAFT_CONFIRM)`이며, 같은 범위의 확인 결과는 `draftVersion` 검사보다 먼저 재생한다. 다른 키·오래된 version·범위 밖 재사용·동시성 충돌은 `409 ASSISTANT_DRAFT_CONFLICT`이고 Room을 만들지 않는다. 같은 key와 같은 의미의 재시도는 두 번째 Room·ChatRoom을 만들지 않는다. 확인 결과의 재생 보장은 AI 기능이 활성인 동안 확인 시각부터 24시간이며, 비활성 상태에서는 아래 판정 순서대로 재생 전에 `ASSISTANT_NOT_ENABLED`로 끝난다. 보존 기간이 지난 기록은 별도 batch를 기다리지 않고 같은 사용자의 다음 초안 생성·확인 명령이 같은 트랜잭션에서 만료를 판정해 정리하므로, 같은 key를 새 초안 확인에 다시 쓸 수 있고 이때는 이전 Room 결과를 재생하지 않는다.

확인 시작 시 대상 `USERS` 행 → 초안 행 → `ASSISTANT_IDEMPOTENCY_RECORDS`를 이 순서로 잠근다. 판정 순서는 `ASSISTANT_NOT_ENABLED` → `404` → 같은 범위·같은 key의 멱등 재생 → 상태 → 만료 → 동의 → version → 필수 `place`다. 기능 gate는 동의 endpoint와 같게 업무 판정보다 먼저 fail-closed로 적용하므로 비활성 상태에서는 멱등 재생도 하지 않는다. 보존 기간 안의 같은 key 재시도는 상태 판정보다 먼저 원래 결과를 재생하고, 재생 대상이 아닌 `CONFIRMED`·`DISCARDED` 초안의 확인 시도는 `409 ASSISTANT_DRAFT_CONFLICT`로 끝낸다. `410 ASSISTANT_DRAFT_EXPIRED`는 `ACTIVE` 초안에만 적용한다. 확인 성공은 기존 `room.contract` 확인형 command를 호출해 Room과 ChatRoom을 하나의 DB 트랜잭션에서 생성하고, 초안을 `CONFIRMED`로 바꾸며 결과 참조를 저장한다. 어느 단계라도 실패하면 Room·ChatRoom·초안 상태 변경을 함께 롤백한다. 기존 수동 `POST /api/rooms`는 이 경로와 별개로 계속 제공한다.

## 8. 참가·대기·내 모임 API

### PART-01 방 참가·재참가

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/rooms/{roomId}/participants` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `201 Created`, `data`: `RoomParticipationResponse` (`participationStatus = ACTIVE`) |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

Request body는 없다.

- 신규 참가와 취소 후 재참가 모두 `201 Created`와 `RoomParticipationResponse`를 반환한다.
- 어떤 동시 요청에서도 모집 정원을 초과한 참가는 성공하지 않는다. 동시성 제어는 [ADR-0005](adr/participation/0005-room-participation-optimistic-locking.md)를 따른다.
- 참가 후 주최자 외 `ACTIVE` 참가자 수가 `recruitmentCapacity`에 도달하면 `RECRUITING → CLOSED`로 자동 전환한다.
- 직접 참가가 `CAPACITY_EXCEEDED` 또는 동시성 충돌로 실패해도 대기 성공 응답으로 바꾸거나 대기 관계를 생성하지 않는다.
- 시간대가 겹치는 다른 방 참가는 검사하지 않는다.

#### 오류 판정 순서

인증과 방 존재 확인 뒤 여러 업무 조건이 동시에 성립하면 아래 순서로 반환한다. 따라서 마지막 좌석 참가로 `CLOSED`가 된 방의 다음 신규 참가 요청은 `CAPACITY_EXCEEDED`다.

1. 방이 `CANCELED` 또는 `FINISHED`: `ROOM_NOT_RECRUITING`
2. 요청자가 주최자이거나 현재 `ACTIVE` 참가 관계가 있음: `ALREADY_PARTICIPATING`
3. `remainingRecruitmentSeats = 0`: `CAPACITY_EXCEEDED`
4. `now >= startsAt`이거나 `status != RECRUITING`: `ROOM_NOT_RECRUITING`

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 주최자이거나 이미 참가 중 | 409 | `ALREADY_PARTICIPATING` |
| 남은 모집 자리가 없음 | 409 | `CAPACITY_EXCEEDED` |
| 모집 중이 아니거나 시작 시각이 지남 | 409 | `ROOM_NOT_RECRUITING` |
| 동시 변경 충돌 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### PART-02 참가 취소

> **단계: P0 현행 + P1 `PART-04` 확장 계약** · 현재 P1 상태: [기능 상태 정본](archive/p1/README.md#기능별-종료-상태)

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/rooms/{roomId}/participants/me` |
| 인증 / CSRF | 필요, 현재 참가자 전용 / 필요 |
| 성공 | `200 OK`, `data`: `RoomParticipationResponse` (`participationStatus = CANCELED`) |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

Request body는 없다.

- 본인만 `now < startsAt`일 때 취소할 수 있다. 활성 대기자가 있으면 참가 취소와 첫 대기자 한 명의 자동 승격을 같은 ROOM 처리에서 완료하고 `CLOSED`를 유지한다. 활성 대기자가 없을 때만 `CLOSED → RECRUITING`으로 자동 복귀한다.
- 성공 응답은 자동 승격까지 끝난 최종 `roomStatus`, `participantCount`, `remainingRecruitmentSeats`를 반환한다. 승격 여부 필드와 승격된 사용자 신원은 포함하지 않는다.
- 주최자는 이 API로 본인의 참가를 따로 취소할 수 없다.

#### 오류 판정 순서

인증과 방 존재 확인 뒤 여러 업무 조건이 동시에 성립하면 아래 순서로 반환한다.

1. 요청자가 주최자: `FORBIDDEN`
2. 현재 `ACTIVE` 참가 관계가 없음: `PARTICIPATION_NOT_FOUND`
3. `now >= startsAt`: `INVALID_ROOM_STATUS_TRANSITION`

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 요청자가 주최자 | 403 | `FORBIDDEN` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 현재 참가 관계가 없음 | 404 | `PARTICIPATION_NOT_FOUND` |
| 시작 시각 이후 취소 | 409 | `INVALID_ROOM_STATUS_TRANSITION` |
| 동시 변경 충돌 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### PART-03 내 모임 조회

> **단계: P0 현행 + P1 `ROOM-08`·`CHAT-05` 확장 계약** · 현재 P1 상태: [기능 상태 정본](archive/p1/README.md#기능별-종료-상태)

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/users/me/rooms` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `PageResponse<MyRoomListItem>` |

내 모임 목록은 전역 저장 상태 보정 없이 같은 고정 `requestTime`의 유효 상태와 snapshot 사실로 응답을 구성하므로, 목록 대상 ROOM 보정 충돌의 `ROOM_CONCURRENT_MODIFICATION`을 반환하지 않는다.

#### Query Parameters

| 이름 | 타입 | 필수 | 기본값 | 의미 |
|---|---|:---:|---|---|
| `role` | MyRoomRole | Y | 없음 | `all`, `joined`, `hosted` |
| `page` | integer | N | `0` | 페이지 번호 |
| `size` | integer | N | `10` | 페이지 크기, 1~100 |

| `role` | 포함 범위 |
|---|---|
| `joined` | `Participation.status = ACTIVE`이고 `Room.status != CANCELED`인 본인 참가 방. `FINISHED` 방은 참여 이력으로 포함 |
| `hosted` | 본인이 개설한 모든 방 |
| `all` | `joined`와 `hosted`의 중복 없는 합집합 |

참가 취소한 `CANCELED` 관계와 방이 취소된 `CANCELED` 방은 `joined`에서 제외한다. 대기 중인 ROOM은 현재 조회 범위에 포함하지 않으며, 여러 대기 ROOM을 한 번에 조회·관리하는 API는 P1 범위 밖이다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| query parameter 검증 실패 | 400 | `VALIDATION_ERROR` |

### PART-04 대기 등록·재신청

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `PART-04`](archive/p1/README.md#기능별-종료-상태)

> **빈 요청 계약:** 이 절의 대기 등록·본인 대기 상태 조회·대기 취소 API는 모두 request body가 없으며, `Content-Type`과 `Transfer-Encoding` 헤더도 보내지 않는다. `Content-Length: 0`은 허용한다. 인증·CSRF·path 검증 뒤 handler에 진입한 요청에서 `Content-Type`·`Transfer-Encoding`·실제 본문 중 하나라도 있으면 ROOM·대기 관계를 조회하기 전에 `415 UNSUPPORTED_MEDIA_TYPE`을 반환한다.

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/rooms/{roomId}/waitlist` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | 신규 및 허용된 `CANCELED`·`PROMOTED` 재신청은 `201 Created`, 활성 대기 중 재요청은 `200 OK`; `data`: `MyRoomWaitlistResponse` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

Request body는 없다.

- 신규 신청은 하나의 최신 대기 레코드를 `WAITING`으로 만들고 현재 마지막 순번을 반환한다.
- 이미 `WAITING`인 사용자의 중복 신청은 새 관계를 만들거나 순번을 바꾸지 않고 조회 시점의 최신 순번을 반환한다.
- 최신 참가·방·시각·좌석 조건을 충족한 `CANCELED`·`PROMOTED` 재신청은 같은 레코드에 새 순번·신청 시각을 기록하되 최초 생성 시각을 보존해 대기열 맨 뒤로 이동한다. `EXPIRED`·`ROOM_CANCELED`에서는 재신청할 수 없다.
- 직접 참가 가능한 빈자리가 있거나 시작 시각에 도달했거나 방이 `CANCELED`·`FINISHED`이면 대기를 등록하지 않는다.

#### 오류 판정 순서

인증·CSRF·path와 빈 요청 계약을 확인한 뒤 방 존재를 확인하고, 현재 시각 기준 상태를 반영해 아래 순서로 판정한다.

1. `now >= startsAt`이거나 방이 `CANCELED`·`FINISHED`: `WAITLIST_NOT_AVAILABLE`
2. 요청자가 주최자 또는 현재 `ACTIVE` 참가자: `ALREADY_PARTICIPATING`
3. 요청자가 이미 `WAITING`: 기존 순서를 유지하고 `200 OK`
4. 기존 대기 상태가 `EXPIRED` 또는 `ROOM_CANCELED`: `WAITLIST_NOT_AVAILABLE`
5. 남은 모집 자리가 있어 직접 참가할 수 있음: `WAITLIST_NOT_AVAILABLE`
6. 대기 이력이 없거나 기존 대기 상태가 `CANCELED` 또는 `PROMOTED`: 대기열 마지막에 등록하고 `201 Created`

대기 등록·재신청은 최초 시도 전에 request time을 한 번 고정한다. ROOM 낙관적 락 또는 조건부 version claim 충돌과 정확히 `uq_room_waitlists_waiting_room_queue_order` 제약에서 발생한 현재 `WAITING` 순번 UNIQUE 충돌만 최초 시도 포함 총 3회의 단일 예산으로 전체 요청을 재시도하며, 매 시도마다 같은 request time과 최신 상태로 위 순서를 다시 판정한다. 그 밖의 DB 오류는 재시도하지 않는다.

총 3회를 소진한 최종 원인이 ROOM 충돌이면 `ROOM_CONCURRENT_MODIFICATION`을 반환한다. 정확한 순번 UNIQUE 충돌의 예산 소진이나 비대상 DB 오류는 내부 제약명·SQL 정보를 노출하지 않는 기존 공통 `500 INTERNAL_SERVER_ERROR`로 반환한다.

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 빈 요청 계약 위반(`Content-Type`, `Transfer-Encoding` 또는 실제 본문) | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 요청자가 주최자 또는 현재 참가자 | 409 | `ALREADY_PARTICIPATING` |
| 현재 방·시각·좌석 조건에서 대기할 수 없음 | 409 | `WAITLIST_NOT_AVAILABLE` |
| ROOM 낙관적 락 또는 조건부 version claim 충돌의 재시도 예산 소진 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### PART-04 본인 대기 상태 조회

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `PART-04`](archive/p1/README.md#기능별-종료-상태)

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/rooms/{roomId}/waitlist/me` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `MyRoomWaitlistResponse` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

Request body는 없다.

- `WAITING`, `PROMOTED`, `CANCELED`, `EXPIRED`, `ROOM_CANCELED`를 모두 정상 결과로 반환한다.
- `position`은 `WAITING`일 때만 조회 시점의 1 이상 순번이고, 그 외 상태는 `null`이다.
- 대기 신청 이력 자체가 없을 때만 `WAITLIST_ENTRY_NOT_FOUND`를 반환한다.
- 자동 승격 뒤에는 이 응답의 `PROMOTED`와 ROOM 상세의 `myRole = JOINED`가 같은 결과를 나타내야 한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 빈 요청 계약 위반(`Content-Type`, `Transfer-Encoding` 또는 실제 본문) | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 본인 대기 이력이 없음 | 404 | `WAITLIST_ENTRY_NOT_FOUND` |
| 동시 변경으로 최신 상태를 확인할 수 없음 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### PART-04 대기 취소

> **단계: P1 계약** · 현재 상태: [P1 기능 종료 상태의 `PART-04`](archive/p1/README.md#기능별-종료-상태)

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/rooms/{roomId}/waitlist/me` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `{}` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

Request body는 없다.

현재 상태가 `WAITING`일 때만 `CANCELED`로 변경한다. `WAITING`이 아니거나 대기 신청 이력이 없으면 같은 `WAITLIST_ENTRY_NOT_FOUND`를 반환한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |
| 빈 요청 계약 위반(`Content-Type`, `Transfer-Encoding` 또는 실제 본문) | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 취소할 본인 `WAITING` 관계가 없음 | 404 | `WAITLIST_ENTRY_NOT_FOUND` |
| 동시 변경 충돌 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

## 9. 알림·채팅 API

> **단계: P1 계약**
>
> `NOTI-02`·`NOTI-03`의 현재 제공·검증·운영 상태는 [P1 기능 종료 상태](archive/p1/README.md#기능별-종료-상태)을 따른다. 이 절은 상태가 바뀌어도 P1 HTTP 계약으로 유지한다.

P1 알림 API는 로그인한 사용자의 앱 내 알림만 제공하도록 계약한다. 알림 생성은 방·참가 업무와 내부 Outbox relay가 담당하므로 공개 생성 API는 없다. 제품 범위·수신자·중복 방지 규칙은 [P1 알림 구현 명세](archive/p1/notification.md), 물리 저장 구조는 [ERD의 P1 알림 저장 계약](ERD.md#p1-알림-저장-계약)을 따른다.

### NOTI-02 내 알림 목록

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/users/me/notifications` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `PageResponse<NotificationListItem>` |

#### Query Parameters

| 이름 | 타입 | 필수 | 기본값 | 의미 |
|---|---|:---:|---|---|
| `page` | integer | N | `0` | 페이지 번호 |
| `size` | integer | N | `10` | 페이지 크기, 1~100 |

목록 QueryService의 짧은 읽기 트랜잭션이 고정한 PostgreSQL `transaction_timestamp()`보다 저장 만료 시각(`NOTIFICATIONS.expires_at`)이 뒤인 본인 알림만 `createdAt DESC, id DESC` 순서로 반환한다. 목록 본문과 `totalElements` count는 같은 트랜잭션의 DB `queryTime`을 사용한다. `createdAt`은 원인 Command Coordinator의 고정 `requestTime`이므로 relay 처리 순서와 다를 수 있다. 같은 DB 상태에서는 원인 이벤트 시각이 같은 알림도 페이지 경계에서 순서가 바뀌지 않는다. `createdAt + NOTIFICATION_RETENTION`이 지난 만료 알림은 물리 삭제 전에도 결과와 `totalElements`에서 제외한다. 결과가 없으면 빈 `content`와 `totalElements = 0`을 반환한다. `expires_at`은 응답 필드로 노출하지 않으며 유형 필터, 검색, 클라이언트 지정 정렬은 지원하지 않는다. Offset pagination의 안정성은 같은 DB 상태에만 한정하며, 요청 사이에 지연 복구된 과거 알림이 중간 페이지에 추가되면 항목이 이동·중복·누락될 수 있음을 P1에서 수용한다.

클라이언트는 `type`으로 표시 문구를 렌더링한다. `roomTitle`은 알림과 함께 저장한 문자열이 아니라 목록 조회 시 결합한 현재 `ROOMS.title`이므로, 방 제목이 바뀌면 과거 알림에도 바뀐 제목을 반환한다. `roomTitle`과 `roomId`는 사람이 알림을 구분하고 화면으로 이동하기 위한 최소 표시값일 뿐 방 조회 권한이 아니며, 이동 시 현재 세션으로 방 상세를 다시 조회한다. 응답과 로그에는 참가자 닉네임·사용자 ID·이메일·정확한 장소·전체 참가자 목록·인증 정보를 포함하지 않는다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| query parameter 검증 실패 | 400 | `VALIDATION_ERROR` |

### NOTI-02 내 미확인 알림 수

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/users/me/notifications/unread-count` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `UnreadNotificationCountResponse` |

Path variable·query parameter·body는 없다. `unreadCount`는 미확인 개수 QueryService 읽기 트랜잭션의 PostgreSQL `transaction_timestamp()`보다 `expires_at`이 뒤이고 `readAt = null`인 본인 알림 건수다. 목록 GET과는 별도 요청이므로 같은 시각이나 DB 스냅샷을 공유하지 않는다. 만료 알림은 물리 삭제 전에도 세지 않으며 알림이 없거나 모두 읽었으면 `0`을 반환한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |

### NOTI-03 내 알림 단건 읽음

| 항목 | 값 |
|---|---|
| Method / Path | `PATCH /api/users/me/notifications/{notificationId}` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `NotificationListItem` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `notificationId` | integer | Y | 1 이상의 알림 ID |

#### Request Body — NotificationReadRequest

~~~json
{
  "read": true
}
~~~

| 필드 | 타입 | 필수 | nullable | 검증 |
|---|---|:---:|:---:|---|
| `read` | boolean | Y | N | `true`만 허용 |

빈 객체, `null`, `read = false`와 클라이언트의 `readAt` 직접 지정은 `VALIDATION_ERROR`다. 단건 읽음 `UPDATE` SQL은 내부 `operation` CTE에서 PostgreSQL `clock_timestamp()`를 한 번 평가해 `operationTime`으로 고정하고, 만료 판정과 처음 읽는 행의 `readAt`에 같은 값을 사용한다. 이미 읽은 보존 기간 안의 본인 알림에 같은 요청을 반복하면 저장값을 변경하지 않고 최초 `readAt`이 담긴 현재 알림을 반환한다. 읽지 않음으로 되돌리는 요청은 지원하지 않는다.

존재하지 않는 알림, 다른 사용자의 알림과 저장 `expires_at <= operationTime`인 만료 알림에는 모두 `NOTIFICATION_NOT_FOUND`를 반환해 타인의 알림 존재 여부와 물리 정리 지연을 노출하지 않는다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| path ID 또는 요청 본문 검증 실패 | 400 | `VALIDATION_ERROR` |
| 알림이 없거나 본인 알림이 아니거나 보존 기간이 만료됨 | 404 | `NOTIFICATION_NOT_FOUND` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

### NOTI-03 내 알림 일괄 읽음

| 항목 | 값 |
|---|---|
| Method / Path | `PATCH /api/users/me/notifications` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `NotificationBulkReadResponse` |

#### Request Body

요청 본문과 검증은 [단건 읽음의 NotificationReadRequest](#request-body-notificationreadrequest)와 같다.

서버는 PostgreSQL 기본 격리 수준인 `READ COMMITTED`를 유지하고, 하나의 쓰기 트랜잭션 안에서 단일 data-modifying CTE/`UPDATE` SQL 문장으로 처리한다. CTE와 `UPDATE`는 하나의 DB 문장 스냅샷을 공유하며 세부 순서는 다음과 같다.

1. `operation` CTE가 SQL 실행 중 PostgreSQL 실제 시각을 한 번 평가해 `operationTime`으로 고정한다.
2. `boundary` CTE가 `recipient_user_id = 현재 사용자`이고 `expires_at > operationTime`인 스냅샷 내 알림의 `MAX(id)`를 `boundaryNotificationId`로 고정한다.
3. `updated` CTE가 같은 사용자·만료 조건에서 `id <= boundaryNotificationId`이고 `read_at IS NULL`인 행만 `read_at = operationTime`으로 갱신하고 변경한 ID를 `RETURNING`한다.
4. 마지막 `SELECT`가 `updated`의 행 수, `boundaryNotificationId`와 같은 `operationTime`의 `readAt`을 한 결과로 반환한다. 이미 읽은 알림의 최초 `readAt`은 변경하지 않는다.
5. SQL 문장 스냅샷에 보이지 않았던 알림은 ID나 원인 이벤트 시각과 관계없이 갱신하지 않는다. 따라서 문장 스냅샷 획득 뒤 커밋된 알림은 미확인 상태로 남고, 만료 알림도 갱신하지 않는다.

보존 기간 안의 알림이 하나도 없으면 `updatedCount = 0`, `boundaryNotificationId = null`을 반환한다. 현재 집합이 모두 읽음이면 `updatedCount = 0`이고 현재 경계는 반환한다. 같은 DB 상태에서 요청을 반복하면 추가 저장 변경 없이 `updatedCount = 0`으로 수렴한다. 요청 사이에 새 알림이 커밋되면 뒤 요청은 새 경계를 가진 별도 일괄 읽음으로 처리한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 요청 본문 검증 실패 | 400 | `VALIDATION_ERROR` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

#### 클라이언트 읽음 상태 동기화

- 미확인 배지의 정본은 `GET /api/users/me/notifications/unread-count` 응답이다. 현재 불러온 목록 한 페이지의 `readAt`만 세어 전체 미확인 개수를 만들지 않는다.
- 단건 읽음 성공 시 같은 `id`의 목록 항목을 응답 `NotificationListItem`으로 갱신하고 미확인 개수를 즉시 다시 조회한다. 이미 읽은 알림의 반복 요청도 응답에 담긴 최초 `readAt`을 그대로 적용한다.
- 일괄 읽음 성공 시 `boundaryNotificationId`를 실제 갱신 ID 집합이나 클라이언트 읽음 경계로 해석해 현재 목록을 직접 변경하지 않는다. 목록 첫 페이지와 미확인 개수를 즉시 다시 조회해 서버 응답으로 교체하며, polling 중단·이전 응답 폐기와 요청 세대 규칙은 [알림 프론트엔드 UX 계약](archive/p1/notification.md#읽음-상태-동기화)을 따른다. `updatedCount`만으로 배지를 영구히 `0`으로 고정하지 않는다.
- 낙관적으로 화면을 먼저 바꾼 읽음 요청이 실패하면 그 상태를 확정하지 않는다. 목록과 미확인 개수를 다시 조회해 서버 상태로 복구하며, 상세 사용자 동작은 [알림 프론트엔드 UX 계약](archive/p1/notification.md#프론트엔드-ux-계약)을 따른다.

### 채팅 공통 계약

현재 제품·HTTP·WebSocket 계약은 이 문서가 정본이며, P1 종료 시점의 기록은 [P1 방 채팅 기능 명세](archive/p1/chatting.md)와 [P1 기능 종료 상태](archive/p1/README.md#기능별-종료-상태)에 보존한다. 메시지 ID cursor·실시간 전달·PostgreSQL 정본·보관 경계는 [ADR-0031](adr/chat/0031-chat-history-cursor-pagination.md)·[ADR-0032](adr/chat/0032-http-send-websocket-receive.md)·[ADR-0033](adr/chat/0033-postgresql-source-after-commit-delivery.md)·[ADR-0049](adr/chat/0049-chat-message-retention-lock-section-boundary.md), 전송 제한·Redis 실패 처리의 공개 계약은 [#288 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930)과 [#372 정본 반영 이슈](https://github.com/bamsongi-club/albam-mate/issues/372), 50/100 완화 결정은 [#760 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/760#issuecomment-5300372595)을 따른다.

모든 채팅 요청은 요청 시점의 방 상태와 주최자·현재 `ACTIVE` 참가자 관계를 서버에서 다시 확인한다. 접근 확인 전 대상 ROOM 보정의 낙관 락 재시도를 소진하면 `409 ROOM_CONCURRENT_MODIFICATION`을 반환한다. `RECRUITING`·`CLOSED` 방만 일반 사용자 접근을 허용하며, 참가 취소·`CANCELED`·`FINISHED` 상태는 `FORBIDDEN`으로 거절한다. 메시지 본문은 로그와 메트릭에 기록하지 않는다.

### CHAT-06 입장·퇴장 시스템 메시지 계약

> **도입 단계: P2** · **기능: CHAT-06** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**
>
> 이 절의 필드·enum·문구는 승인된 목표 계약이며 현재 제공 기능이 아니다. 제품 상태는 [P2 기능 상태의 `CHAT-06`](p2/README.md#기능별-현재-상태)에서만 판정한다.

`CHAT-06`은 새 엔드포인트를 추가하지 않는다. 기존 이력 조회와 실시간 구독이 사용자 메시지와 함께 입장·퇴장 안내를 반환하도록 [ChatMessage](#415-chatmessage)와 [ChatMessageEvent](#417-chatmessageevent)를 확장한다. 안내를 남기는 사건 경계와 소급 생성 제외는 [CHAT-06 명세](p2/chat.md#chat-06-입장퇴장-시스템-메시지), 저장 모델·문구 소유의 선택 이유는 [ADR-0078](adr/chat/0078-chat-system-message-storage-and-read-time-composition.md)을 따른다.

#### 안내 문구

서버는 `systemEvent`와 안내 대상의 현재 공개 닉네임으로 아래 문장을 조립해 `content`로 반환한다. 클라이언트는 이 문장을 그대로 표시하고 자체 문구를 조립하지 않는다.

| `systemEvent` | `content` |
|---|---|
| `PARTICIPANT_ENTERED` | `{닉네임}님이 입장했어요.` |
| `PARTICIPANT_LEFT` | `{닉네임}님이 나갔어요.` |

`{닉네임}`은 조회 시점의 `subject.nickname`이다. 대상 사용자의 공개 프로필을 찾지 못하면 조회를 실패시키지 않고 `알 수 없는 사용자`를 대신 사용하며, 이때 `subject.nickname`도 같은 값으로 반환한다. 닉네임이 바뀌면 과거 안내도 현재 닉네임으로 조립되며, 사건 당시의 닉네임은 제공하지 않는다.

#### 이력·구독에서의 취급

- 시스템 메시지는 사용자 메시지와 같은 `messageId` 순서를 사용하며, `beforeMessageId`·`size`·`nextBeforeMessageId`·`hasNext`와 `afterMessageId` catch-up 규칙을 그대로 따른다. `size`는 두 종류를 합해 센다.
- 접근 판정은 [채팅 공통 계약](#채팅-공통-계약)과 같다. 주최자·현재 `ACTIVE` 참가자가 아니거나 방이 `CANCELED`·`FINISHED`이면 시스템 메시지도 반환하지 않는다.
- 시스템 메시지는 사용자가 만들 수 없다. `POST /api/rooms/{roomId}/chat/messages`는 `USER` 메시지만 저장하며, 시스템 메시지는 사용자·방 전송 제한 quota를 소비하지 않는다.
- 안내 문장·닉네임·사용자 ID는 로그와 metric label에 기록하지 않는다.
- 이 계약이 적용되기 전에 만들어진 채팅방에는 안내를 소급 생성하지 않으므로, 기존 방의 이력에는 배포 이후 사건의 안내만 나타난다.

### CHAT-07 채팅 목록 마지막 메시지·방별 미읽음 상태 계약

> **도입 단계: P2** · **기능: CHAT-07** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**
>
> 이 절의 필드·엔드포인트는 승인된 목표 계약이며 현재 제공 기능이 아니다. 제품 상태는 [P2 기능 상태의 `CHAT-07`](p2/README.md#기능별-현재-상태)에서만 판정한다.

제품 규칙은 [CHAT-07 명세](p2/chat.md#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태), 저장·집계 방식의 선택 이유는 [ADR-0079](adr/chat/0079-chat-room-read-cursor-and-derived-unread-count.md)를 따른다.

- 마지막 메시지·미읽음 개수는 [MyRoomListItem](#410-myroomlistitem)에 필드를 더해 제공한다. 새 목록 엔드포인트를 만들지 않는다.
- 미읽음 개수는 저장된 counter가 아니라 조회 시점에 계산한 값이다. 서버가 몇 번을 다시 계산해도 같은 메시지 상태에서는 항상 같은 값을 반환한다.
- `CHAT-06` `SYSTEM` 메시지는 마지막 메시지 미리보기와 미읽음 집계에 포함한다. 다만 안내 대상 본인(`subject`)에게는 자신의 입장·퇴장 안내를 미읽음으로 세지 않는다. 이 판정은 `CHAT-06`의 저장·응답 계약을 바꾸지 않는다.
- 상단 채팅 아이콘의 미읽음 표시는 [`GET /api/users/me/chat/unread-summary`](#chat-07-내-미읽음-채팅방-요약)가 반환하는 "미읽음 메시지가 1건 이상인 방의 개수"를 사용한다. 방별 `unreadCount`의 총합이 아니다.
- 이 기능은 새 실시간 채널을 만들지 않는다. 채팅 목록·상단 배지는 조회 시점 값이며, 화면이 열려 있는 동안의 서버 push는 이 계약의 범위가 아니다.

### CHAT-02 메시지 전송

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/rooms/{roomId}/chat/messages` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | 최초 저장은 `201 Created`, `data`: `ChatMessage`; 같은 `clientMessageId` 재시도는 `200 OK`와 최초 결과 |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

#### Request Body — ChatMessageSendRequest

~~~json
{
  "clientMessageId": "01JCHAT-0001",
  "content": "오늘 7시에 홍대입구에서 만나요."
}
~~~

| 필드 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `clientMessageId` | string | Y | 1~100자. 같은 방·같은 사용자에서 재시도 멱등성의 기준 |
| `content` | string | Y | CRLF(`\r\n`)를 LF(`\n`)로 정규화하고 LF 외 제어문자(단독 CR·탭·NUL 등)를 거절한 뒤, 앞뒤 공백 제거 후 1~500자의 일반 텍스트 |

LF는 본문에 그대로 보존하며, 저장·이력 조회·실시간 수신은 정규화된 LF 형태를 반환한다. 본문 길이와 같은 `clientMessageId` 재시도의 본문 비교도 정규화·공백 제거 뒤 본문을 기준으로 한다.

이 API로는 `USER` 메시지만 만들 수 있다. `CHAT-06` 입장·퇴장 시스템 메시지는 참가·참가 취소 확정에서만 서버가 저장하며 클라이언트가 요청으로 만들 수 없다([CHAT-06 계약](#chat-06-입장퇴장-시스템-메시지-계약)).

검증·권한 판정은 세션, 방 존재, 방 상태·현재 관계, 본문, 멱등성 순서로 수행한다. 같은 사용자가 같은 방에서 같은 `clientMessageId`로 다른 정규화 본문을 보내면 `400 VALIDATION_ERROR`다. 전송 제한은 아래 검증을 통과한 신규 전송에만 적용하며 PostgreSQL 저장 직전에 두 bucket을 함께 판정한다.

#### 전송 제한 계약

| 대상 | 제한 키 | 허용량 | 창·TTL |
|---|---|---:|---|
| 사용자 | 인증된 `userId`, 모든 방 합산 | 50건/10초 | 10초 고정 창 |
| 방 | `roomId`, 모든 참여자 합산 | 100건/10초 | 10초 고정 창 |

- 첫 허용 요청이 각 bucket의 TTL을 시작한다. 이후 허용·거절 요청은 TTL을 연장하지 않는다.
- 사용자·방 bucket의 허용 확인과 증가는 원자적으로 처리한다. 하나라도 초과하면 어느 bucket도 증가시키지 않는다.
- 인증·관계·본문·멱등성 검증 실패, 권한 거부, 이미 저장된 동일 `clientMessageId`의 동일 payload 재전송은 quota를 소비하지 않는다.
- 제한 초과는 `429 RATE_LIMIT_EXCEEDED`로 응답한다. `Retry-After`는 초과한 bucket의 남은 TTL을 밀리초에서 올림한 초 단위 값으로 계산하며, 두 bucket이 초과하면 더 큰 값을 사용한다. 이 헤더는 429에만 포함하고 성공 응답과 503에는 포함하지 않는다.
- Redis 연결·명령·원자 연산·TTL 확인 실패 또는 결과 불명확은 fail closed로 처리한다. 메시지를 PostgreSQL에 저장하지 않고 `503 SERVICE_UNAVAILABLE`을 반환하며 인메모리 fallback은 허용하지 않는다.
- P1 초기 실행값은 Redis 연결 timeout 1초, Redis 명령·Lua 실행 timeout 2초다. 서버는 Redis 재기동을 기다리기 위해 이 요청을 자동 재시도하지 않으며, timeout·연결 실패·결과 불명확은 위 fail-closed 503 계약으로 처리한다.
- 프런트 채팅 POST는 3초 deadline을 사용한다. POST가 시작된 뒤 HTTP 응답 없이 deadline 또는 전송 계층 오류가 발생하면 저장 성공·실패를 단정하지 않고 입력을 유지한 채 `전송 여부를 확인하지 못했어요. 다시 시도해주세요.`를 표시한다. 같은 본문 수동 재시도는 기존 `clientMessageId`를 재사용하고, 본문을 수정하면 새 식별자를 발급한다. 명시적인 503·4xx 응답은 이 미확정 상태가 아니다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 주최자·현재 `ACTIVE` 참가자가 아니거나 방이 `CANCELED`·`FINISHED`임 | 403 | `FORBIDDEN` |
| 대상 ROOM 보정의 낙관 락 재시도 소진 | 409 | `ROOM_CONCURRENT_MODIFICATION` |
| 본문·경로·멱등성 키 검증 실패 | 400 | `VALIDATION_ERROR` |
| 사용자·방 단위 전송 제한 초과 | 429 | `RATE_LIMIT_EXCEEDED` |
| 세션 또는 전송 제한 상태 저장소를 확인할 수 없음 | 503 | `SERVICE_UNAVAILABLE` (전송 제한 장애는 저장 전, `Retry-After` 없음) |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

### CHAT-02 메시지 이력 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/rooms/{roomId}/chat/messages` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `ChatMessagePage` |

#### Query Parameters

| 이름 | 타입 | 필수 | 기본값 | 검증·의미 |
|---|---|:---:|---:|---|
| `beforeMessageId` | integer | N | 없음 | 1 이상의 메시지 ID. 해당 ID보다 이전 메시지를 조회 |
| `size` | integer | N | `50` | 1~100. 최신 메시지부터 반환 |

`beforeMessageId`가 없으면 최신 구간을 반환한다. 클라이언트는 응답의 `nextBeforeMessageId`로 과거 구간을 반복 조회하며, 메시지 ID로 중복을 제거한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 주최자·현재 `ACTIVE` 참가자가 아니거나 방이 `CANCELED`·`FINISHED`임 | 403 | `FORBIDDEN` |
| 대상 ROOM 보정의 낙관 락 재시도 소진 | 409 | `ROOM_CONCURRENT_MODIFICATION` |
| 경로·커서·크기 검증 실패 | 400 | `VALIDATION_ERROR` |
| 세션 상태 저장소를 확인할 수 없음 | 503 | `SERVICE_UNAVAILABLE` |

### CHAT-03 실시간 메시지 구독

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/rooms/{roomId}/chat/ws` WebSocket Upgrade |
| 인증 / CSRF | 필요 / 불필요 |
| handshake | 기존 `JSESSIONID` 세션과 허용된 `Origin` 검증 |
| 성공 | `101 Switching Protocols`, 서버 발신 텍스트 프레임의 JSON `ChatMessageEvent` |

#### Query Parameters

| 이름 | 타입 | 필수 | 기본값 | 의미 |
|---|---|:---:|---:|---|
| `afterMessageId` | integer | N | 없음 | 연결 시 이 ID보다 큰 커밋 메시지부터 누락분을 전달한 뒤 실시간 대기 |

`ChatMessageEvent.eventId`는 `messageId`와 같다. 연결이 끊기면 클라이언트는 마지막 이벤트 ID를 `afterMessageId`로 사용해 재연결한다. 서버는 누락 메시지를 `messageId ASC`로 먼저 전달하고, 복구 중 도착한 새 이벤트를 버퍼링·중복 제거한 뒤 실시간 전달로 전환한다.

WebSocket은 P1에서 수신 전용이다. 클라이언트가 애플리케이션 메시지 프레임을 보내면 서버는 처리하지 않고 정책 위반으로 연결을 종료한다. 메시지 저장은 HTTP POST와 PostgreSQL이 담당하며, WebSocket 전달 실패는 저장된 이력을 삭제하거나 롤백하지 않는다. 방 상태·관계 또는 세션이 바뀌어 접근 권한을 잃으면 서버는 정책 위반 close frame으로 연결을 종료하고 새 이벤트를 전달하지 않는다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 주최자·현재 `ACTIVE` 참가자가 아니거나 방이 `CANCELED`·`FINISHED`임 | 403 | `FORBIDDEN` |
| handshake 전 대상 ROOM 보정의 낙관 락 재시도 소진 | 409 | `ROOM_CONCURRENT_MODIFICATION` |
| 경로·커서 검증 실패 | 400 | `VALIDATION_ERROR` |
| 허용되지 않은 `Origin` | 403 | `FORBIDDEN` |
| Upgrade 전에 세션 상태 저장소를 확인할 수 없음 | 503 | `SERVICE_UNAVAILABLE` |

### CHAT-07 채팅방 읽음 처리

> **도입 단계: P2** · **기능: CHAT-07** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 제공**

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/rooms/{roomId}/chat/read` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | `200 OK`, `data`: `ChatRoomReadStateResponse` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `roomId` | integer | Y | 1 이상의 방 ID |

#### Request Body — ChatRoomReadRequest

~~~json
{
  "upToMessageId": 1042
}
~~~

| 필드 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `upToMessageId` | integer | Y | 1 이상. 요청자가 방금 확인한 화면상 최신 메시지의 `messageId` |

`upToMessageId`가 그 방에 실제로 존재하는 메시지 ID를 넘는 값이면 `400 VALIDATION_ERROR`다. 이미 저장된 커서보다 작거나 같은 값으로 다시 호출해도 오류가 아니며, 커서는 후퇴하지 않고 현재 값을 그대로 반환한다. `CHAT_ROOM_READ_STATES`는 사용자×방 첫 호출에서 생성한다.

#### Response Body — ChatRoomReadStateResponse

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 대상 방 ID |
| `lastReadMessageId` | integer | Y | N | 갱신 뒤(또는 이미 그 이상이라 변경 없음) 커서 값 |
| `updatedAt` | string(date-time) | Y | N | 이번 요청이 커서를 실제로 전진시켰으면 그 시각, 변경이 없었으면 이전 갱신 시각 |

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 주최자·현재 `ACTIVE` 참가자가 아니거나 방이 `CANCELED`·`FINISHED`임 | 403 | `FORBIDDEN` |
| 대상 ROOM 보정의 낙관 락 재시도 소진 | 409 | `ROOM_CONCURRENT_MODIFICATION` |
| `upToMessageId`가 없거나 그 방의 메시지가 아님 | 400 | `VALIDATION_ERROR` |
| 세션 상태 저장소를 확인할 수 없음 | 503 | `SERVICE_UNAVAILABLE` |
| CSRF 토큰 오류 | 403 | `CSRF_TOKEN_INVALID` |

### CHAT-07 내 미읽음 채팅방 요약

> **도입 단계: P2** · **기능: CHAT-07** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/users/me/chat/unread-summary` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `UnreadChatSummaryResponse` |

상단 채팅 아이콘 배지 전용 경량 조회다. 채팅 목록 전체(`GET /api/users/me/rooms`)를 다시 불러오지 않고 배지 값만 갱신할 때 사용한다.

#### Response Body — UnreadChatSummaryResponse

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `unreadRoomCount` | integer | Y | N | 미읽음 메시지가 1건 이상인 채팅방 수. `chatAvailable = false`인 방은 세지 않는다 |

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 세션 상태 저장소를 확인할 수 없음 | 503 | `SERVICE_UNAVAILABLE` |

### CHAT-08 채팅 목록 실시간 갱신 계약

> **도입 단계: P2** · **기능: CHAT-08** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 백엔드 제공(`#918`), 프런트엔드 소비 구현 중(`#919`)**
>
> 백엔드 엔드포인트·이벤트는 구현·자동 검증을 완료했다(`#918`). 프런트엔드 구독·재조회는 아직 구현 중이다(`#919`). 제품 상태는 [P2 기능 상태의 `CHAT-08`](p2/README.md#기능별-현재-상태)에서만 판정한다.

제품 규칙은 [CHAT-08 명세](p2/chat.md#chat-08-채팅-목록-실시간-갱신), 채널 구조 선택 이유는 [ADR-0082](adr/chat/0082-chat-list-per-user-realtime-channel.md)를 따른다. 이 계약은 기존 방별 WebSocket([CHAT-03](#chat-03-실시간-메시지-구독))을 대체하지 않고 병렬로 추가한다.

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/users/me/chat/ws` WebSocket Upgrade |
| 인증 / CSRF | 필요 / 불필요 |
| handshake | 기존 `JSESSIONID` 세션 검증. 방 단위 권한 검사는 하지 않는다 |
| 성공 | `101 Switching Protocols`, 서버 발신 텍스트 프레임의 JSON [ChatRoomUpdatedEvent](#440-chatroomupdatedevent) |

- 이 채널은 서버 발신 전용이다. 클라이언트가 애플리케이션 메시지 프레임을 보내면 서버는 처리하지 않고 정책 위반으로 연결을 종료한다.
- 연결된 사용자가 참가 중인 어느 방에서든 메시지가 커밋되면, 그 방의 현재 참가자 전원의 연결에 [ChatRoomUpdatedEvent](#440-chatroomupdatedevent)를 전송한다. 발신자 본인도 참가자이므로 함께 받는다.
- 이 연결은 방 접근 권한을 다시 확인하지 않는다. 어떤 방의 이벤트를 받을지는 그 방의 현재 참가자 목록 조회로만 결정하며, 참가자가 아니게 된 방의 이벤트는 더 이상 받지 않는다.
- 인스턴스 간 전달은 [ADR-0033](adr/chat/0033-postgresql-source-after-commit-delivery.md)의 기존 Redis 채널을 재사용한다. 신호는 at-most-once이며, 유실·중복·순서 역전은 클라이언트의 [CHAT-07](#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태-계약) 재조회로 수렴한다.
- 연결이 끊어졌다가 재연결되면 서버는 별도 catch-up 이력을 전달하지 않는다. 클라이언트가 재연결 시점에 한 번 채팅 목록을 재조회해 단절 구간의 갱신을 복구한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 허용되지 않은 `Origin` | 403 | `FORBIDDEN` |
| Upgrade 전에 세션 상태 저장소를 확인할 수 없음 | 503 | `SERVICE_UNAVAILABLE` |

## MATCH-01 실시간 파티 매칭 API

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**
>
> 이 절의 모든 HTTP·WebSocket 경로·요청·응답·enum은 승인된 목표 계약이며 현재 제공 기능이 아니다. 제품 상태는 [P2 기능 상태의 `MATCH-01`](p2/README.md#기능별-현재-상태)에서 별도로 판정한다.

MATCHING은 매칭 요청·제안·성공 파티와 그 접근 관계를 소유한다. 현재 상태 조회는 PostgreSQL 정본을 조합해 반환하며, WebSocket·그 밖의 실시간 이벤트는 정본이 아니다. 따라서 재접속, 이벤트 유실·중복·순서 역전 또는 서버 재기동 뒤 클라이언트는 반드시 이 조회 결과로 화면을 복구한다. 저장 구조·제약·인덱스는 [P2 MATCH 저장 계약](ERD.md#p2-match-저장-계약), 모듈 흐름·재시도 내부는 [P2 MATCH 모듈 계약](ARCHITECTURE.md#p2-match-모듈-계약), 기술 선택 근거는 [MATCH ADR](adr/matching/README.md)이 소유한다.

모든 MATCH HTTP API는 인증된 현재 사용자만 호출한다. `GET`은 CSRF가 필요 없고 `POST`·`PUT`·`DELETE`는 세션과 CSRF가 필요하다. 유효 세션이 없으면 CSRF보다 `UNAUTHENTICATED`를 먼저 반환한다. MATCH WebSocket handshake는 세션과 허용된 `Origin`을 검증하며 CSRF는 필요 없다.

### MATCH 멱등성 키 공통 계약

`POST /api/matches/requests`와 `POST /api/matches/proposals/{proposalId}/responses`는 `Idempotency-Key` 헤더가 필수다. 키는 앞뒤 공백 없이 1~100자의 ASCII printable 문자다. 키의 범위는 인증된 사용자별 24시간이며, 같은 사용자는 이 기간에 같은 키를 다른 MATCH 명령에 재사용할 수 없다.

명령의 의미는 operation(`MATCH_REQUEST_CREATE` 또는 `MATCH_PROPOSAL_RESPONSE`), 경로의 `proposalId`, 검증을 통과한 request body 값으로 정한다. 같은 사용자·키·의미의 재시도는 업무 상태를 다시 전이하지 않고 항상 `200 OK`와 **그 시점의 최신 `CurrentMatchStateResponse`**를 반환한다. 같은 사용자·키에 operation·경로·body 중 하나라도 다르면 `409 IDEMPOTENCY_KEY_CONFLICT`이며 상태를 바꾸지 않는다. 24시간이 지나면 키 보장은 끝나며, 이전 기록이 아직 purge되지 않았더라도 새 명령은 같은 트랜잭션의 operation time으로 만료를 판정해 그 키의 기록을 새 의미로 원자 교체할 수 있다. 만료 전 기록은 batch purge 여부와 관계없이 충돌·재사용 규칙을 그대로 적용한다.

두 명령은 세션, CSRF, 헤더·경로·body 형식, 저장된 멱등성 결과, 현재 업무 상태 순서로 판정한다. 따라서 첫 유효 명령 뒤 제안이 종료되었더라도 같은 키·의미의 재시도는 "현재 열린 제안 없음" 오류가 아니라 최신 상태를 반환한다.

### MATCH-01 현재 상태 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/matches/current` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `CurrentMatchStateResponse` |

응답은 한 사용자에게 현재 하나인 화면 상태만 반환한다. `WAITING`은 후보 부재로 대기 중인 요청, `PROPOSED`는 응답 기한 안의 열린 제안, `PAUSED`는 본인의 미응답으로 다시 찾기를 기다리는 요청, `PREPARING`은 [제품이 정한 기한](p2/matching.md#성공-파티-채팅) 안의 채팅 준비, `ACTIVE`는 채팅 handoff 상태다. `PREPARING`에는 채팅 경로나 party ID를 반환하지 않으며, `ACTIVE`일 때만 `chat`에 연결 정보를 담는다. 현재 대상이 없으면 `operationTime`을 제외한 `data` 필드가 `null`이다.

조회는 due 상태를 임의로 선택해 숨기지 않는다. due 상태 보정, PostgreSQL `operationTime` 고정, 단일 SQL snapshot과 bounded retry의 실행 계약은 [아키텍처의 MATCH 현재 상태 snapshot](ARCHITECTURE.md#p2-match-현재-상태-snapshot-계획미구현)을 따른다. API는 보정이 끝난 하나의 안정적인 현재 상태만 반환하며, 실행 계약 안에서 안정적인 snapshot을 확보하지 못하면 `MATCH_CURRENT_STATE_NOT_STABLE`을 반환한다.

### MATCH-01 매칭 요청 등록

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/matches/requests` |
| 인증 / CSRF | 필요 / 필요 |
| 필수 헤더 | `Idempotency-Key` |
| 성공 | 최초 유효 등록은 `201 Created`, 같은 키·같은 의미 재시도는 `200 OK`; `data`: `CurrentMatchStateResponse` |

#### Request Body — MatchRequestCreateRequest

~~~json
{
  "minPlayers": 3,
  "maxPlayers": 4
}
~~~

| 필드 | 타입 | 필수 | nullable | 검증 |
|---|---|:---:|:---:|---|
| `minPlayers` | integer | Y | N | `1` 이상 `32767` 이하이며 `maxPlayers` 이하 |
| `maxPlayers` | integer | Y | N | `minPlayers` 이상 `32767` 이하 |

게임과 플랫폼은 요청·응답·매칭 후보 조건에 포함하지 않는다. 두 인원 값이 유효하면 게임 카탈로그를 조회하지 않고 요청을 등록한다. 현재 후보가 없거나 다른 요청과 인원 범위가 겹치지 않으면 `WAITING` 상태로 성공한다.

한 사용자는 `WAITING`·`PROPOSED`·`PAUSED` 중 하나의 비종료 매칭 요청과 `PREPARING` 또는 아직 명시적으로 나가지 않은 `ACTIVE` 성공 파티 접근 관계를 동시에 가질 수 없다. 둘 중 하나가 있으면 새 등록은 `MATCH_REQUEST_ALREADY_ACTIVE`다. 명시적으로 나갔거나 실제 `CLOSED`가 된 성공 파티 관계는 새 요청 등록을 막지 않는다.

저장하는 `minPlayers`·`maxPlayers`는 본문에 입력한 희망 범위 그대로다. 후보 선별은 연결된 요청들의 저장 범위 교집합을 사용하며, 실제 `partySize`는 그 교집합의 하한으로 정한다. 매칭이 확정된 뒤 참가자들은 전용 채팅에서 원하는 게임과 진행 방법을 직접 정한다.

### MATCH-01 매칭 요청 취소

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/matches/requests/me` |
| 인증 / CSRF | 필요 / 필요 |
| Request Body / Idempotency-Key | 없음 / 없음 |
| 성공 | `200 OK`, `data`: `CurrentMatchStateResponse` |

경로와 `DELETE`만으로 본인의 비종료 매칭 요청을 없애는 목표 상태가 결정된다. `WAITING`·`PROPOSED`·`PAUSED` 요청을 취소하며, `PROPOSED` 요청의 취소는 [아키텍처의 Proposal Terminal Executor](ARCHITECTURE.md#p2-match-제안채팅-복구-흐름-계획미구현)에 따라 같은 열린 제안의 `REQUEUE`·`CANCEL`·기한 만료·마지막 `ACCEPT`와 하나의 종결 결과를 경쟁한다. 이 취소가 종결 승자가 되면 다른 사용자의 제안 종료·자동 재대기 규칙도 같은 트랜잭션에서 적용한다. 이미 취소되어 대상이 없으면 반복 요청도 `200 OK`와 모든 필드가 `null`인 현재 상태로 수렴한다. `PREPARING`·`ACTIVE` 성공 파티는 이 API로 취소·퇴장·재매칭하지 않으며 `MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE`를 반환한다. `PAUSED` 사용자가 다시 찾으려면 이 목표 상태 `DELETE` 뒤 새 요청을 등록한다.

### MATCH-01 제안 응답

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/matches/proposals/{proposalId}/responses` |
| 인증 / CSRF | 필요 / 필요 |
| 필수 헤더 | `Idempotency-Key` |
| 성공 | 최초 유효 응답과 같은 키·같은 의미 재시도 모두 `200 OK`, `data`: `CurrentMatchStateResponse` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `proposalId` | integer | Y | 1 이상의 제안 ID |

#### Request Body — MatchProposalResponseRequest

~~~json
{
  "action": "ACCEPT"
}
~~~

| 필드 | 타입 | 필수 | nullable | 검증 |
|---|---|:---:|:---:|---|
| `action` | MatchProposalResponseAction | Y | N | `ACCEPT`, `REQUEUE`, `CANCEL` 중 하나 |

`respondBy` 이전의 본인 열린 제안에 대해서만 첫 유효 응답 하나를 기록한다. 마지막이 아닌 `ACCEPT`는 `PROPOSED`와 `myResponse = ACCEPTED`를 반환한다. 마지막 `ACCEPT`, `REQUEUE`, `CANCEL`, `PROPOSED` 요청의 `DELETE`, 응답 기한 만료가 하나의 종결 결과로 수렴하는 실행 경계는 [아키텍처의 Proposal Terminal Executor](ARCHITECTURE.md#p2-match-제안채팅-복구-흐름-계획미구현)가 소유한다. 종결 결과별 `PREPARING`·`ACTIVE`·`WAITING`·`PAUSED`·취소 전이, 자동 재대기·우선순위와 미응답 정책은 [MATCH-01 후보 파티와 제안](p2/matching.md#후보-파티와-제안)을 따르며, 이 API는 그 규칙으로 확정된 최신 `CurrentMatchStateResponse`를 반환한다.

응답 기한이 지났거나, 다른 사용자의 유효 응답으로 제안이 끝났거나, 이 사용자가 이미 다른 키로 첫 유효 응답을 보냈으면 새 명령은 `MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE`다. 이 오류는 현재 제안 외의 과거 제안에 응답할 수 없다는 의미이며, 같은 멱등키 재시도에는 적용하지 않는다.

**수락 응답을 잃어버린 경우:** 클라이언트는 새 키나 새 `ACCEPT`를 보내지 않고 같은 `proposalId`, body, `Idempotency-Key`로 재시도한다. 서버는 두 번째 수락·두 번째 성공 파티를 만들지 않고 `200 OK`와 최신 `PROPOSED`·`PREPARING`·`ACTIVE` 또는 채팅 준비 실패 뒤 `WAITING` 상태를 반환한다. 재시도할 수 없거나 이벤트가 유실됐으면 `GET /api/matches/current`으로 같은 상태를 복구한다.

### MATCH 채팅 공통 계약

MATCH 성공 파티와 접근 관계는 MATCHING이 판정하며, 채팅은 Party가 `ACTIVE`이고 현재 사용자의 참가자 접근 관계가 아직 나가지 않은 경우에만 메시지 저장·이력·실시간 전달을 제공한다. 따라서 `/api/rooms/{roomId}/chat/**`와 ROOM 주최자·참가자 접근 규칙은 적용하지 않는다. `PREPARING` 중에는 모든 MATCH 채팅 경로를 허용하지 않고 `MATCH_CHAT_NOT_ACTIVE`를 반환하며, 클라이언트는 현재 상태 조회의 `preparing`으로 화면을 유지한다.

메시지는 HTTP로 저장하고 WebSocket으로 수신한다. 커서 기반 이력·재연결 원칙과 HTTP 저장/WebSocket 수신 방식은 [ADR-0032](adr/chat/0032-http-send-websocket-receive.md)를 따른다. 이력·전송·구독은 `ACTIVE`인 현재 성공 파티 관계를 요청과 handshake 때마다 다시 확인하고, `CLOSED` 뒤에는 조회·전송·구독을 허용하지 않는다. MATCH 채팅은 `closesAt` 도달 또는 마지막 현재 사용자의 명시적 나가기로 `CLOSED`가 되며, `purgeAfter`가 되면 URL 텍스트를 포함한 메시지·성공 파티·접근 관계를 삭제한다. `closesAt`·`purgeAfter` 계산과 기존 ROOM 채팅과의 보존 차이는 [MATCH-01 성공 파티 채팅](p2/matching.md#성공-파티-채팅)을 따른다.

### MATCH-01 매칭 채팅 메시지 전송

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/matches/parties/{partyId}/chat/messages` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | 최초 저장은 `201 Created`, 같은 `clientMessageId`·같은 정규화 본문 재시도는 `200 OK`; `data`: `MatchChatMessage` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `partyId` | integer | Y | 1 이상의 성공 파티 ID |

#### Request Body — MatchChatMessageSendRequest

~~~json
{
  "clientMessageId": "01JMATCH-0001",
  "content": "같이 플레이해요."
}
~~~

| 필드 | 타입 | 필수 | nullable | 검증 |
|---|---|:---:|:---:|---|
| `clientMessageId` | string | Y | N | 1~100자. 같은 파티·같은 사용자에서 전송 재시도의 기준 |
| `content` | string | Y | N | CRLF를 LF로 정규화하고 LF 외 제어문자를 거절한 뒤, 앞뒤 공백 제거 후 1~500자의 일반 텍스트 |

같은 사용자·파티의 같은 `clientMessageId`에 다른 정규화 본문을 보내면 `VALIDATION_ERROR`다. 시스템 메시지는 이 API로 만들 수 없다. 외부 URL은 `content` 안의 일반 텍스트로만 공유하며 별도 링크 생성·조회 API, 링크 미리보기, 링크 유효성 검증이나 별도 링크 저장 행을 만들지 않는다([ADR-0064](adr/matching/0064-match-chat-url-text-storage.md)). URL 텍스트를 포함한 메시지 본문은 로그와 metric label에 기록하지 않는다.

#### MATCH 채팅 전송 제한

MATCH의 사용자 메시지는 P1 채팅과 같은 Redis 전송 제한을 쓰되, MATCH 전용 key namespace에서 사용자 bucket `5건/10초`와 Party bucket `30건/10초`을 함께 적용한다. 사용자 bucket은 모든 MATCH Party의 전송을 합산하고 Party bucket은 같은 Party의 모든 현재 참가자 전송을 합산한다. 이는 전송 남용 제한일 뿐 MATCH 후보 선점·응답·복구의 Redis 업무 락이 아니다.

공통 인증·CSRF, Party 존재·현재 접근·`ACTIVE` 상태, 본문 정규화와 같은 `clientMessageId`의 멱등 재전송 판정을 통과한 **신규** 전송에만 두 bucket을 적용한다. 두 bucket은 10초 고정 창이며 TTL을 연장하지 않고, 허용 확인과 증가는 원자적으로 처리한다. 하나라도 초과하면 둘 다 증가시키지 않는다. 검증 실패·권한 거부·이미 저장된 같은 정규화 본문의 멱등 재전송은 quota를 소비하지 않는다.

초과하면 `429 RATE_LIMIT_EXCEEDED`와 초과 bucket의 남은 TTL을 올림한 `Retry-After`를 반환한다. 둘 다 초과하면 더 큰 값을 사용하며 `Retry-After`는 429에만 포함한다. Redis 제한 상태를 확인할 수 없거나 결과가 불명확하면 메시지를 저장하기 전에 `503 SERVICE_UNAVAILABLE`로 실패하고, 인메모리 fallback·자동 재시도·`Retry-After`를 허용하지 않는다.

### MATCH-01 매칭 채팅 이력 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/matches/parties/{partyId}/chat/messages` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `MatchChatMessagePage` |

| Query parameter | 타입 | 필수 | 기본값 | 검증·의미 |
|---|---|:---:|---|---|
| `beforeMessageId` | integer | N | 없음 | 1 이상의 메시지 ID. 해당 ID보다 이전 메시지를 조회 |
| `size` | integer | N | `50` | 1~100. 최신 메시지부터 반환 |

`beforeMessageId`가 없으면 최신 구간을 반환한다. 클라이언트는 `nextBeforeMessageId`를 사용해 과거 구간을 조회하고 `messageId`로 중복을 제거한다.

### MATCH-01 매칭 채팅 실시간 구독

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/matches/parties/{partyId}/chat/ws` WebSocket Upgrade |
| 인증 / CSRF | 필요 / 불필요 |
| handshake | 기존 `JSESSIONID` 세션, MATCH `ACTIVE` 접근 관계와 허용된 `Origin` 검증 |
| 성공 | `101 Switching Protocols`, 서버 발신 JSON 텍스트 프레임의 `MatchChatMessageEvent` |

| Query parameter | 타입 | 필수 | 기본값 | 검증·의미 |
|---|---|:---:|---|---|
| `afterMessageId` | integer | N | 없음 | 이 ID보다 큰 커밋 메시지를 먼저 복구한 뒤 실시간 구독 |

WebSocket은 수신 전용이다. 클라이언트가 애플리케이션 메시지 프레임을 보내면 처리하지 않고 정책 위반으로 연결을 종료한다. 연결이 끊기면 마지막 `eventId`를 `afterMessageId`로 사용하며, 접근 관계·채팅 상태·세션이 바뀌면 연결을 종료한다.

### MATCH-01 성공 파티 나가기

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/matches/parties/{partyId}/participants/me` |
| 인증 / CSRF | 필요 / 필요 |
| Request Body / Idempotency-Key | 없음 / 없음 |
| 성공 | `200 OK`, `data`: `CurrentMatchStateResponse` |

이 명령은 `ACTIVE` 성공 파티에서 사용자가 **명시적으로** 나가겠다는 뜻이다. 브라우저 종료, WebSocket 연결 끊김, 서버 재시작은 나가기로 해석하지 않으므로 재접속 복구 권한을 잃지 않는다. Executor는 Party를 잠근 뒤 아직 나가지 않은 본인 접근 관계만 목표 상태 `나감`으로 바꾸고, 남은 현재 접근 관계가 없으면 같은 트랜잭션에서 Party를 `ACTIVE → CLOSED`로 전이한다. 마지막 사용자가 아닌 경우 Party와 다른 사용자의 채팅은 계속 `ACTIVE`이며 자동 충원·자동 재매칭을 하지 않는다.

이미 나갔거나 마지막 퇴장으로 `CLOSED`가 된 자신의 Party에 같은 `DELETE`를 반복해도 새 상태 전이나 보존 기한을 만들지 않고 `200 OK`와 최신 현재 상태로 수렴한다. 명시적으로 나간 사용자는 해당 Party의 채팅 접근을 즉시 잃고, 현재 매칭 상태가 없으면 새 매칭 요청을 등록할 수 있다. `PREPARING` Party에서는 아직 채팅에 나갈 수 없으므로 `MATCH_PARTY_LEAVE_NOT_AVAILABLE`를 반환한다. 본인 관계가 없거나 [제품 보존 기한](p2/matching.md#성공-파티-채팅)에 따른 물리 삭제 뒤에는 `FORBIDDEN`, 존재하지 않는 Party는 `MATCH_PARTY_NOT_FOUND`를 반환한다.

### MATCH-01 차단 목록 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/matches/blocks` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `PageResponse<MatchBlockListItem>` |

| Query parameter | 타입 | 필수 | 기본값 | 검증 |
|---|---|:---:|---|---|
| `page` | integer | N | `0` | 0 이상 |
| `size` | integer | N | `10` | 1~100 |

### MATCH-01 사용자 차단

| 항목 | 값 |
|---|---|
| Method / Path | `PUT /api/matches/parties/{partyId}/participants/{participantRef}/block` |
| 인증 / CSRF | 필요 / 필요 |
| Request Body / Idempotency-Key | 없음 / 없음 |
| 성공 | `200 OK`, `data`: `MatchBlockListItem` |

| Path variable | 타입 | 검증 |
|---|---|---|
| `partyId` | integer | 1 이상의 MATCH Party ID |
| `participantRef` | string | 같은 Party의 `MatchPartyMember`에서 받은 opaque reference |

`partyId`는 요청자가 현재 속했거나 보존 기간 안에 속했던 MATCH Party이고, `participantRef`는 그 Party의 공개 `MatchPartyMember`에서 받은 opaque 값이다. 서버는 Party-scoped reference를 같은 Party 안의 사용자로 해석하며 사용자 ID를 요청으로 받거나 응답에 노출하지 않는다. 본인 participantRef는 차단할 수 없다. 이미 차단된 관계도 같은 목표 상태로 `200 OK`에 수렴하고, 차단은 이미 열린 제안·성공 파티를 바꾸지 않으며 이후 새 후보 구성에서만 두 사용자를 함께 넣지 않는다.

### MATCH-01 차단 해제

| 항목 | 값 |
|---|---|
| Method / Path | `DELETE /api/matches/blocks/{blockId}` |
| 인증 / CSRF | 필요 / 필요 |
| Request Body / Idempotency-Key | 없음 / 없음 |
| 성공 | `200 OK`, `data`: `{}` |

`blockId`는 본인의 `MatchBlockListItem`에서 받은 차단 관계 ID다. 서버는 현재 사용자가 소유한 일치 관계만 삭제한다. 일치 관계가 없으면 이미 해제됨·존재하지 않음·다른 사용자 소유를 구분하지 않고 모두 `200 OK`와 `{}`로 수렴하며, 다른 사용자의 차단 관계는 변경하지 않는다. 따라서 응답 유실 뒤 같은 `DELETE`를 반복해도 별도 해제 이력 없이 같은 목표 상태를 반환하고 다른 사용자의 차단 상태도 노출하지 않는다.

### MATCH-01 신고 접수

| 항목 | 값 |
|---|---|
| Method / Path | `POST /api/matches/parties/{partyId}/reports` |
| 인증 / CSRF | 필요 / 필요 |
| 성공 | 최초 접수는 `201 Created`, 같은 신고자·피신고자 조합의 보존 중 재신고는 `200 OK`; `data`: `MatchReportReceipt`. 보존 규칙은 [MATCH-01 신고와 차단](p2/matching.md#신고와-차단)을 따름 |

| Path variable | 타입 | 검증 |
|---|---|---|
| `partyId` | integer | 1 이상의 MATCH Party ID |

`partyId`와 `participantRef`는 요청자가 현재 속했거나 보존 기간 안에 속했던 MATCH Party에서 함께 얻은 값이어야 한다. 서버는 Party-scoped reference를 내부 사용자로 해석하고, 사용자 ID를 요청으로 받거나 응답·로그에 노출하지 않는다. 대상이 그 Party의 다른 참가자가 아니면 `MATCH_PARTICIPANT_NOT_FOUND` 또는 `FORBIDDEN`을 반환한다.

#### Request Body — MatchReportCreateRequest

~~~json
{
  "participantRef": "part_opaque_ref",
  "reason": "ABUSE_OR_HARASSMENT"
}
~~~

| 필드 | 타입 | 필수 | nullable | 검증 |
|---|---|:---:|:---:|---|
| `participantRef` | string | Y | N | 같은 `partyId`에서 받은 다른 참가자의 opaque reference |
| `reason` | MatchReportReason | Y | N | 고정 사유 5개 중 하나 |

같은 신고자·피신고자 조합에는 [제품이 정한 보존 기간](p2/matching.md#신고와-차단) 동안 하나의 receipt만 있다. `purge_after > operationTime`인 동안의 재신고는 사유가 달라도 새 행을 만들거나 기존 사유·접수 시각을 바꾸지 않고 `200 OK`와 `alreadyReceived = true`인 기존 receipt를 반환한다. `purge_after <= operationTime`이면 이전 행이 batch purge되지 않았더라도 같은 트랜잭션에서 사유·접수 시각·`purge_after`를 새 신고로 원자 교체하고 `201 Created`와 `alreadyReceived = false`를 반환한다. 신고는 차단을 자동 생성하지 않으며 현재 제안·성공 파티·이후 후보에 영향을 주지 않는다.

## 10. 오류 코드

오류 코드는 클라이언트가 실패 원인을 식별하는 안정적인 외부 계약이다.

- `code`는 `UPPER_SNAKE_CASE`를 사용하고 전체 API에서 유일하다. 같은 코드를 다른 의미나 HTTP 상태로 재사용하지 않는다.
- `status`는 실제 HTTP 상태 및 실패 응답 본문의 `status`와 일치한다.
- `message`는 아래 카탈로그의 한국어 기본 메시지를 사용한다. 클라이언트는 메시지가 아니라 `code`로 분기한다.
- 코드의 소유 도메인은 호출한 엔드포인트가 아니라 실패 규칙의 소유자를 기준으로 정한다. 예를 들어 방 생성 중 선택한 게임이 없으면 게임 도메인의 `GAME_NOT_FOUND`를 반환한다.
- 오류 코드를 추가하거나 의미·HTTP 상태·기본 메시지를 변경하면 이 카탈로그와 [엔드포인트별 오류 매트릭스](#11-부록-엔드포인트별-오류-매트릭스)를 함께 갱신한다.

### 10.1 공통 오류

여러 도메인의 HTTP 경계에서 같은 의미로 사용하는 실패다.

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `VALIDATION_ERROR` | 400 | 요청값 검증에 실패했습니다. | 입력값의 필수 여부·형식·길이·범위 검증 실패 |
| `UNAUTHENTICATED` | 401 | 인증이 필요합니다. | 세션 쿠키가 없거나 세션이 만료·무효화됨 |
| `FORBIDDEN` | 403 | 요청을 수행할 권한이 없습니다. | 인증은 됐지만 요청한 작업을 수행할 권한이 없음 |
| `CSRF_TOKEN_INVALID` | 403 | CSRF 토큰이 없거나 유효하지 않습니다. | 상태 변경 요청의 CSRF 토큰이 없거나 유효하지 않음 |
| `RESOURCE_NOT_FOUND` | 404 | 요청한 리소스를 찾을 수 없습니다. | 요청 경로에 대응하는 핸들러 또는 정적 리소스가 없음 |
| `METHOD_NOT_ALLOWED` | 405 | 허용되지 않은 HTTP 메서드입니다. | 요청 경로는 존재하지만 HTTP 메서드를 지원하지 않음 |
| `NOT_ACCEPTABLE` | 406 | 요청한 응답 미디어 타입을 제공할 수 없습니다. | `Accept` 헤더와 호환되는 응답 미디어 타입이 없음 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 요청 미디어 타입입니다. | `Content-Type`이 요청 본문 계약과 호환되지 않거나, PART-04 대기 API에 금지된 `Content-Type`·`Transfer-Encoding`·실제 본문이 포함됨 |
| `INTERNAL_SERVER_ERROR` | 500 | 서버 오류가 발생했습니다. | 처리하지 않은 예외로 요청을 완료하지 못함 |
| `SERVICE_UNAVAILABLE` | 503 | 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요. | 요청 처리에 필수인 세션·인증 요청 제한·전송 제한 또는 AI 비용·사용량 예약 상태 저장소를 확인할 수 없음 |

`METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` 응답은 Spring MVC 예외가 제공하는 `Allow`, `Accept`, `Accept-Patch` 등의 프로토콜 헤더가 있으면 그대로 포함한다.

`SERVICE_UNAVAILABLE`의 현재 적용 범위는 [채팅 API](#채팅-공통-계약)의 세 엔드포인트, `POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/assistant/recommendations`의 provider 기반 추천 경로다. `local`과 `production`에서 인증 요청 제한 Redis를 확인할 수 없으면 회원가입·로그인은 사용자 조회·생성과 비밀번호 해시 전에 이 코드를 반환한다. 채팅 요청이 Spring Session Redis의 세션 상태를 확인할 수 없으면 같은 코드를 반환하며, 메시지 전송은 세션 저장소가 정상이더라도 전송 제한 상태 저장소를 확인할 수 없으면 저장 전에 같은 코드를 반환한다. AI provider 기반 추천은 비용·사용량 예약 Redis를 확인할 수 없을 때 provider를 호출하지 않고 같은 코드를 반환한다. 정확 게임명 직접 조회는 이 예약 저장소를 읽지 않는다. 이 503에는 `Retry-After`를 포함하지 않으며 Redis 장애 시 인메모리 구현으로 자동 대체하지 않는다.

로그인·로그아웃과 그 밖의 현재 P0·P1 세션 사용 엔드포인트로 이 코드를 확장할지는 이 문서에서 아직 결정하지 않는다. P2 MATCH 채팅의 계획된 적용 범위는 [MATCH 채팅 API](#match-채팅-공통-계약)와 오류 매트릭스에만 적으며, 이 계약이 현재 제공 범위를 넓히지 않는다.

### 10.2 인증·회원 오류

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호가 일치하지 않습니다. | 로그인 이메일 또는 비밀번호가 일치하지 않음 |
| `EMAIL_ALREADY_EXISTS` | 409 | 이미 사용 중인 이메일입니다. | 회원가입 이메일이 이미 사용 중임(정규화된 값 기준) |
| `SOCIAL_PROVIDER_NOT_AVAILABLE` | 404 | 사용할 수 없는 소셜 로그인 제공자입니다. | 지원하지 않거나 현재 Client ID·Secret이 설정되지 않은 제공자로 연결 시작 |
| `SOCIAL_ACCOUNT_ALREADY_LINKED` | 409 | 해당 소셜 계정 제공자가 이미 연결되어 있습니다. | 로그인 사용자가 같은 제공자의 다른 연결을 시작함 |
| `RATE_LIMIT_EXCEEDED` | 429 | 요청 처리 한도를 초과했습니다. 잠시 후 다시 시도해 주세요. | 인증·채팅 등 요청 횟수 또는 비밀번호 해시 동시 실행 한도 초과 |

인증 요청의 `Retry-After` 계산은 [인증 요청 남용 제한](#인증-요청-남용-제한)을 따른다. 채팅 전송 제한은 [전송 제한 계약](#전송-제한-계약)과 [#288 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930)을 따른다.

### 10.3 게임 오류

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `GAME_NOT_FOUND` | 404 | 게임을 찾을 수 없습니다. | 요청한 게임이 없음 |

### 10.4 방 오류

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `ROOM_NOT_FOUND` | 404 | 방을 찾을 수 없습니다. | 방이 없거나, 권한 없는 사용자가 취소·종료 방 상세를 조회함 |
| `INVALID_ROOM_STATUS_TRANSITION` | 409 | 허용되지 않은 방 상태 변경입니다. | 현재 상태 또는 시간 조건에서 허용되지 않은 상태 전이 시도 |
| `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS` | 409 | 주최자 외 활성 참가자가 있는 방은 수정할 수 없습니다. | 주최자 외 `ACTIVE` 참가자가 있는 방 수정 시도 |
| `ROOM_CONCURRENT_MODIFICATION` | 409 | 방 정보가 동시에 변경되었습니다. 다시 시도해 주세요. | 같은 방의 동시 변경이 반복되어 요청을 완료하지 못함 |

방 상태를 보정하거나 변경하는 요청의 동시 변경은 다음 순서로 처리한다.

1. 서버가 최신 상태로 요청을 다시 시도한다.
2. 새 시도에서 업무 규칙 위반을 확인하면 해당 업무 오류를 반환한다.
3. 동시 변경으로 끝내 완료하지 못할 때만 `ROOM_CONCURRENT_MODIFICATION`을 반환한다.

대상 ROOM의 저장 상태를 보정하는 `GET /api/rooms/{roomId}`, `GET /api/rooms/{roomId}/waitlist/me`와 채팅 세 엔드포인트에서 이 오류를 받으면 클라이언트는 요청 또는 WebSocket handshake 전체를 다시 시도한다. `GET /api/rooms`와 `GET /api/users/me/rooms`는 고정 `requestTime`의 유효 상태를 읽는 무보정 snapshot 조회이므로 이 오류를 반환하지 않는다. 알고리즘은 [ADR-0055](adr/room/0055-room-query-effective-status-and-persistence-correction.md)와 [ADR-0005](adr/participation/0005-room-participation-optimistic-locking.md)를 따른다.

### 10.5 참가 오류

정확한 판정 순서는 [PART-01](#part-01-방-참가재참가), [PART-02](#part-02-참가-취소)와 [PART-04](#part-04-대기-등록재신청)의 오류 판정 순서를 따른다. 아래 발생 조건은 각 코드의 의미 요약이며 독립적인 충분조건이 아니다.

| code | HTTP | 기본 message | 대표 발생 조건 |
|---|---:|---|---|
| `PARTICIPATION_NOT_FOUND` | 404 | 현재 참가 정보를 찾을 수 없습니다. | 현재 `ACTIVE`인 본인 참가 관계가 없음 |
| `CAPACITY_EXCEEDED` | 409 | 모집 가능한 인원을 초과했습니다. | 방의 모집 인원을 초과하는 참가 시도 |
| `ROOM_NOT_RECRUITING` | 409 | 현재 모집 중인 방이 아닙니다. | 모집 중이 아니거나 참가 가능 시간이 지난 방 참가 시도 |
| `ALREADY_PARTICIPATING` | 409 | 이미 참가 중인 방입니다. | 요청자가 주최자이거나, 같은 방에 `ACTIVE` 참가 관계가 있는데 다시 참가 시도 |
| `WAITLIST_ENTRY_NOT_FOUND` | 404 | 현재 대기 정보를 찾을 수 없습니다. | 상태 조회에 반환할 본인 대기 이력이 없거나 취소할 본인 `WAITING` 관계가 없음 |
| `WAITLIST_NOT_AVAILABLE` | 409 | 현재 대기 신청할 수 없는 방입니다. | 직접 참가할 자리가 있거나 시작 시각에 도달했거나 방이 `CANCELED`·`FINISHED`여서 새 대기를 받을 수 없음 |

### 10.6 알림 오류

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `NOTIFICATION_NOT_FOUND` | 404 | 알림을 찾을 수 없습니다. | 요청한 알림이 없거나 본인 알림이 아니거나 보존 기간이 만료됨 |

다른 사용자의 알림에도 같은 코드를 반환하며 `FORBIDDEN`으로 구분하지 않는다.

### 10.7 매칭 오류

> **도입 단계: P2** · **기능: MATCH-01** · **API 계약 상태: 계약 준비 완료** · **제공 상태: 구현 예정**

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 동일한 멱등성 키를 다른 요청에 사용할 수 없습니다. | 같은 사용자·24시간 범위의 `Idempotency-Key`가 다른 operation·경로·body 의미로 이미 기록됨 |
| `MATCH_CURRENT_STATE_NOT_STABLE` | 409 | 매칭 현재 상태가 계속 변경 중입니다. 잠시 후 다시 시도해 주세요. | current-state read의 bounded snapshot 재시도 안에 due recovery와 상태 조합이 안정되지 않음 |
| `MATCH_REQUEST_ALREADY_ACTIVE` | 409 | 이미 진행 중인 매칭 요청이 있습니다. | `WAITING`·`PROPOSED`·`PAUSED` 요청이 있거나 `PREPARING`·아직 명시적으로 나가지 않은 `ACTIVE` 성공 파티 접근 관계가 있는 사용자가 새 요청을 등록함. 명시적으로 나갔거나 실제 `CLOSED` 뒤에는 성공 파티 관계만으로 이 오류를 반환하지 않음 |
| `MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE` | 409 | 현재 성공 파티는 매칭 요청으로 취소할 수 없습니다. | `PREPARING`·`ACTIVE` 성공 파티에 요청 취소를 시도함 |
| `MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE` | 409 | 현재 응답할 수 있는 매칭 제안이 없습니다. | 본인 열린 제안이 없거나 응답 기한이 지났거나 첫 유효 응답이 다른 키로 이미 처리됨 |
| `MATCH_PARTY_NOT_FOUND` | 404 | 성공 파티를 찾을 수 없습니다. | 요청한 성공 파티가 없음 |
| `MATCH_PARTY_LEAVE_NOT_AVAILABLE` | 409 | 현재 성공 파티에서 나갈 수 없습니다. | 채팅이 아직 `PREPARING`이어서 명시적 나가기 대상이 아님 |
| `MATCH_CHAT_NOT_ACTIVE` | 409 | 매칭 채팅이 아직 준비되지 않았습니다. | 본인 성공 파티 채팅이 `PREPARING`이거나 아직 `ACTIVE`가 아님 |
| `MATCH_PARTICIPANT_NOT_FOUND` | 404 | 매칭 참가자를 찾을 수 없습니다. | Party-scoped `participantRef`가 없거나 해당 Party의 참가자가 아님 |

MATCH 채팅 경로(`/api/matches/parties/{partyId}/chat/**`)는 성공 파티 접근을 `ACTIVE`·현재 참가자면 허용, `PREPARING`인 현재 참가자면 `MATCH_CHAT_NOT_ACTIVE`, 그 밖의 `CLOSED`·비참가자·이탈자·파티 미존재는 모두 `FORBIDDEN`으로 판정한다. 파티 존재 여부를 접근 권한이 없는 호출자에게 노출하지 않기 위해 미존재를 `FORBIDDEN`으로 흡수하므로, 채팅 경로는 `MATCH_PARTY_NOT_FOUND`를 반환하지 않는다.

`MATCH_PARTY_NOT_FOUND`는 나가기·차단·신고처럼 채팅 밖 성공 파티 경로에서만 반환한다. 이 경로들도 요청자가 해당 파티의 참가자임을 확인한 뒤에만 파티·참가자의 존재를 구분해 알리고, 확인하지 못하면 `FORBIDDEN`을 반환해 다른 성공 파티 상태를 노출하지 않는다.

### 10.8 AI 기능군 오류

> **도입 단계: P2** · **기능: AI-01·AI-02·AI-03** · **API 계약 상태: 계약 확정** · **제공 상태: AI-01a T1~T5와 AI-03a T1~T6 구현·검증 완료**

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `ASSISTANT_NOT_ENABLED` | 503 | AI 모임 도우미가 현재 활성화되지 않았습니다. | feature flag가 꺼져 있거나 provider enablement 전제 확인이 끝나지 않음 |
| `ASSISTANT_CONSENT_REQUIRED` | 403 | 외부 AI 처리 동의가 필요합니다. | 유효한 `GRANTED` 동의 없이 추천·초안·확인을 요청함 |
| `ASSISTANT_CONSENT_VERSION_MISMATCH` | 409 | 최신 동의문을 확인해야 합니다. | 승인되지 않은 동의문 버전을 `GRANT`로 보냄 |
| `ASSISTANT_INPUT_NOT_ALLOWED` | 400 | 외부 AI 처리에 허용되지 않는 입력입니다. | PII·secret·지원하지 않는 지시를 안전하게 처리할 수 없음 |
| `ASSISTANT_PROVIDER_UNAVAILABLE` | 503 | AI provider를 현재 사용할 수 없습니다. | provider를 호출하는 경로에서 provider·정책을 확인할 수 없거나 timeout·provider 429가 발생함. provider를 호출하지 않는 동의·초안 경로에는 사용하지 않음 |
| `ASSISTANT_PROVIDER_RESPONSE_INVALID` | 503 | AI provider 응답을 처리할 수 없습니다. | 강제 구조화 schema를 검증하지 못함 |
| `RATE_LIMIT_EXCEEDED` | 429 | 요청 처리 한도를 초과했습니다. 잠시 후 다시 시도해 주세요. | provider 기반 추천 경로에서 사용자별 KST 일일 10회 또는 월간 150회 quota에 도달함 |
| `ASSISTANT_COST_LIMIT_EXCEEDED` | 429 | AI 사용 비용 한도를 초과했습니다. | provider 기반 추천의 다음 USD `0.10` 고정 예약이 앱 전체 KST 월 `$5` hard cap을 초과함. `$4`에서 warning을 발행하며 빈 월에는 50번째 예약까지 허용 |
| `ASSISTANT_DRAFT_NOT_FOUND` | 404 | AI 초안을 찾을 수 없습니다. | 없는 초안 또는 현재 사용자 소유가 아닌 초안 |
| `ASSISTANT_DRAFT_EXPIRED` | 410 | AI 초안이 만료되었습니다. | 요청 시작 시각에 초안의 15분 유효 기간이 지남 |
| `ASSISTANT_DRAFT_CONFLICT` | 409 | AI 초안이 동시에 변경되었습니다. 다시 확인해 주세요. | 오래된 version, 다른 멱등키, 범위 밖 key 재사용, confirm 경합 또는 `CONFIRMED`·`DISCARDED` 초안 수정과 `CONFIRMED` 초안 폐기 시도 |

`ASSISTANT_PROVIDER_UNAVAILABLE`는 실제 provider를 자동 재시도하거나 다른 model로 조용히 대체하지 않는다. Redis 비용·사용량 예약을 확인할 수 없는 경우 provider 기반 추천은 공통 오류인 `SERVICE_UNAVAILABLE`을 반환하고 provider를 호출하지 않는다. 정확 게임명 직접 조회는 예약 저장소를 읽거나 예약하지 않으므로 이 Redis 실패를 적용하지 않는다. `ASSISTANT_PROVIDER_RESPONSE_INVALID`와 모든 provider 실패는 Room·ChatRoom·초안 확인 결과를 남기지 않는다. `ASSISTANT_DRAFT_EXPIRED`는 HTTP `410 Gone`을 사용하며 클라이언트는 새 초안을 시작해야 한다.

## 11. 부록: 엔드포인트별 오류 매트릭스

각 엔드포인트가 반환할 수 있는 오류 코드의 전체 인덱스다. 개별 판정 순서는 각 API 절을, 코드 정의는 [10. 오류 코드](#10-오류-코드)를 따른다.

| API | 오류 코드 |
|---|---|
| 모든 엔드포인트 | `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_SERVER_ERROR` |
| 요청 경로에 대응하는 엔드포인트 또는 정적 리소스 없음 | `RESOURCE_NOT_FOUND` |
| `GET /api/auth/csrf` | 없음 |
| `POST /api/auth/signup` | `VALIDATION_ERROR`, `EMAIL_ALREADY_EXISTS`, `RATE_LIMIT_EXCEEDED`, `SERVICE_UNAVAILABLE`, `CSRF_TOKEN_INVALID` |
| `POST /api/auth/login` | `VALIDATION_ERROR`, `INVALID_CREDENTIALS`, `RATE_LIMIT_EXCEEDED`, `SERVICE_UNAVAILABLE`, `CSRF_TOKEN_INVALID` |
| `POST /api/auth/logout` | `UNAUTHENTICATED`, `CSRF_TOKEN_INVALID` |
| `GET /api/auth/social/providers` | 없음 |
| `GET /api/auth/social/authorization/{provider}` | JSON 오류 대신 AUTH-05의 고정 `socialAuth` 리다이렉트 결과 |
| `GET /api/auth/social/callback/{provider}` | JSON 오류 대신 AUTH-05의 고정 `socialAuth` 리다이렉트 결과 |
| `POST /api/users/me/social-accounts/{provider}/link` | `UNAUTHENTICATED`, `SOCIAL_PROVIDER_NOT_AVAILABLE`, `SOCIAL_ACCOUNT_ALREADY_LINKED`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me` | `UNAUTHENTICATED` |
| `PATCH /api/users/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `CSRF_TOKEN_INVALID` |
| `GET /api/games` | `VALIDATION_ERROR`, `UNAUTHENTICATED` |
| `GET /api/games/{gameId}` | `VALIDATION_ERROR`, `GAME_NOT_FOUND` |
| `PUT /api/users/me/played-games/{gameId}` | `UNAUTHENTICATED`, `CSRF_TOKEN_INVALID`, `VALIDATION_ERROR`, `GAME_NOT_FOUND` |
| `DELETE /api/users/me/played-games/{gameId}` | `UNAUTHENTICATED`, `CSRF_TOKEN_INVALID`, `VALIDATION_ERROR`, `GAME_NOT_FOUND` |
| `POST /api/rooms` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `GAME_NOT_FOUND`, `CSRF_TOKEN_INVALID` |
| `GET /api/rooms` | `VALIDATION_ERROR` |
| `GET /api/rooms/{roomId}` | `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION` |
| `PATCH /api/rooms/{roomId}` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `GAME_NOT_FOUND`, `VALIDATION_ERROR`, `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/rooms/{roomId}` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `PATCH /api/rooms/{roomId}/status` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `VALIDATION_ERROR`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `POST /api/rooms/{roomId}/participants` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `ALREADY_PARTICIPATING`, `ROOM_NOT_RECRUITING`, `CAPACITY_EXCEEDED`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/rooms/{roomId}/participants/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `PARTICIPATION_NOT_FOUND`, `FORBIDDEN`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me/rooms` | `UNAUTHENTICATED`, `VALIDATION_ERROR` |
| `POST /api/rooms/{roomId}/waitlist` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `ALREADY_PARTICIPATING`, `WAITLIST_NOT_AVAILABLE`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/rooms/{roomId}/waitlist/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `WAITLIST_ENTRY_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION` |
| `DELETE /api/rooms/{roomId}/waitlist/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `WAITLIST_ENTRY_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me/notifications` | `UNAUTHENTICATED`, `VALIDATION_ERROR` |
| `GET /api/users/me/notifications/unread-count` | `UNAUTHENTICATED` |
| `PATCH /api/users/me/notifications/{notificationId}` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `NOTIFICATION_NOT_FOUND`, `CSRF_TOKEN_INVALID` |
| `PATCH /api/users/me/notifications` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `CSRF_TOKEN_INVALID` |
| `POST /api/rooms/{roomId}/chat/messages` | `UNAUTHENTICATED`, `ROOM_NOT_FOUND`, `FORBIDDEN`, `ROOM_CONCURRENT_MODIFICATION`, `VALIDATION_ERROR`, `RATE_LIMIT_EXCEEDED`, `SERVICE_UNAVAILABLE`, `CSRF_TOKEN_INVALID` |
| `GET /api/rooms/{roomId}/chat/messages` | `UNAUTHENTICATED`, `ROOM_NOT_FOUND`, `FORBIDDEN`, `ROOM_CONCURRENT_MODIFICATION`, `VALIDATION_ERROR`, `SERVICE_UNAVAILABLE` |
| `GET /api/rooms/{roomId}/chat/ws` | `UNAUTHENTICATED`, `ROOM_NOT_FOUND`, `FORBIDDEN`, `ROOM_CONCURRENT_MODIFICATION`, `VALIDATION_ERROR`, `SERVICE_UNAVAILABLE` |
| `GET /api/assistant/consent` | `UNAUTHENTICATED` |
| `PUT /api/assistant/consent` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `CSRF_TOKEN_INVALID`, `GRANT`에만 `ASSISTANT_NOT_ENABLED`·`ASSISTANT_CONSENT_VERSION_MISMATCH` |
| `POST /api/assistant/recommendations` | `UNAUTHENTICATED`, `ASSISTANT_NOT_ENABLED`, `ASSISTANT_CONSENT_REQUIRED`, `VALIDATION_ERROR`, `ASSISTANT_INPUT_NOT_ALLOWED`, `ASSISTANT_PROVIDER_UNAVAILABLE`, `ASSISTANT_PROVIDER_RESPONSE_INVALID`, `RATE_LIMIT_EXCEEDED`, `ASSISTANT_COST_LIMIT_EXCEEDED`, `SERVICE_UNAVAILABLE`, `CSRF_TOKEN_INVALID` |
| `POST /api/assistant/drafts` | `UNAUTHENTICATED`, `ASSISTANT_NOT_ENABLED`, `ASSISTANT_CONSENT_REQUIRED`, `VALIDATION_ERROR`, `GAME_NOT_FOUND`, `CSRF_TOKEN_INVALID` |
| `GET /api/assistant/drafts/active` | `UNAUTHENTICATED`, `ASSISTANT_DRAFT_EXPIRED` |
| `PATCH /api/assistant/drafts/{draftId}` | `UNAUTHENTICATED`, `ASSISTANT_DRAFT_NOT_FOUND`, `ASSISTANT_DRAFT_EXPIRED`, `ASSISTANT_DRAFT_CONFLICT`, `VALIDATION_ERROR`, `GAME_NOT_FOUND`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/assistant/drafts/{draftId}` | `UNAUTHENTICATED`, `ASSISTANT_DRAFT_NOT_FOUND`, `ASSISTANT_DRAFT_EXPIRED`, `ASSISTANT_DRAFT_CONFLICT`, `CSRF_TOKEN_INVALID` |
| `POST /api/assistant/drafts/{draftId}/confirm` | `UNAUTHENTICATED`, `ASSISTANT_NOT_ENABLED`, `ASSISTANT_CONSENT_REQUIRED`, `ASSISTANT_DRAFT_NOT_FOUND`, `ASSISTANT_DRAFT_EXPIRED`, `ASSISTANT_DRAFT_CONFLICT`, `VALIDATION_ERROR`, `GAME_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/matches/current` | `UNAUTHENTICATED`, `MATCH_CURRENT_STATE_NOT_STABLE` |
| `POST /api/matches/requests` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `MATCH_REQUEST_ALREADY_ACTIVE`, `IDEMPOTENCY_KEY_CONFLICT`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/matches/requests/me` | `UNAUTHENTICATED`, `MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE`, `CSRF_TOKEN_INVALID` |
| `POST /api/matches/proposals/{proposalId}/responses` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE`, `IDEMPOTENCY_KEY_CONFLICT`, `CSRF_TOKEN_INVALID` |
| `POST /api/matches/parties/{partyId}/chat/messages` | `UNAUTHENTICATED`, `FORBIDDEN`, `MATCH_CHAT_NOT_ACTIVE`, `VALIDATION_ERROR`, `RATE_LIMIT_EXCEEDED`, `SERVICE_UNAVAILABLE`, `CSRF_TOKEN_INVALID` |
| `GET /api/matches/parties/{partyId}/chat/messages` | `UNAUTHENTICATED`, `FORBIDDEN`, `MATCH_CHAT_NOT_ACTIVE`, `VALIDATION_ERROR`, `SERVICE_UNAVAILABLE` |
| `GET /api/matches/parties/{partyId}/chat/ws` | `UNAUTHENTICATED`, `FORBIDDEN`, `MATCH_CHAT_NOT_ACTIVE`, `VALIDATION_ERROR`, `SERVICE_UNAVAILABLE` |
| `DELETE /api/matches/parties/{partyId}/participants/me` | `UNAUTHENTICATED`, `MATCH_PARTY_NOT_FOUND`, `FORBIDDEN`, `MATCH_PARTY_LEAVE_NOT_AVAILABLE`, `CSRF_TOKEN_INVALID` |
| `GET /api/matches/blocks` | `UNAUTHENTICATED`, `VALIDATION_ERROR` |
| `PUT /api/matches/parties/{partyId}/participants/{participantRef}/block` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `MATCH_PARTY_NOT_FOUND`, `MATCH_PARTICIPANT_NOT_FOUND`, `FORBIDDEN`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/matches/blocks/{blockId}` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `CSRF_TOKEN_INVALID` |
| `POST /api/matches/parties/{partyId}/reports` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `MATCH_PARTY_NOT_FOUND`, `MATCH_PARTICIPANT_NOT_FOUND`, `FORBIDDEN`, `CSRF_TOKEN_INVALID` |

- `POST /api/matches/requests`의 `MATCH_REQUEST_ALREADY_ACTIVE`는 `WAITING`·`PROPOSED`·`PAUSED` 요청뿐 아니라 `PREPARING`·아직 명시적으로 나가지 않은 `ACTIVE` 성공 파티 접근 관계에도 적용한다. 사용자가 명시적으로 나갔거나 성공 파티가 실제 `CLOSED`가 된 뒤에는 그 관계만으로 새 요청을 거절하지 않는다.
- `GET /api/rooms/{roomId}`에서만 취소·종료 방을 권한 없는 사용자가 조회할 때 존재 여부를 숨기기 위해 `ROOM_NOT_FOUND`를 반환한다. 그 외 주최자 전용 쓰기 API의 비주최자 요청은 `FORBIDDEN`을 반환한다.
- `PATCH /api/rooms/{roomId}`의 `GAME_NOT_FOUND`는 요청에 `gameId`를 포함했을 때만 적용한다.

> 문서 관리: 소유자 `밤송이클럽 백엔드·프런트엔드 팀` · 최종 검증일 `2026-08-18` · 폐기 조건 `HTTP·WebSocket 계약이 승인된 다른 정본에서 생성되고 이 문서가 그 정본으로 대체될 때`
