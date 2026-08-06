# 알밤메이트 API 명세서

- 문서 상태: **P0 현행 + 승인되어 반영된 P1 목표 HTTP·WebSocket 인터페이스 계약 (정본)**
- 기준 문서: [PRD](PRD.md), [P1 공통 명세](P1-spec.md), [P1 기능별 명세](p1/README.md), [P0 완료 명세](archive/p0/P0-spec.md), [ERD](ERD.md)

### 이 문서의 범위

| 구분 | 내용 |
|---|---|
| 이 문서가 정본인 것 | 클라이언트와 서버 사이 HTTP·WebSocket 계약 — 경로, 인증·CSRF, handshake, 요청·응답·이벤트 스키마, 쿼리 파라미터, 상태, 오류 코드와 판정 순서 |
| 이 문서가 담지 않는 것 | 제품 규칙의 배경(→ [P1-spec](P1-spec.md), [p1/](p1/README.md), [P0 완료 명세](archive/p0/P0-spec.md)), 저장 구조·계산식(→ [ERD](ERD.md)), 되돌리기 어려운 기술 결정과 근거(→ [ADR](adr/README.md)) |
| 변경 시 함께 갱신 | API 계약을 바꾸면 같은 변경에서 이 문서와 [엔드포인트별 오류 매트릭스](#11-부록-엔드포인트별-오류-매트릭스)를 함께 갱신하고, 관련 정본([P1-spec](P1-spec.md)·[P1 기능 문서](p1/README.md)·[ERD](ERD.md)·[ADR](adr/README.md))과의 정합을 확인한다. 상세 규칙은 [CONVENTIONS](CONVENTIONS.md#api-응답)를 따른다. |

> `P0`, `P1`은 API가 도입되는 제품 단계이며 현재 구현 상태값이 아니다. P0 현행과 승인되어 이 문서에 반영된 P1 목표 계약을 함께 관리하고, P1 기능의 현재 계약 준비·생산 코드·자동 검증·운영 상태는 [P1 기능 상태 정본](p1/README.md#기능별-현재-상태)만 따른다.

### 도입 단계와 제공 상태

현재 제공 항목과 목표 항목이 같은 상세 표에 섞이면 `도입 단계`와 `제공 상태`를 행마다 구분한다.

| 구분·값 | 의미 |
|---|---|
| `도입 단계` | 해당 필드·파라미터가 최초로 속한 제품 단계. `P0`, `P1`처럼 기록하며 제공 상태가 바뀌어도 유지한다. |
| `제공` | `develop`에 구현이 반영되고 해당 API 계약 검증을 통과해 현재 요청에 사용하거나 현재 응답에서 기대할 수 있다. |
| `구현 예정` | 승인된 목표 계약이지만 아직 현재 요청에 사용하거나 현재 응답에서 기대하면 안 된다. |
| `검토 예정` | 후속 후보로 검토 중이며 목표 계약도 확정되지 않았다. 현재 요청·응답 계약으로 사용하지 않는다. |
| `Deprecated` | 현재 제공하지만 대체 계약으로의 전환 대상이다. 신규 사용자는 사용하지 않는다. |
| `제거` | 현재 요청에서 허용하거나 현재 응답으로 반환하지 않는다. 변경 이력·전환 문맥이 필요할 때만 남긴다. |

`구현 예정`·`검토 예정` 행의 `필수`·`nullable`·기본값은 `제공`으로 전환된 뒤의 목표 스키마를 뜻한다. 구현과 계약 검증을 완료하면 `도입 단계`는 유지하고 `제공 상태`만 `구현 예정`에서 `제공`으로 바꾼다. 하나의 도입 단계와 제공 상태만 담는 절은 필요한 경우 절 설명에서 기본값을 선언하고 두 열을 생략할 수 있다.

이 표의 `제공 상태`는 HTTP·WebSocket 요청·응답에 해당 항목을 현재 적용할 수 있는지만 나타낸다. 기능 전체의 계약 준비·생산 코드·자동 검증·운영 배포·측정 상태는 [P1 기능 상태 정본](p1/README.md#기능별-현재-상태)에서 별도로 관리한다.

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
- [8. 참가·대기·내 모임 API](#8-참가대기내-모임-api)
- [9. 알림·채팅 API](#9-알림채팅-api)
- [10. 오류 코드](#10-오류-코드)
- [11. 부록: 엔드포인트별 오류 매트릭스](#11-부록-엔드포인트별-오류-매트릭스)

## 1. 공통 계약

### 1.1 HTTP·WebSocket과 데이터 형식

| 항목 | 계약 |
|---|---|
| API prefix | `/api` |
| 요청·응답 본문 | HTTP API는 `application/json`; WebSocket은 Upgrade 뒤 서버 발신 JSON 텍스트 프레임 |
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

`PageResponse<T>`:

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

- 클라이언트 지정 `sort`와 응답 필드 `first`, `last`는 지원하지 않는다.
- 목록 API는 아래 고정 정렬을 적용한다. 모든 정렬은 마지막에 내부 `id`를 고유 tie-breaker로 사용해 같은 DB 상태에서 페이지 이동 중 순서가 임의로 바뀌지 않게 한다.

| API | 고정 정렬 |
|---|---|
| `GET /api/games` | `name ASC, id ASC` |
| `GET /api/rooms` | 상태 보정과 필터를 적용한 뒤 `startsAt ASC, id ASC` |
| `GET /api/users/me/rooms` | 상태 보정, `role` 필터와 중복 제거를 적용한 뒤 `startsAt DESC, id DESC` |
| `GET /api/users/me/notifications` | `createdAt DESC, id DESC` |

P1 채팅 이력은 페이지 번호가 아니라 메시지 ID 커서를 사용한다. `beforeMessageId`가 없으면 최신 메시지부터 반환하고, 값이 있으면 해당 ID보다 이전에 저장된 메시지를 반환한다. 한 번에 반환하는 `size`는 1 이상 100 이하이며, 다음 구간이 있으면 `nextBeforeMessageId`와 `hasNext`를 함께 반환한다.

## 2. API 인덱스

기능 ID는 엔드포인트가 아니라 기능 단위다. 로그인·로그아웃은 함께 `AUTH-03`, 프로필 조회·수정은 함께 `AUTH-04`, 방 취소·종료는 함께 `ROOM-05`, 알림 목록·미확인 개수는 함께 `NOTI-02`, 단건·일괄 읽음은 함께 `NOTI-03`, 채팅 전송·이력 조회는 함께 `CHAT-02`에 속한다. 각 기능의 제품 규칙 정본은 인덱스에서 링크한다.

`단계`는 API 도입 제품 단계다(→ [PRD 로드맵](PRD.md#6-단계별-로드맵)). `P0·P1`은 P0에 도입한 경로를 P1에서 확장한다는 뜻이다. 단계가 늘어도 HTTP 계약인 이 파일을 나누지 않고 표에 행·단계 값을 더한다. 단계 표시는 구현 여부에 따라 바꾸지 않으며, P1 기능의 현재 제공 여부는 [P1 기능 상태 정본](p1/README.md#기능별-현재-상태)으로 판정한다.

| # | 단계 | 기능 ID | Method | Path | 인증 | CSRF | 성공 |
|---:|:---:|---|---|---|:---:|:---:|:---:|
| 1 | P0 | [AUTH-01](#auth-01-csrf-토큰-조회) · [정본](archive/p0/auth-profile.md#auth-01-csrf-토큰-조회) | GET | `/api/auth/csrf` | N | N | 200 |
| 2 | P0 | [AUTH-02](#auth-02-회원가입) · [정본](archive/p0/auth-profile.md#auth-02-회원가입) | POST | `/api/auth/signup` | N | Y | 201 |
| 3 | P0 | [AUTH-03](#auth-03-로그인) · [정본](archive/p0/auth-profile.md#auth-03-로그인로그아웃) | POST | `/api/auth/login` | N | Y | 200 |
| 4 | P0 | [AUTH-03](#auth-03-로그아웃) · [정본](archive/p0/auth-profile.md#auth-03-로그인로그아웃) | POST | `/api/auth/logout` | Y | Y | 200 |
| 5 | P0 | [AUTH-04](#auth-04-내-프로필-조회) · [정본](archive/p0/auth-profile.md#auth-04-내-프로필-조회수정) | GET | `/api/users/me` | Y | N | 200 |
| 6 | P0 | [AUTH-04](#auth-04-내-프로필-수정) · [정본](archive/p0/auth-profile.md#auth-04-내-프로필-조회수정) | PATCH | `/api/users/me` | Y | Y | 200 |
| 7 | P0·P1 | [GAME-01](#game-01-게임-목록검색) · [P0 정본](archive/p0/game-catalog.md#game-01-게임-목록검색) · [SEARCH-01 정본](p1/search.md#search-01-게임-조건-검색) · [SEARCH-03 정본](p1/search.md#search-03-사용자별-해-본-게임) | GET | `/api/games` | 선택 | N | 200 |
| 8 | P0·P1 | [GAME-02](#game-02-게임-상세-조회) · [P0 정본](archive/p0/game-catalog.md#game-02-게임-상세-조회) · [SEARCH-01 정본](p1/search.md#search-01-게임-조건-검색) · [SEARCH-03 정본](p1/search.md#search-03-사용자별-해-본-게임) | GET | `/api/games/{gameId}` | 선택 | N | 200 |
| 9 | P0 | [ROOM-03](#room-03-방-생성) · [정본](archive/p0/room.md#room-03-방-생성) | POST | `/api/rooms` | Y | Y | 201 |
| 10 | P0·P1 | [ROOM-01](#room-01-방-목록-조회) · [P0 정본](archive/p0/room.md#room-01-방-탐색) · [SEARCH-02 정본](p1/search.md#search-02-방-조건-검색) · [ROOM-08 정본](p1/room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리) | GET | `/api/rooms` | 선택 | N | 200 |
| 11 | P0·P1 | [ROOM-02](#room-02-방-상세-조회) · [P0 정본](archive/p0/room.md#room-02-방-상세) · [ROOM-08 정본](p1/room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리) | GET | `/api/rooms/{roomId}` | 선택 | N | 200 |
| 12 | P0 | [ROOM-04](#room-04-방-수정) · [정본](archive/p0/room.md#room-04-방-수정) | PATCH | `/api/rooms/{roomId}` | Y | Y | 200 |
| 13 | P0 | [ROOM-05](#room-05-방-취소) · [정본](archive/p0/room.md#room-05-방-취소종료) | DELETE | `/api/rooms/{roomId}` | Y | Y | 200 |
| 14 | P0 | [ROOM-05](#room-05-방-종료) · [정본](archive/p0/room.md#room-05-방-취소종료) | PATCH | `/api/rooms/{roomId}/status` | Y | Y | 200 |
| 15 | P0 | [PART-01](#part-01-방-참가재참가) · [정본](archive/p0/participation.md#part-01-방-참가재참가) | POST | `/api/rooms/{roomId}/participants` | Y | Y | 201 |
| 16 | P0·P1 | [PART-02](#part-02-참가-취소) · [P0 정본](archive/p0/participation.md#part-02-참가-취소) · [PART-04 정본](p1/room.md#part-04-선착순-대기열과-자동-승격) | DELETE | `/api/rooms/{roomId}/participants/me` | Y | Y | 200 |
| 17 | P0·P1 | [PART-03](#part-03-내-모임-조회) · [P0 정본](archive/p0/participation.md#part-03-내-모임-조회) · [ROOM-08 정본](p1/room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리) · [CHAT-05 정본](p1/chatting.md#chat-05-내-모임-채팅-진입) | GET | `/api/users/me/rooms` | Y | N | 200 |
| 18 | P1 | [PART-04](#part-04-대기-등록재신청) · [정본](p1/room.md#part-04-선착순-대기열과-자동-승격) | POST | `/api/rooms/{roomId}/waitlist` | Y | Y | 201·200 |
| 19 | P1 | [PART-04](#part-04-본인-대기-상태-조회) · [정본](p1/room.md#part-04-선착순-대기열과-자동-승격) | GET | `/api/rooms/{roomId}/waitlist/me` | Y | N | 200 |
| 20 | P1 | [PART-04](#part-04-대기-취소) · [정본](p1/room.md#part-04-선착순-대기열과-자동-승격) | DELETE | `/api/rooms/{roomId}/waitlist/me` | Y | Y | 200 |
| 21 | P1 | [NOTI-02](#noti-02-내-알림-목록) · [정본](p1/notification.md#noti-02-내-알림-목록미확인-개수) | GET | `/api/users/me/notifications` | Y | N | 200 |
| 22 | P1 | [NOTI-02](#noti-02-내-미확인-알림-수) · [정본](p1/notification.md#noti-02-내-알림-목록미확인-개수) | GET | `/api/users/me/notifications/unread-count` | Y | N | 200 |
| 23 | P1 | [NOTI-03](#noti-03-내-알림-단건-읽음) · [정본](p1/notification.md#noti-03-알림-읽음-처리) | PATCH | `/api/users/me/notifications/{notificationId}` | Y | Y | 200 |
| 24 | P1 | [NOTI-03](#noti-03-내-알림-일괄-읽음) · [정본](p1/notification.md#noti-03-알림-읽음-처리) | PATCH | `/api/users/me/notifications` | Y | Y | 200 |
| 25 | P1 | [CHAT-02](#chat-02-메시지-전송) · [정본](p1/chatting.md#chat-02-메시지-전송이력-조회) | POST | `/api/rooms/{roomId}/chat/messages` | Y | Y | 201·200 |
| 26 | P1 | [CHAT-02](#chat-02-메시지-이력-조회) · [정본](p1/chatting.md#chat-02-메시지-전송이력-조회) | GET | `/api/rooms/{roomId}/chat/messages` | Y | N | 200 |
| 27 | P1 | [CHAT-03](#chat-03-실시간-메시지-구독) · [정본](p1/chatting.md#chat-03-실시간-전달재연결-복구) | GET (Upgrade) | `/api/rooms/{roomId}/chat/ws` | Y | N | 101 |
| 28 | P1 | [AUTH-05](#auth-05-소셜-로그인계정-연결) · [정본](p1/social-login.md#auth-05-소셜-로그인계정-연결) | GET | `/api/auth/social/providers` | 선택 | N | 200 |
| 29 | P1 | [AUTH-05](#소셜-로그인-authorization-시작) · [정본](p1/social-login.md#auth-05-소셜-로그인계정-연결) | GET | `/api/auth/social/authorization/{provider}` | N | N | 302 |
| 30 | P1 | [AUTH-05](#소셜-callback과-고정-결과) · [정본](p1/social-login.md#auth-05-소셜-로그인계정-연결) | GET | `/api/auth/social/callback/{provider}` | N | N | 302 |
| 31 | P1 | [AUTH-05](#소셜-계정-연결-시작) · [정본](p1/social-login.md#auth-05-소셜-로그인계정-연결) | POST | `/api/users/me/social-accounts/{provider}/link` | Y | Y | 200 |
| 32 | P1 | [SEARCH-03](#search-03-해-본-게임-표시) · [정본](p1/search.md#search-03-사용자별-해-본-게임) | PUT | `/api/users/me/played-games/{gameId}` | Y | Y | 200 |
| 33 | P1 | [SEARCH-03](#search-03-해-본-게임-표시-취소) · [정본](p1/search.md#search-03-사용자별-해-본-게임) | DELETE | `/api/users/me/played-games/{gameId}` | Y | Y | 200 |
| 34 | P1 | [GAME-03](#game-03-게임-메커니즘-선택지-조회) · [SEARCH-01 정본](p1/search.md#search-01-게임-조건-검색) | GET | `/api/game-mechanisms` | N | N | 200 |
| 35 | P1 | [GAME-04](#game-04-게임-카테고리-선택지-조회) · [SEARCH-01 정본](p1/search.md#search-01-게임-조건-검색) | GET | `/api/game-categories` | N | N | 200 |
| 36 | P1 | [GAME-05](#game-05-게임-테마-선택지-조회) · [SEARCH-01 정본](p1/search.md#search-01-게임-조건-검색) | GET | `/api/game-themes` | N | N | 200 |

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

클라이언트가 관찰하는 상태 변화는 다음과 같다. 제품 규칙 정본은 [P0-spec 방 상태](archive/p0/P0-spec.md#방-상태roomstatus), 저장 반영 방식은 [ADR-0012](adr/room/0012-room-request-boundary-state-reconciliation.md)를 따른다.

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

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `PART-04`](p1/README.md#기능별-현재-상태)

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

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `SEARCH-01`](p1/README.md#기능별-현재-상태)

`GET /api/games`의 플레이 시간 구간 값이다. 검증된 최대 플레이 시간을 기준으로 판정한다.

| 값 | 의미 |
|---|---|
| `SHORT` | 20분 이하 |
| `MEDIUM` | 20분 초과 60분 이하 |
| `LONG` | 60분 초과 |

### PlayedFilter

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `SEARCH-03`](p1/README.md#기능별-현재-상태)

`GET /api/games`의 사용자별 해 본 게임 관계 필터다.

| 값 | 의미 |
|---|---|
| `PLAYED_ONLY` | 현재 사용자가 해 본 게임으로 표시한 결과만 반환 |
| `EXCLUDE_PLAYED` | 현재 사용자가 해 본 게임으로 표시한 결과를 제외 |

두 값 모두 로그인한 사용자만 사용할 수 있다. 필터를 생략하면 로그인 여부와 관계없이 사용자 관계로 결과를 제한하지 않는다.

### ThemeMatch

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `SEARCH-01`](p1/README.md#기능별-현재-상태)

`GET /api/games`의 반복 `theme` 조건 결합 방식이다. `theme`을 생략하면 두 값 모두 결과에 영향을 주지 않는다.

| 값 | 의미 |
|---|---|
| `ANY` | 전달한 테마 중 하나라도 포함한 게임 |
| `ALL` | 전달한 테마를 모두 포함한 게임 |

### SocialProvider

> **단계: P1 계약 승인·구현 대기** · 현재 상태: [P1 기능 상태 정본의 `AUTH-05`](p1/README.md#기능별-현재-상태)

| 값 | 경로값 | 의미 |
|---|---|---|
| `GOOGLE` | `google` | Google OpenID Connect |
| `NAVER` | `naver` | Naver Login OAuth2 |
| `KAKAO` | `kakao` | Kakao Login OpenID Connect |

### NotificationType

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `NOTI-01`~`NOTI-03`](p1/README.md#기능별-현재-상태)

| 값 | 수신자에게 표시하는 의미 |
|---|---|
| `PARTICIPANT_JOINED` | 모임에 새 참가자가 있음 |
| `PARTICIPANT_CANCELED` | 모임에 빈자리가 생김 |
| `ROOM_CANCELED` | 참가 중인 모임이 취소됨 |

`PARTICIPANT_JOINED`는 최초 참가와 취소 뒤 재참가를 구분하지 않는다. 알림 응답은 참가자의 닉네임·사용자 ID·이메일을 포함하지 않으며, 클라이언트는 `type`으로 표시 문구·방식과 동작을 렌더링한다.

`NotificationListItem`에는 `message` 필드가 없고 서버도 표시 문구를 생성하거나 저장하지 않는다. P1 웹 클라이언트는 `type`과 `roomTitle`로 문구를 만들며, 정확한 기본 문구와 텍스트 렌더링 규칙은 [알림 프론트엔드 UX 계약](p1/notification.md#프론트엔드-ux-계약)을 따른다. 이 규칙은 참가자 식별자를 문구에 복원하거나 추론하는 근거가 아니다.

## 4. 공통 스키마

응답 스키마 표에서 `필수 Y`는 필드가 응답에 항상 포함됨을, `nullable Y`는 값으로 JSON `null`을 허용함을 뜻한다. 이 절의 필드는 모두 응답 값이며, 계산으로 도출하는 필드의 계산식 정본은 [ERD 정원·참가자 표시 규칙](ERD.md#정원참가자-표시-규칙)과 [서비스 규칙](ERD.md#서비스-규칙)이다. 혼합 스키마의 `단계` 열은 필드가 도입되는 제품 단계를 나타내며 구현 상태에 따라 바꾸지 않는다.

### 4.1 UserSummary

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `id` | integer | Y | N | 사용자 ID |
| `nickname` | string | Y | N | 표시 닉네임, 1~50자 |

P0 프로필은 닉네임만 제공·수정한다. 이메일과 인증 정보는 응답에 포함하지 않는다.

### 4.2 NicknameSummary

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `nickname` | string | Y | N | 표시 닉네임 |

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
| `status` | RoomStatus | Y | N | P0 | 제공 | 현재 방 상태 |
| `joinable` | boolean | Y | N | P0 | 제공 | 현재 요청자의 참가 가능 여부. 판정 규칙은 아래 참고 |
| `waitlistable` | boolean | Y | N | P1 | 제공 | 현재 요청자의 대기 신청 가능 여부. 판정 규칙은 아래 참고 |

`joinable`과 `waitlistable`은 서버의 같은 행동 가능성 판정에서 계산하며 동시에 `true`일 수 없다.

`joinable`은 다음을 **모두** 만족할 때만 `true`이고, 그 외에는 `false`다.

1. 요청자가 로그인했다.
2. 요청자가 주최자도, 현재 `ACTIVE` 참가자도, 현재 `WAITING` 대기자도 아니다.
3. 방 상태가 `RECRUITING`이다.
4. 현재 시각이 `startsAt`보다 이르다(`now < startsAt`).
5. `remainingRecruitmentSeats`가 1 이상이다.

기존 `CANCELED` 참가 관계를 가진 사용자도 위 조건을 만족하면 재참가할 수 있어 `true`다.

`waitlistable`은 다음을 **모두** 만족할 때만 `true`이고, 그 외에는 `false`다.

1. 요청자가 로그인했다.
2. 요청자가 주최자도, 현재 `ACTIVE` 참가자도, 현재 `WAITING` 대기자도 아니다.
3. 방 상태가 정원 충족으로 `CLOSED`다.
4. 현재 시각이 `startsAt`보다 이르다(`now < startsAt`).
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

> 필드 구조는 P0 계약이고, 활성 대기자 자동 승격 뒤 최종 값을 반환하는 동작은 [P1 `PART-04` 계약](p1/room.md#part-04-선착순-대기열과-자동-승격)이다. 현재 상태는 [P1 기능 상태 정본](p1/README.md#기능별-현재-상태)을 따른다.

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
| `chatAvailable` | boolean | Y | N | P1 | 제공 | 현재 요청자가 채팅 API에 접근할 수 있는지. `HOST` 또는 `ACTIVE` 참가자이고 방 상태가 `RECRUITING`·`CLOSED`일 때만 `true`. 프론트엔드의 직접 진입점은 모임 상세이며 내 모임 목록에서는 이 필드로 채팅 버튼을 표시하지 않는다 |

`joinable`과 `waitlistable`은 `PublicRoomResponse`와 같은 요청자 기준 값이다. 내 모임은 주최·참가 ROOM만 반환하므로 두 값은 항상 `false`이고, 대기 중인 ROOM을 조회 대상에 추가하지 않는다. `chatAvailable`은 서버 접근 가능성의 계약 일치를 위한 값이며, 채팅 버튼은 모임 상세의 `myRole`·방 상태 기준으로 표시한다. 내 모임 목록에는 중복 채팅 진입을 표시하지 않으며, 직접 채팅 API를 호출해도 서버가 같은 관계·상태 규칙으로 거절한다.

### 4.11 RoomStatusResponse

방 취소·종료 응답이다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 방 ID |
| `roomStatus` | RoomStatus | Y | N | 변경 후 상태 |

### 4.12 NotificationListItem

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `NOTI-02`·`NOTI-03`](p1/README.md#기능별-현재-상태)

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

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `NOTI-02`](p1/README.md#기능별-현재-상태)

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `unreadCount` | integer | Y | N | 보존 기간 안의 본인 알림 중 `readAt = null`인 건수, 0 이상 |

### 4.14 NotificationBulkReadResponse

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `NOTI-03`](p1/README.md#기능별-현재-상태)

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `updatedCount` | integer | Y | N | 이번 요청에서 처음 읽음 처리한 보존 기간 안의 알림 수, 0 이상 |
| `readAt` | string(date-time) | Y | N | 이번 일괄 읽음 SQL 내부에서 `clock_timestamp()`를 한 번 평가해 고정한 `operationTime`. 변경 대상이 없어도 반환 |
| `boundaryNotificationId` | integer | Y | Y | 요청이 고정한 보존 기간 안의 본인 알림 집합에서 가장 큰 알림 ID. 대상 알림이 없으면 `null` |

### 4.15 ChatMessage

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `CHAT-02`](p1/README.md#기능별-현재-상태)

채팅 이력과 메시지 전송 성공 응답에 사용한다. 메시지 본문은 일반 텍스트로만 반환하며 HTML·스크립트로 해석하지 않는다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `messageId` | integer | Y | N | 서버가 저장 순서에 사용하는 메시지 ID |
| `roomId` | integer | Y | N | 채팅 대상 방 ID |
| `clientMessageId` | string | Y | N | 클라이언트가 재시도 멱등성에 사용하는 1~100자 식별자 |
| `sender` | NicknameSummary | Y | N | 작성자 표시명 |
| `isMine` | boolean | Y | N | 서버가 현재 요청자와 발신자가 같은지 계산한 값. 사용자 ID는 노출하지 않는다 |
| `content` | string | Y | N | 앞뒤 공백 제거 후 1~500자의 일반 텍스트 |
| `createdAt` | string(date-time) | Y | N | 서버가 저장한 시각 |

### 4.16 ChatMessagePage

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `CHAT-02`](p1/README.md#기능별-현재-상태)

`GET /api/rooms/{roomId}/chat/messages`의 응답이다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `messages` | ChatMessage[] | Y | N | 최신 메시지부터 `messageId` 내림차순으로 반환한 구간 |
| `nextBeforeMessageId` | integer | Y | Y | 다음 과거 구간을 조회할 커서. 더 없으면 `null` |
| `hasNext` | boolean | Y | N | 더 과거 메시지 존재 여부 |

### 4.17 ChatMessageEvent

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `CHAT-03`](p1/README.md#기능별-현재-상태)

`GET /api/rooms/{roomId}/chat/ws`로 Upgrade한 WebSocket이 보내는 서버 발신 텍스트 이벤트다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `eventId` | integer | Y | N | 중복 제거와 재연결 기준으로 사용하는 `messageId` |
| `type` | string | Y | N | P1에서는 `MESSAGE_CREATED`만 사용 |
| `message` | ChatMessage | Y | N | 커밋된 메시지 |

### 4.18 MyRoomWaitlistResponse

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `PART-04`](p1/README.md#기능별-현재-상태)

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

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `SEARCH-03`](p1/README.md#기능별-현재-상태)

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `gameId` | integer | Y | N | 표시하거나 표시를 취소한 알밤메이트 내부 게임 ID |
| `playedByMe` | boolean | Y | N | 표시 성공은 `true`, 표시 취소 성공은 `false` |

## 5. 인증·프로필 API

### 인증 요청 남용 제한

회원가입·로그인 요청에는 아래 요청 한도를 적용한다. 제한을 선택한 근거와 CSRF·요청 형식 검증, 횟수 제한, 동시 해시 실행 슬롯과 비밀번호 해시 작업의 내부 적용 순서는 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)을 따른다.

| 대상 | 제한 키 | 허용량 |
|---|---|---|
| 회원가입 | 원격 IP | 10분 이동 창당 5회. ADR-0013의 사전 검증을 통과한 요청은 성공·실패 모두 계산 |
| 로그인 | 원격 IP | 10분 이동 창당 30회. ADR-0013의 사전 검증을 통과한 요청은 성공·실패 모두 계산 |
| 로그인 실패 | 정규화 이메일 + 원격 IP | 10분 이동 창당 5회. 로그인 성공 시 실패 횟수 초기화 |

다음 경우 `429 RATE_LIMIT_EXCEEDED`를 반환한다.

- 요청 횟수 제한 초과
- 동일한 정규화 이메일·원격 IP 조합의 로그인 검증 진행 중
- 동시 해시 실행 슬롯 부족

`Retry-After`에는 다시 요청할 수 있을 때까지의 초를 담는다. 실패 한도 초과 시 가장 오래된 실패가 이동 창을 벗어날 때까지 남은 초, 동일 키 검증 진행 중이나 슬롯 부족 시 `1`이다. 클라이언트는 이 값에 따라 재시도한다.

- 존재하지 않는 이메일과 잘못된 비밀번호는 계정 유무를 구분하지 않고 같은 `INVALID_CREDENTIALS`로 응답한다.
- 원격 IP를 바꿔 가며 같은 계정을 노리는 분산 추측은 P0 제한 범위 밖이다. 근거와 재검토 조건은 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)을 따른다.

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
| `password` | string | Y | N | Unicode code point 15개 이상 64개 이하이면서 UTF-8 인코딩 결과 72바이트 이하 |
| `nickname` | string | Y | N | 앞뒤 공백 제거 후 1~50자, 제어문자 금지 |

- 비밀번호는 Unicode와 공백을 허용하고, 앞뒤 공백 제거·Unicode 정규화·자동 잘라내기를 하지 않으며 문자 조합 규칙도 강제하지 않는다.
- UTF-8 인코딩 결과가 72바이트를 넘는 비밀번호는 `VALIDATION_ERROR`로 거절한다.
- 비밀번호 원문은 응답에 포함하지 않는다. 저장 방식은 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)을 따른다.

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

### AUTH-05 소셜 로그인·계정 연결

> **단계: P1 계약 승인·구현 대기** · 현재 상태: [P1 기능 상태 정본의 `AUTH-05`](p1/README.md#기능별-현재-상태)

이 절은 #328에서 승인된 계약이다. 제품 규칙은 [P1 소셜 로그인 명세](p1/social-login.md), 외부 식별자·세션 결정은 [ADR-0042](adr/auth/0042-p1-oauth-social-identity-and-session-integration.md)를 따른다. 경로의 `{provider}`는 [SocialProvider](#socialprovider)의 소문자 경로값만 허용한다.

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

`link-required`는 비로그인 첫 로그인에서만 반환한다. 인증된 명시적 연결 callback은 제공자 이메일의 일치·중복과 무관하게 현재 세션 사용자를 연결 대상으로 유지하며, 외부 식별자 또는 사용자·제공자 유일 제약이 충돌할 때만 `link-conflict`로 돌아간다. 제공자별 이메일 신뢰 조건과 `null` 매핑은 [P1 소셜 로그인 명세](p1/social-login.md#제공자-이메일-매핑)를 따른다.

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

게임 데이터는 운영자가 준비한다. 사용자용 게임 생성·수정·삭제 API는 제공하지 않는다(→ [GAME-01 정본](archive/p0/game-catalog.md#game-01-게임-목록검색), 게임 목록 출처 [ADR-0015](adr/game/0015-bgg-baseline-team-collected-game-list.md)).

### GAME-01 게임 목록·검색

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/games` |
| 인증 / CSRF | 선택 / 불필요. 유효한 `playedFilter` 사용 시 인증 필요 |
| 성공 | `200 OK`, `data`: `PageResponse<GameListItem>` |

#### Query Parameters

> `구현 예정` 파라미터는 현재 요청에 전송하면 안 된다. `필수`·기본값은 제공 전환 뒤의 목표 계약이다.

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
| `complexityMin` | number | N | 검색 없음 | P1 | 제공 | `1.00`~`5.00`, 난이도 닫힌 구간의 하한 |
| `complexityMax` | number | N | 검색 없음 | P1 | 제공 | `1.00`~`5.00`, 난이도 닫힌 구간의 상한 |
| `playedFilter` | PlayedFilter | N | 검색 없음 | P1 | 제공 | 단일 값. `PLAYED_ONLY` 또는 `EXCLUDE_PLAYED`; 사용 시 로그인 필요 |
| `mechanism` | string | N | 검색 없음 | P1 | 제공 | 반복 전달 가능한 공개 메커니즘 내부 코드. 목록 안 OR |
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

- 이전 `playTime` 값 `SHORT`, `MEDIUM`, `LONG`은 제거했다. 단독으로 전달하거나 새 값과 섞어 전달하면 검증 오류이며 조용히 무시하지 않는다.
- 복잡도는 전달한 하한 이상·상한 이하의 닫힌 구간으로 판정한다. 두 값을 함께 전달할 때 하한이 상한보다 크면 검증 오류다.
- `PLAYED_ONLY`는 현재 사용자의 표시 관계가 있는 게임만, `EXCLUDE_PLAYED`는 그 관계가 없는 게임만 반환한다. 관계가 없다는 사실을 실제 미플레이로 해석하지 않는다.
- `playedFilter`를 생략하면 관계 필터를 적용하지 않는다. 잘못된 값이나 중복 전달은 로그인 여부와 관계없이 먼저 `400 VALIDATION_ERROR`, 유효한 값을 비로그인으로 전달하면 `401 UNAUTHENTICATED`다.
- `mechanism`은 [GAME-03](#game-03-게임-메커니즘-선택지-조회)의 공개 `code`를 정확히 전달한다. 여러 코드는 OR로 결합하고 다른 필터와는 AND로 결합하며, 같은 코드를 반복해도 결과를 중복하지 않는다.
- 존재하지 않거나 비공개인 메커니즘 코드는 전체 요청을 `VALIDATION_ERROR`로 거절한다. 일부 유효 코드가 함께 있어도 잘못된 코드를 조용히 무시하지 않는다.
- `category`는 [GAME-04](#game-04-게임-카테고리-선택지-조회)의 code를 반복 전달하고 같은 목록 안에서 OR다. `theme`은 [GAME-05](#game-05-게임-테마-선택지-조회)의 code를 반복 전달하며, `themeMatch=ANY`는 하나 이상, `themeMatch=ALL`은 모든 고유 code 관계를 요구한다.
- `recommendedPlayerCount`와 `bestPlayerCount`는 각각 BGG 투표에서 정규화한 양의 인원을 반복 전달하며 같은 목록 안에서 OR다. 가능 인원과 다른 의미이며 `4+` 결과는 해당 게임의 검증된 최대 가능 인원까지 확장된 관계로 판정한다.
- `themeMatch`는 생략하면 `ANY`이고 theme 없이 보내도 유효하다. 중복된 themeMatch, 존재하지 않는 category/theme code, 0 이하 인원은 일부 유효 값이 함께 있어도 전체 요청을 `VALIDATION_ERROR`로 거절한다.
- 인원·시간·복잡도·카테고리·테마·추천/베스트·메커니즘 필터를 적용하면 해당 조건을 판정할 검증값이나 관계가 없는 게임은 제외한다. 필터를 생략하면 누락값이나 관계 부재만으로 제외하지 않는다.
- 모든 필터를 적용한 뒤 전체 건수, `name ASC, id ASC` 정렬과 페이지를 계산한다.

`tag` 필터와 클라이언트 지정 `sort`는 지원하지 않는다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| query parameter 검증 실패 | 400 | `VALIDATION_ERROR` |
| 유효한 `playedFilter`를 인증 없이 사용 | 401 | `UNAUTHENTICATED` |
| 존재하지 않거나 비공개인 `mechanism` 코드 | 400 | `VALIDATION_ERROR` |
| 존재하지 않는 `category` 또는 `theme` code, 중복·잘못된 `themeMatch`, 0 이하 추천·베스트 인원 | 400 | `VALIDATION_ERROR` |

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

## 7. 방 API

### ROOM-01 방 목록 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/rooms` |
| 인증 / CSRF | 선택 / 불필요 |
| 성공 | `200 OK`, `data`: `PageResponse<PublicRoomResponse>` |

유효한 세션이 있으면 요청자 기준으로 `joinable`을 계산한다.

#### Query Parameters

> `구현 예정` 파라미터는 현재 요청에 전송하면 `VALIDATION_ERROR`가 발생한다. `필수`·적용 조건은 제공 전환 뒤의 목표 계약이다.

| 이름 | 타입 | 필수 | 적용 조건 | 도입 단계 | 제공 상태 | 의미 |
|---|---|:---:|---|:---:|:---:|---|
| `type` | RoomType | N | 전달 시 | P0 | 제공 | 방 유형 |
| `gameId` | integer | N | 전달 시 | P0 | 제공 | 1 이상의 알밤메이트 내부 게임 ID |
| `keyword` | string | N | 전달 시 | P0 | 제공 | 방 제목 부분 일치 |
| `startsAtFrom` | string(date-time) | N | 전달 시 | P1 | 제공 | `startsAt >= startsAtFrom` |
| `startsAtTo` | string(date-time) | N | 전달 시 | P1 | 제공 | `startsAt < startsAtTo` |
| `minRemainingSeats` | integer | N | 전달 시 | P1 | 제공 | 최소 남은 모집 자리, 1~10 |
| `experienceLevels` | ExperienceLevel | N | 전달 시 | P1 | 제공 | 반복 전달 가능한 권장 경험 수준. 목록 안 OR |
| `rulemasterOnly` | boolean | N | `true`일 때 | P1 | 제공 | 룰마스터 진행 방만 반환 |
| `page` | integer | N | 항상 | P0 | 제공 | 기본값 `0` |
| `size` | integer | N | 항상 | P0 | 제공 | 기본값 `10`, 1~100 |

`type`, `gameId`, `keyword`와 P1 조건은 서로 독립적인 선택 필터이며, 전달된 서로 다른 조건을 모두 만족하는 방을 반환한다. 반복한 `experienceLevels` 안에서만 OR로 결합하고 같은 값의 중복은 한 번 전달한 것과 같다. 모든 필터를 생략하면 두 유형의 공개 방 전체를 반환한다. `keyword`의 빈 문자열과 공백은 검색 조건 없음으로 처리하며, 제목 부분 일치는 대소문자를 구분하지 않는다.

- 날짜 범위는 시작 경계를 포함하고 종료 경계를 제외하는 `[startsAtFrom, startsAtTo)`다. 한쪽 경계만 전달할 수 있으며 두 값을 함께 전달하면 시작 경계가 종료 경계보다 빨라야 한다.
- 남은 모집 자리는 상태 정합화 뒤 `recruitmentCapacity - activeParticipantCount`로 계산하고 `minRemainingSeats` 이상인 방만 반환한다.
- 경험 수준은 방의 권장 조건을 검색할 뿐 참가 자격 제한으로 바꾸지 않는다.
- `rulemasterOnly=true`일 때만 룰마스터 진행 여부를 조건으로 적용한다. 생략하거나 `false`이면 해당 조건을 적용하지 않는다.
- 공개 목록의 상태를 정합화한 뒤 모든 필터를 적용하고 전체 건수, `startsAt ASC, id ASC` 정렬과 페이지를 계산한다.

- 잘못된 enum·날짜·boolean, 역전된 날짜 범위, `gameId` 0 이하, 숫자 범위·바인딩 실패, `page`·`size` 범위 위반 또는 허용하지 않는 parameter는 `VALIDATION_ERROR`다.
- `keyword`는 방 제목 검색이며, P0에서 제외한 조건 필터가 아니다.
- 공개 목록은 `RECRUITING`, `CLOSED` 방만 반환한다.
- `playerCount`, `playTime`, `region`, `experienceLevel`, `tag`, `categoryIds`, `bggWeightMin`, `bggWeightMax`, `sort`는 방 목록 쿼리 파라미터가 아니다. 경험 수준 다중 선택은 `experienceLevels`만 사용한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| query parameter 검증 실패 | 400 | `VALIDATION_ERROR` |
| 동시 변경으로 방 상태를 확인할 수 없음 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

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

> **단계: P0 현행 + P1 `PART-04` 확장 계약** · 현재 P1 상태: [기능 상태 정본](p1/README.md#기능별-현재-상태)

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

> **단계: P0 현행 + P1 `ROOM-08`·`CHAT-05` 확장 계약** · 현재 P1 상태: [기능 상태 정본](p1/README.md#기능별-현재-상태)

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/users/me/rooms` |
| 인증 / CSRF | 필요 / 불필요 |
| 성공 | `200 OK`, `data`: `PageResponse<MyRoomListItem>` |

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
| 동시 변경으로 방 상태를 확인할 수 없음 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### PART-04 대기 등록·재신청

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `PART-04`](p1/README.md#기능별-현재-상태)

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

인증·CSRF·path와 방 존재 확인 뒤 현재 시각 기준 상태를 반영하고 아래 순서로 판정한다.

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
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 요청자가 주최자 또는 현재 참가자 | 409 | `ALREADY_PARTICIPATING` |
| 현재 방·시각·좌석 조건에서 대기할 수 없음 | 409 | `WAITLIST_NOT_AVAILABLE` |
| ROOM 낙관적 락 또는 조건부 version claim 충돌의 재시도 예산 소진 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### PART-04 본인 대기 상태 조회

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `PART-04`](p1/README.md#기능별-현재-상태)

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
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 본인 대기 이력이 없음 | 404 | `WAITLIST_ENTRY_NOT_FOUND` |
| 동시 변경으로 최신 상태를 확인할 수 없음 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

### PART-04 대기 취소

> **단계: P1 계약** · 현재 상태: [P1 기능 상태 정본의 `PART-04`](p1/README.md#기능별-현재-상태)

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
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 취소할 본인 `WAITING` 관계가 없음 | 404 | `WAITLIST_ENTRY_NOT_FOUND` |
| 동시 변경 충돌 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

## 9. 알림·채팅 API

> **단계: P1 계약**
>
> `NOTI-02`·`NOTI-03`의 현재 제공·검증·운영 상태는 [P1 기능 상태 정본](p1/README.md#기능별-현재-상태)을 따른다. 이 절은 상태가 바뀌어도 P1 HTTP 계약으로 유지한다.

P1 알림 API는 로그인한 사용자의 앱 내 알림만 제공하도록 계약한다. 알림 생성은 방·참가 업무와 내부 Outbox relay가 담당하므로 공개 생성 API는 없다. 제품 범위·수신자·중복 방지 규칙은 [P1 알림 구현 명세](p1/notification.md), 물리 저장 구조는 [ERD의 P1 알림 저장 계약](ERD.md#p1-알림-저장-계약)을 따른다.

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
- 일괄 읽음 성공 시 `boundaryNotificationId`를 실제 갱신 ID 집합이나 클라이언트 읽음 경계로 해석해 현재 목록을 직접 변경하지 않는다. 목록 첫 페이지와 미확인 개수를 즉시 다시 조회해 서버 응답으로 교체하며, polling 중단·이전 응답 폐기와 요청 세대 규칙은 [알림 프론트엔드 UX 계약](p1/notification.md#읽음-상태-동기화)을 따른다. `updatedCount`만으로 배지를 영구히 `0`으로 고정하지 않는다.
- 낙관적으로 화면을 먼저 바꾼 읽음 요청이 실패하면 그 상태를 확정하지 않는다. 목록과 미확인 개수를 다시 조회해 서버 상태로 복구하며, 상세 사용자 동작은 [알림 프론트엔드 UX 계약](p1/notification.md#프론트엔드-ux-계약)을 따른다.

### 채팅 공통 계약

채팅의 제품 규칙은 [P1 방 채팅 기능 명세](p1/chatting.md)를 따른다. 아래 인터페이스는 구현 예정 계약이다. [ADR-0031](adr/chat/0031-chat-history-cursor-pagination.md)·[ADR-0032](adr/chat/0032-http-send-websocket-receive.md)·[ADR-0033](adr/chat/0033-postgresql-source-after-commit-delivery.md)·[ADR-0049](adr/chat/0049-chat-message-retention-lock-section-boundary.md)가 승인됐지만, 구현과 검증이 끝나기 전에는 제공 기능을 뜻하지 않는다. 전송 제한·Redis 실패 처리의 공개 계약은 [#288 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930)과 [#372 정본 반영 이슈](https://github.com/bamsongi-club/albam-mate/issues/372)에 따른다.

모든 채팅 요청은 요청 시점의 방 상태와 주최자·현재 `ACTIVE` 참가자 관계를 서버에서 다시 확인한다. `RECRUITING`·`CLOSED` 방만 일반 사용자 접근을 허용하며, 참가 취소·`CANCELED`·`FINISHED` 상태는 `FORBIDDEN`으로 거절한다. 메시지 본문은 로그와 메트릭에 기록하지 않는다.

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
| `content` | string | Y | 앞뒤 공백 제거 후 1~500자의 일반 텍스트 |

검증·권한 판정은 세션, 방 존재, 방 상태·현재 관계, 본문, 멱등성 순서로 수행한다. 같은 사용자가 같은 방에서 같은 `clientMessageId`로 다른 본문을 보내면 `400 VALIDATION_ERROR`다. 전송 제한은 아래 검증을 통과한 신규 전송에만 적용하며 PostgreSQL 저장 직전에 두 bucket을 함께 판정한다.

#### 전송 제한 계약

| 대상 | 제한 키 | 허용량 | 창·TTL |
|---|---|---:|---|
| 사용자 | 인증된 `userId`, 모든 방 합산 | 5건/10초 | 10초 고정 창 |
| 방 | `roomId`, 모든 참여자 합산 | 30건/10초 | 10초 고정 창 |

- 첫 허용 요청이 각 bucket의 TTL을 시작한다. 이후 허용·거절 요청은 TTL을 연장하지 않는다.
- 사용자·방 bucket의 허용 확인과 증가는 원자적으로 처리한다. 하나라도 초과하면 어느 bucket도 증가시키지 않는다.
- 인증·관계·본문·멱등성 검증 실패, 권한 거부, 이미 저장된 동일 `clientMessageId`의 동일 payload 재전송은 quota를 소비하지 않는다.
- 제한 초과는 `429 RATE_LIMIT_EXCEEDED`로 응답한다. `Retry-After`는 초과한 bucket의 남은 TTL을 밀리초에서 올림한 초 단위 값으로 계산하며, 두 bucket이 초과하면 더 큰 값을 사용한다. 이 헤더는 429에만 포함하고 성공 응답과 503에는 포함하지 않는다.
- Redis 연결·명령·원자 연산·TTL 확인 실패 또는 결과 불명확은 fail closed로 처리한다. 메시지를 PostgreSQL에 저장하지 않고 `503 SERVICE_UNAVAILABLE`을 반환하며 인메모리 fallback은 허용하지 않는다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| 방이 없음 | 404 | `ROOM_NOT_FOUND` |
| 주최자·현재 `ACTIVE` 참가자가 아니거나 방이 `CANCELED`·`FINISHED`임 | 403 | `FORBIDDEN` |
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
| 경로·커서 검증 실패 | 400 | `VALIDATION_ERROR` |
| 허용되지 않은 `Origin` | 403 | `FORBIDDEN` |
| Upgrade 전에 세션 상태 저장소를 확인할 수 없음 | 503 | `SERVICE_UNAVAILABLE` |

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
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 요청 미디어 타입입니다. | `Content-Type`이 요청 본문에서 지원하는 미디어 타입과 호환되지 않음 |
| `INTERNAL_SERVER_ERROR` | 500 | 서버 오류가 발생했습니다. | 처리하지 않은 예외로 요청을 완료하지 못함 |
| `SERVICE_UNAVAILABLE` | 503 | 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요. | 요청 처리에 필수인 세션 또는 전송 제한 상태 저장소를 확인할 수 없음 |

`METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` 응답은 Spring MVC 예외가 제공하는 `Allow`, `Accept`, `Accept-Patch` 등의 프로토콜 헤더가 있으면 그대로 포함한다.

`SERVICE_UNAVAILABLE`의 현재 적용 범위는 [채팅 API](#채팅-공통-계약)의 세 엔드포인트다. `local-multi`에서 채팅 요청이 Spring Session Redis의 세션 상태를 확인할 수 없으면 이 코드를 반환하며, 메시지 전송은 세션 저장소가 정상이더라도 전송 제한 상태 저장소를 확인할 수 없으면 저장 전에 같은 코드를 반환한다. 전송 제한 장애의 503에는 `Retry-After`를 포함하지 않는다. Redis 장애 시 인메모리 구현으로 자동 대체하지 않는 근거는 [ADR-0038](adr/platform/0038-multi-instance-session-and-scheduler-coordination.md)과 [#288 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930)을 따른다. `prod` 적용은 별도 운영 설정·계약 확정 후 다룬다.

로그인·로그아웃과 그 밖의 세션 사용 엔드포인트로 이 코드를 확장할지는 이 문서에서 아직 결정하지 않는다. 확장이 필요하면 적용 엔드포인트를 명시한 별도 계약 변경으로 승인받은 뒤 이 절과 [엔드포인트별 오류 매트릭스](#11-부록-엔드포인트별-오류-매트릭스)를 함께 갱신한다.

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

`GET /api/rooms`, `GET /api/rooms/{roomId}`, `GET /api/users/me/rooms`에서 이 오류를 받으면 클라이언트는 조회 요청 전체를 다시 시도한다. 알고리즘은 [ADR-0012](adr/room/0012-room-request-boundary-state-reconciliation.md)와 [ADR-0005](adr/participation/0005-room-participation-optimistic-locking.md)를 따른다.

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

## 11. 부록: 엔드포인트별 오류 매트릭스

각 엔드포인트가 반환할 수 있는 오류 코드의 전체 인덱스다. 개별 판정 순서는 각 API 절을, 코드 정의는 [10. 오류 코드](#10-오류-코드)를 따른다.

| API | 오류 코드 |
|---|---|
| 모든 엔드포인트 | `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_SERVER_ERROR` |
| 요청 경로에 대응하는 엔드포인트 또는 정적 리소스 없음 | `RESOURCE_NOT_FOUND` |
| `GET /api/auth/csrf` | 없음 |
| `POST /api/auth/signup` | `VALIDATION_ERROR`, `EMAIL_ALREADY_EXISTS`, `RATE_LIMIT_EXCEEDED`, `CSRF_TOKEN_INVALID` |
| `POST /api/auth/login` | `VALIDATION_ERROR`, `INVALID_CREDENTIALS`, `RATE_LIMIT_EXCEEDED`, `CSRF_TOKEN_INVALID` |
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
| `GET /api/rooms` | `VALIDATION_ERROR`, `ROOM_CONCURRENT_MODIFICATION` |
| `GET /api/rooms/{roomId}` | `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION` |
| `PATCH /api/rooms/{roomId}` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `GAME_NOT_FOUND`, `VALIDATION_ERROR`, `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/rooms/{roomId}` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `PATCH /api/rooms/{roomId}/status` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `VALIDATION_ERROR`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `POST /api/rooms/{roomId}/participants` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `ALREADY_PARTICIPATING`, `ROOM_NOT_RECRUITING`, `CAPACITY_EXCEEDED`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/rooms/{roomId}/participants/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `PARTICIPATION_NOT_FOUND`, `FORBIDDEN`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me/rooms` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_CONCURRENT_MODIFICATION` |
| `POST /api/rooms/{roomId}/waitlist` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `ALREADY_PARTICIPATING`, `WAITLIST_NOT_AVAILABLE`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/rooms/{roomId}/waitlist/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `WAITLIST_ENTRY_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION` |
| `DELETE /api/rooms/{roomId}/waitlist/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `WAITLIST_ENTRY_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me/notifications` | `UNAUTHENTICATED`, `VALIDATION_ERROR` |
| `GET /api/users/me/notifications/unread-count` | `UNAUTHENTICATED` |
| `PATCH /api/users/me/notifications/{notificationId}` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `NOTIFICATION_NOT_FOUND`, `CSRF_TOKEN_INVALID` |
| `PATCH /api/users/me/notifications` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `CSRF_TOKEN_INVALID` |
| `POST /api/rooms/{roomId}/chat/messages` | `UNAUTHENTICATED`, `ROOM_NOT_FOUND`, `FORBIDDEN`, `VALIDATION_ERROR`, `RATE_LIMIT_EXCEEDED`, `SERVICE_UNAVAILABLE`, `CSRF_TOKEN_INVALID` |
| `GET /api/rooms/{roomId}/chat/messages` | `UNAUTHENTICATED`, `ROOM_NOT_FOUND`, `FORBIDDEN`, `VALIDATION_ERROR`, `SERVICE_UNAVAILABLE` |
| `GET /api/rooms/{roomId}/chat/ws` | `UNAUTHENTICATED`, `ROOM_NOT_FOUND`, `FORBIDDEN`, `VALIDATION_ERROR`, `SERVICE_UNAVAILABLE` |

- `GET /api/rooms/{roomId}`에서만 취소·종료 방을 권한 없는 사용자가 조회할 때 존재 여부를 숨기기 위해 `ROOM_NOT_FOUND`를 반환한다. 그 외 주최자 전용 쓰기 API의 비주최자 요청은 `FORBIDDEN`을 반환한다.
- `PATCH /api/rooms/{roomId}`의 `GAME_NOT_FOUND`는 요청에 `gameId`를 포함했을 때만 적용한다.
