# 알밤메이트 P0 API 명세서

- 문서 상태: **P0 1차 MVP HTTP API 계약 (정본)**
- 기준 문서: [PRD](PRD.md), [P0 공통 명세](P0-spec.md), [기능별 P0 명세](P0-spec.md#관련-문서), [ERD](ERD.md)

### 이 문서의 범위

| 구분 | 내용 |
|---|---|
| 이 문서가 정본인 것 | 클라이언트와 서버 사이 HTTP 계약 — 경로, 인증·CSRF, 요청·응답 스키마, 쿼리 파라미터, HTTP 상태, 오류 코드와 판정 순서 |
| 이 문서가 담지 않는 것 | 제품 규칙의 배경(→ [P0-spec](P0-spec.md), [p0/](P0-spec.md#관련-문서)), 저장 구조·계산식(→ [ERD](ERD.md)), 되돌리기 어려운 기술 결정과 근거(→ [ADR](adr/README.md)) |
| 변경 시 함께 갱신 | API 계약을 바꾸면 같은 변경에서 이 문서와 [엔드포인트별 오류 매트릭스](#10-부록-엔드포인트별-오류-매트릭스)를 함께 갱신하고, 관련 정본([P0-spec](P0-spec.md)·[ERD](ERD.md)·[ADR](adr/README.md))과의 정합을 확인한다. 상세 규칙은 [CONVENTIONS](CONVENTIONS.md#api-응답)를 따른다. |

> 이 문서는 P0 1차 MVP의 17개 API만 정의한다. 같은 사실을 다른 정본과 중복해서 서술하지 않고, 배경과 근거가 필요한 곳에서는 해당 정본으로 링크한다.

### 대표 흐름으로 읽기

P0는 `게임부터 찾기`, `사람부터 만나기`, `방 만들기` 세 흐름을 지원한다(→ [P0-spec 핵심 사용자 흐름](P0-spec.md#핵심-사용자-흐름)). 아래는 `게임부터 찾기 → 참가`를 API 호출 순서로 옮긴 예시다. CSRF 토큰을 언제 다시 받아야 하는지까지 포함한다.

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
- [8. 참가·내 모임 API](#8-참가내-모임-api)
- [9. 오류 코드](#9-오류-코드)
- [10. 부록: 엔드포인트별 오류 매트릭스](#10-부록-엔드포인트별-오류-매트릭스)

## 1. 공통 계약

### 1.1 HTTP와 데이터 형식

| 항목 | 계약 |
|---|---|
| API prefix | `/api` |
| 요청·응답 본문 | `application/json` |
| JSON 필드명 | camelCase |
| 식별자 | JSON에서는 integer, 경로에서는 1 이상의 10진 정수. 형식·범위를 벗어난 경로 값은 대상을 조회하기 전에 `400 VALIDATION_ERROR`로 거절한다. 생성 전략은 [ADR-0006](adr/platform/0006-p0-bigint-identity-ids.md)과 [ERD](ERD.md#테이블-명세)를 따른다 |
| 요청 시각 | RFC 3339 기반 서비스 프로필의 `date-time`. `T`/`t` 구분자와 `Z`/`z` UTC 표기를 허용하며, `±HH:MM` 오프셋도 허용한다. 초는 필수이고 `00`~`59` 또는 윤초 `60`을 허용한다. 윤초 `60`은 Java 21 `Instant`가 표현할 수 있는 직전 `:59` 시각의 `Instant`로 정규화한다 |
| 응답 시각 | RFC 3339 `date-time`, `Asia/Seoul` 기준 `+09:00` |

- 요청 시각의 오프셋이 없거나 형식을 해석할 수 없으면 `400 VALIDATION_ERROR`다. 응답은 `+09:00`으로 반환한다. 내부 저장·비교 기준은 [ADR-0009](adr/platform/0009-utc-time-standard.md)를 따른다.
- `gameId`는 BoardGameGeek의 `bggId`가 아니라 알밤메이트 내부 게임 ID다. 자세한 구분은 [4.4 GameSummary](#44-gamesummary)를 따른다.
P0는 다음 HTTP 상태 코드를 사용한다.

| HTTP | 용도 |
|---:|---|
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

- P0에서 요청 본문으로 기존 리소스의 일부를 수정하는 API는 `PATCH`를 사용한다. 이후 수정 API의 `PUT`·`PATCH` 선택과 종료 명령의 재시도 기준은 [ADR-0022](adr/platform/0022-p0-update-api-http-method-and-finish-idempotency.md)를 따른다.

JSON 필드는 camelCase를 사용한다. 저장 컬럼(snake_case)과의 대응은 [ERD 테이블 명세](ERD.md#테이블-명세)를 정본으로 한다.

### 1.2 인증·세션·CSRF

P0는 서버 세션 인증을 사용한다. Bearer access token과 refresh token은 사용하지 않는다. 세부 기준은 [ADR-0003](adr/auth/0003-p0-server-session-spring-security.md)을 따른다.

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
| 공개 `POST`·`PATCH`·`DELETE` | 불필요 | 필요 |
| 보호 `POST`·`PATCH`·`DELETE` | 필요 | 필요 |

- 상태 변경 요청은 자동 전송되는 `XSRF-TOKEN` 쿠키와, `headerName`이 지정한 헤더에 담은 `token` 값을 함께 전달한다. 클라이언트는 회원가입·로그인 전에 공개 API인 `GET /api/auth/csrf`를 먼저 호출한다. 비로그인 CSRF 조회는 `JSESSIONID`와 서버 세션을 생성하지 않는다.
- 로그인 성공 시 세션 ID를 교체하고 새 `JSESSIONID`를 설정한다. 로그아웃은 서버 세션과 인증 상태를 무효화하고 `JSESSIONID`를 만료시킨다.
- 로그인과 로그아웃 성공 시 기존 CSRF 토큰이 무효화되므로, 다음 상태 변경 요청 전에 `GET /api/auth/csrf`를 다시 호출한다.
- 세션이 없거나 만료·무효화된 상태로 보호 API를 호출하면 `UNAUTHENTICATED`, CSRF 토큰이 없거나 유효하지 않으면 `CSRF_TOKEN_INVALID`를 반환한다.
- **오류 우선순위:** 보호 API에서 유효한 세션이 없으면 CSRF 토큰의 유효 여부와 무관하게 `UNAUTHENTICATED`를 `CSRF_TOKEN_INVALID`보다 우선한다.

### 1.3 공통 응답

모든 API는 공통 응답 객체를 반환한다. `status`는 실제 HTTP 상태 코드와 같으며, 생성 성공 API는 HTTP `201 Created`와 `"status": 201`을 사용한다.

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
| `code` | string | Y | N | [오류 코드](#9-오류-코드)의 코드 |
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
- P0 목록 API는 아래 고정 정렬을 적용한다. 모든 정렬은 마지막에 내부 `id`를 고유 tie-breaker로 사용해 페이지 이동 중 순서가 임의로 바뀌지 않게 한다.

| API | 고정 정렬 |
|---|---|
| `GET /api/games` | `name ASC, id ASC` |
| `GET /api/rooms` | 상태 보정과 필터를 적용한 뒤 `startsAt ASC, id ASC` |
| `GET /api/users/me/rooms` | 상태 보정, `role` 필터와 중복 제거를 적용한 뒤 `startsAt DESC, id DESC` |

## 2. API 인덱스

기능 ID는 엔드포인트가 아니라 기능 단위다. 로그인·로그아웃은 함께 `AUTH-03`, 프로필 조회·수정은 함께 `AUTH-04`, 방 취소·종료는 함께 `ROOM-05`에 속한다. 각 기능의 제품 규칙 정본은 링크한 `docs/p0/` 문서다.

`단계`는 API 도입 제품 단계이며 현재는 모두 `P0`다. 이후 API는 도입 단계를 표기하고(→ [PRD 로드맵](PRD.md#6-단계별-로드맵)), 단계가 늘어도 현재 유효한 전체 HTTP 계약인 이 파일을 나누지 않고 표에 행·단계 값을 더한다.

| # | 단계 | 기능 ID | Method | Path | 인증 | CSRF | 성공 |
|---:|:---:|---|---|---|:---:|:---:|:---:|
| 1 | P0 | [AUTH-01](#auth-01-csrf-토큰-조회) · [정본](p0/auth-profile.md#auth-01-csrf-토큰-조회) | GET | `/api/auth/csrf` | N | N | 200 |
| 2 | P0 | [AUTH-02](#auth-02-회원가입) · [정본](p0/auth-profile.md#auth-02-회원가입) | POST | `/api/auth/signup` | N | Y | 201 |
| 3 | P0 | [AUTH-03](#auth-03-로그인) · [정본](p0/auth-profile.md#auth-03-로그인로그아웃) | POST | `/api/auth/login` | N | Y | 200 |
| 4 | P0 | [AUTH-03](#auth-03-로그아웃) · [정본](p0/auth-profile.md#auth-03-로그인로그아웃) | POST | `/api/auth/logout` | Y | Y | 200 |
| 5 | P0 | [AUTH-04](#auth-04-내-프로필-조회) · [정본](p0/auth-profile.md#auth-04-내-프로필-조회수정) | GET | `/api/users/me` | Y | N | 200 |
| 6 | P0 | [AUTH-04](#auth-04-내-프로필-수정) · [정본](p0/auth-profile.md#auth-04-내-프로필-조회수정) | PATCH | `/api/users/me` | Y | Y | 200 |
| 7 | P0 | [GAME-01](#game-01-게임-목록검색) · [정본](p0/game-catalog.md#game-01-게임-목록검색) | GET | `/api/games` | N | N | 200 |
| 8 | P0 | [GAME-02](#game-02-게임-상세-조회) · [정본](p0/game-catalog.md#game-02-게임-상세-조회) | GET | `/api/games/{gameId}` | N | N | 200 |
| 9 | P0 | [ROOM-03](#room-03-방-생성) · [정본](p0/room.md#room-03-방-생성) | POST | `/api/rooms` | Y | Y | 201 |
| 10 | P0 | [ROOM-01](#room-01-방-목록-조회) · [정본](p0/room.md#room-01-방-탐색) | GET | `/api/rooms` | 선택 | N | 200 |
| 11 | P0 | [ROOM-02](#room-02-방-상세-조회) · [정본](p0/room.md#room-02-방-상세) | GET | `/api/rooms/{roomId}` | 선택 | N | 200 |
| 12 | P0 | [ROOM-04](#room-04-방-수정) · [정본](p0/room.md#room-04-방-수정) | PATCH | `/api/rooms/{roomId}` | Y | Y | 200 |
| 13 | P0 | [ROOM-05](#room-05-방-취소) · [정본](p0/room.md#room-05-방-취소종료) | DELETE | `/api/rooms/{roomId}` | Y | Y | 200 |
| 14 | P0 | [ROOM-05](#room-05-방-종료) · [정본](p0/room.md#room-05-방-취소종료) | PATCH | `/api/rooms/{roomId}/status` | Y | Y | 200 |
| 15 | P0 | [PART-01](#part-01-방-참가재참가) · [정본](p0/participation.md#part-01-방-참가재참가) | POST | `/api/rooms/{roomId}/participants` | Y | Y | 201 |
| 16 | P0 | [PART-02](#part-02-참가-취소) · [정본](p0/participation.md#part-02-참가-취소) | DELETE | `/api/rooms/{roomId}/participants/me` | Y | Y | 200 |
| 17 | P0 | [PART-03](#part-03-내-모임-조회) · [정본](p0/participation.md#part-03-내-모임-조회) | GET | `/api/users/me/rooms` | Y | N | 200 |

`GET /api/rooms`와 `GET /api/rooms/{roomId}`의 인증은 "선택"이다. 비로그인도 호출할 수 있고, 유효한 세션이 있으면 요청자 기준으로 `joinable`과 응답 범위를 계산한다.

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

권장 표시값이며 검색 필터나 참가 제한으로 사용하지 않는다.

### RoomStatus

| 값 | 의미 |
|---|---|
| `RECRUITING` | 모집 중이며 시작 전 참가할 수 있는 상태 |
| `CLOSED` | 정원 충족 또는 시작 시각 도달로 모집이 종료된 상태 |
| `CANCELED` | 주최자가 취소한 최종 상태 |
| `FINISHED` | 종료된 최종 상태 |

클라이언트가 관찰하는 상태 변화는 다음과 같다. 제품 규칙 정본은 [P0-spec 방 상태](P0-spec.md#방-상태roomstatus), 저장 반영 방식은 [ADR-0012](adr/room/0012-room-request-boundary-state-reconciliation.md)를 따른다.

| 조건 또는 요청 | 이전 상태 | 이후 상태 |
|---|---|---|
| 방 생성 성공 | 생성 전 | `RECRUITING` |
| 모집 인원 충족 | `RECRUITING` | `CLOSED` |
| 현재 시각이 `startsAt`에 도달 | `RECRUITING` | `CLOSED` |
| 시작 전 참가 취소로 빈자리 발생 | `CLOSED` | `RECRUITING` |
| 주최자가 방 취소 | `RECRUITING` 또는 `CLOSED` | `CANCELED` |
| 주최자가 시작 시각 이후 방 종료 | `CLOSED` | `FINISHED` |
| 현재 시각이 `startsAt + 24시간`에 도달 | `CLOSED` | `FINISHED` |

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

## 4. 공통 스키마

응답 스키마 표에서 `필수 Y`는 필드가 응답에 항상 포함됨을, `nullable Y`는 값으로 JSON `null`을 허용함을 뜻한다. 이 절의 필드는 모두 응답 값이며, 계산으로 도출하는 필드의 계산식 정본은 [ERD 정원·참가자 표시 규칙](ERD.md#정원참가자-표시-규칙)과 [서비스 규칙](ERD.md#서비스-규칙)이다.

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

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `id` | integer | Y | N | 알밤메이트 내부 게임 ID |
| `bggId` | integer | Y | N | BoardGameGeek 식별자 |
| `name` | string | Y | N | 게임명 |
| `englishName` | string | Y | N | 영문명 |
| `imageUrl` | string | Y | Y | 대표 이미지 URL |
| `supportedPlayerCount` | string | Y | N | 표시용 가능 인원. 게임 규칙상 플레이 가능한 범위 (예: `2~4명`) |
| `tag` | string | Y | N | 표시용 게임 스타일 태그. 목록 필터로 사용하지 않는다 |
| `estimatedPlayTime` | string | Y | N | 표시용 예상 시간 (예: `30분`) |
| `complexity` | number | Y | Y | 난이도 표시값 |
| `upcomingRoomCount` | integer | Y | N | 미래 시점의 `GAME_FOCUSED` 방 중 `CANCELED`·`FINISHED`가 아닌 건수 |

### 4.6 GameDetail

`GameListItem`의 모든 필드와 다음 필드를 포함한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `alias` | string | Y | Y | 게임 별칭 |
| `description` | string | Y | N | 간단 설명 |
| `detailDescription` | string | Y | N | 상세 설명 |

### 4.7 PublicRoomResponse

방을 탐색·참가 판단하는 데 필요한 비식별 정보만 반환한다. `place`, 주최자·참가자 목록과 사용자 ID는 포함하지 않는다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `id` | integer | Y | N | 방 ID |
| `roomType` | RoomType | Y | N | 방 유형 |
| `title` | string | Y | N | 방 제목 |
| `description` | string | Y | Y | 모임 소개 |
| `game` | GameSummary | Y | Y | `GAME_FOCUSED`는 필수, `PERSON_FOCUSED`는 `null` 가능 |
| `experienceLevel` | ExperienceLevel | Y | N | 권장 경험 수준 |
| `isRulemasterLed` | boolean | Y | N | 룰마스터 진행 여부 |
| `startsAt` | string(date-time) | Y | N | 시작 시각 |
| `region` | string | Y | N | P0 고정값 `홍대` |
| `recruitmentCapacity` | integer | Y | N | 주최자를 제외한 모집 인원, 1~10 |
| `participantCount` | integer | Y | N | 주최자 1명 + 현재 `ACTIVE` 참가 관계 수 |
| `remainingRecruitmentSeats` | integer | Y | N | `recruitmentCapacity − 현재 ACTIVE 참가 관계 수` |
| `status` | RoomStatus | Y | N | 현재 방 상태 |
| `joinable` | boolean | Y | N | 현재 요청자의 참가 가능 여부. 판정 규칙은 아래 참고 |

`joinable`은 다음을 **모두** 만족할 때만 `true`이고, 그 외에는 `false`다.

1. 요청자가 로그인했다.
2. 요청자가 주최자도, 현재 `ACTIVE` 참가자도 아니다.
3. 방 상태가 `RECRUITING`이다.
4. 현재 시각이 `startsAt`보다 이르다(`now < startsAt`).
5. `remainingRecruitmentSeats`가 1 이상이다.

기존 `CANCELED` 참가 관계를 가진 사용자도 위 조건을 만족하면 재참가할 수 있어 `true`다.

### 4.8 ParticipantRoomResponse

주최자 또는 현재 `ACTIVE` 참가자에게 반환하며, `PublicRoomResponse`의 모든 필드에 다음을 추가한다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `myRole` | MyRole | Y | N | 요청자와 방의 관계 |
| `place` | string | Y | N | 정확한 장소 |
| `host` | NicknameSummary | Y | N | 주최자 |
| `participants` | NicknameSummary[] | Y | N | 주최자와 현재 `ACTIVE` 참가자 |

`host`와 `participants`는 사용자 ID를 포함하지 않으므로, 클라이언트는 `myRole`로 요청자의 역할을 판정한다. ROOM-02 상세 조회는 주최자에게 `HOST`, 현재 `ACTIVE` 참가자에게 `JOINED`를 반환한다. 주최자 전용인 ROOM-03 생성과 ROOM-04 수정 응답은 항상 `HOST`다.

### 4.9 RoomParticipationResponse

참가·참가 취소 요청의 응답이다. 모든 값은 참가 관계 변경과 모집 상태 전이가 끝난 뒤의 값이다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 방 ID |
| `participationStatus` | ParticipationStatus | Y | N | 변경 후 참가 상태. 참가 성공은 `ACTIVE`, 취소 성공은 `CANCELED` |
| `roomStatus` | RoomStatus | Y | N | 변경 후 방 상태 |
| `participantCount` | integer | Y | N | 변경 후 전체 참가자 수 |
| `remainingRecruitmentSeats` | integer | Y | N | 변경 후 남은 모집 자리 |

마지막 좌석을 채우는 참가라면 `roomStatus`·`participantCount`·`remainingRecruitmentSeats`는 각각 `CLOSED`, 최종 참가자 수, `0`이 된다.

### 4.10 MyRoomListItem

`GET /api/users/me/rooms`의 각 항목이며, `PublicRoomResponse`의 모든 필드에 다음을 추가한다. 정확한 `place`와 참가자 목록은 내 모임 이력에도 포함하지 않는다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `myRole` | MyRole | Y | N | `HOST` 또는 `JOINED` |
| `participationStatus` | ParticipationStatus | Y | Y | `myRole = JOINED`이면 항상 `ACTIVE`, `HOST`이면 `null` |

`joinable`은 `PublicRoomResponse`와 같은 요청자 기준 값이다. `JOINED` 항목은 현재 `ACTIVE` 참가자이므로 항상 `false`다.

### 4.11 RoomStatusResponse

방 취소·종료 응답이다.

| 필드 | 타입 | 필수 | nullable | 설명 |
|---|---|:---:|:---:|---|
| `roomId` | integer | Y | N | 방 ID |
| `roomStatus` | RoomStatus | Y | N | 변경 후 상태 |

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

## 6. 게임 API

게임 데이터는 운영자가 준비한다. 사용자용 게임 생성·수정·삭제 API는 제공하지 않는다(→ [GAME-01 정본](p0/game-catalog.md#game-01-게임-목록검색), 게임 목록 출처 [ADR-0015](adr/game/0015-bgg-baseline-team-collected-game-list.md)).

### GAME-01 게임 목록·검색

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/games` |
| 인증 / CSRF | 불필요 / 불필요 |
| 성공 | `200 OK`, `data`: `PageResponse<GameListItem>` |

#### Query Parameters

| 이름 | 타입 | 필수 | 기본값 | 의미 |
|---|---|:---:|---|---|
| `keyword` | string | N | 검색 없음 | 게임명 부분 일치 |
| `upcomingOnly` | boolean | N | `false` | `true`이면 예정 모임이 한 개 이상인 게임만 반환 |
| `page` | integer | N | `0` | 페이지 번호 |
| `size` | integer | N | `10` | 페이지 크기, 1~100 |

`keyword`와 `upcomingOnly=true`를 함께 사용하면 두 조건을 모두 적용하며, 페이지 메타데이터는 필터 결과를 기준으로 계산한다.

인원·시간·난이도·태그 필터와 `sort`는 지원하지 않는다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| query parameter 검증 실패 | 400 | `VALIDATION_ERROR` |

### GAME-02 게임 상세 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/games/{gameId}` |
| 인증 / CSRF | 불필요 / 불필요 |
| 성공 | `200 OK`, `data`: `GameDetail` |

#### Path Variables

| 이름 | 타입 | 필수 | 검증 |
|---|---|:---:|---|
| `gameId` | integer | Y | 1 이상의 알밤메이트 내부 게임 ID |

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| path ID 형식·범위 검증 실패 | 400 | `VALIDATION_ERROR` |
| 게임이 없음 | 404 | `GAME_NOT_FOUND` |

## 7. 방 API

### ROOM-01 방 목록 조회

| 항목 | 값 |
|---|---|
| Method / Path | `GET /api/rooms` |
| 인증 / CSRF | 선택 / 불필요 |
| 성공 | `200 OK`, `data`: `PageResponse<PublicRoomResponse>` |

유효한 세션이 있으면 요청자 기준으로 `joinable`을 계산한다.

#### Query Parameters

| 이름 | 타입 | 필수 | 적용 조건 | 의미 |
|---|---|:---:|---|---|
| `type` | RoomType | N | 전달 시 | 방 유형 |
| `gameId` | integer | N | 전달 시 | 1 이상의 알밤메이트 내부 게임 ID |
| `keyword` | string | N | 전달 시 | 방 제목 부분 일치 |
| `page` | integer | N | 항상 | 기본값 `0` |
| `size` | integer | N | 항상 | 기본값 `10`, 1~100 |

`type`, `gameId`, `keyword`는 서로 독립적인 선택 필터이며, 전달된 조건만 모두 만족하는 방을 반환한다. 세 값을 모두 생략하면 두 유형의 공개 방 전체를 반환한다. `keyword`의 빈 문자열과 공백은 검색 조건 없음으로 처리하며, 제목 부분 일치는 대소문자를 구분하지 않는다.

- 잘못된 enum, `gameId` 0 이하, `page`·`size` 범위 위반, 숫자 바인딩 실패 또는 허용하지 않는 parameter는 `VALIDATION_ERROR`다.
- `keyword`는 방 제목 검색이며, P0에서 제외한 조건 필터가 아니다.
- 공개 목록은 `RECRUITING`, `CLOSED` 방만 반환한다.
- `playerCount`, `playTime`, `region`, `experienceLevel`, `tag`, `categoryIds`, `bggWeightMin`, `bggWeightMax`, `sort`는 P0 쿼리 파라미터가 아니다.

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

## 8. 참가·내 모임 API

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

- 본인만 `now < startsAt`일 때 취소할 수 있다. 시작 전 빈자리가 생기면 `CLOSED → RECRUITING`으로 자동 복귀한다.
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

참가 취소한 `CANCELED` 관계와 방이 취소된 `CANCELED` 방은 `joined`에서 제외한다.

#### 오류

| 발생 조건 | HTTP | code |
|---|---:|---|
| 세션이 없거나 유효하지 않음 | 401 | `UNAUTHENTICATED` |
| query parameter 검증 실패 | 400 | `VALIDATION_ERROR` |
| 동시 변경으로 방 상태를 확인할 수 없음 | 409 | `ROOM_CONCURRENT_MODIFICATION` |

## 9. 오류 코드

오류 코드는 클라이언트가 실패 원인을 식별하는 안정적인 외부 계약이다.

- `code`는 `UPPER_SNAKE_CASE`를 사용하고 전체 API에서 유일하다. 같은 코드를 다른 의미나 HTTP 상태로 재사용하지 않는다.
- `status`는 실제 HTTP 상태 및 실패 응답 본문의 `status`와 일치한다.
- `message`는 아래 카탈로그의 한국어 기본 메시지를 사용한다. 클라이언트는 메시지가 아니라 `code`로 분기한다.
- 코드의 소유 도메인은 호출한 엔드포인트가 아니라 실패 규칙의 소유자를 기준으로 정한다. 예를 들어 방 생성 중 선택한 게임이 없으면 게임 도메인의 `GAME_NOT_FOUND`를 반환한다.
- 오류 코드를 추가하거나 의미·HTTP 상태·기본 메시지를 변경하면 이 카탈로그와 [엔드포인트별 오류 매트릭스](#10-부록-엔드포인트별-오류-매트릭스)를 함께 갱신한다.

### 9.1 공통 오류

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

`METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` 응답은 Spring MVC 예외가 제공하는 `Allow`, `Accept`, `Accept-Patch` 등의 프로토콜 헤더가 있으면 그대로 포함한다.

### 9.2 인증·회원 오류

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호가 일치하지 않습니다. | 로그인 이메일 또는 비밀번호가 일치하지 않음 |
| `EMAIL_ALREADY_EXISTS` | 409 | 이미 사용 중인 이메일입니다. | 회원가입 이메일이 이미 사용 중임(정규화된 값 기준) |
| `RATE_LIMIT_EXCEEDED` | 429 | 인증 요청 처리 한도를 초과했습니다. 잠시 후 다시 시도해 주세요. | 인증 요청 횟수 또는 비밀번호 해시 동시 실행 한도 초과 |

`Retry-After` 계산은 [인증 요청 남용 제한](#인증-요청-남용-제한)을 따른다.

### 9.3 게임 오류

| code | HTTP | 기본 message | 발생 조건 |
|---|---:|---|---|
| `GAME_NOT_FOUND` | 404 | 게임을 찾을 수 없습니다. | 요청한 게임이 없음 |

### 9.4 방 오류

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

### 9.5 참가 오류

정확한 판정 순서는 [PART-01](#part-01-방-참가재참가)과 [PART-02](#part-02-참가-취소)의 오류 판정 순서를 따른다. 아래 발생 조건은 각 코드의 의미 요약이며 독립적인 충분조건이 아니다.

| code | HTTP | 기본 message | 대표 발생 조건 |
|---|---:|---|---|
| `PARTICIPATION_NOT_FOUND` | 404 | 현재 참가 정보를 찾을 수 없습니다. | 현재 `ACTIVE`인 본인 참가 관계가 없음 |
| `CAPACITY_EXCEEDED` | 409 | 모집 가능한 인원을 초과했습니다. | 방의 모집 인원을 초과하는 참가 시도 |
| `ROOM_NOT_RECRUITING` | 409 | 현재 모집 중인 방이 아닙니다. | 모집 중이 아니거나 참가 가능 시간이 지난 방 참가 시도 |
| `ALREADY_PARTICIPATING` | 409 | 이미 참가 중인 방입니다. | 요청자가 주최자이거나, 같은 방에 `ACTIVE` 참가 관계가 있는데 다시 참가 시도 |

## 10. 부록: 엔드포인트별 오류 매트릭스

각 엔드포인트가 반환할 수 있는 오류 코드의 전체 인덱스다. 개별 판정 순서는 각 API 절을, 코드 정의는 [9. 오류 코드](#9-오류-코드)를 따른다.

| API | 오류 코드 |
|---|---|
| 모든 엔드포인트 | `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_SERVER_ERROR` |
| 요청 경로에 대응하는 엔드포인트 또는 정적 리소스 없음 | `RESOURCE_NOT_FOUND` |
| `GET /api/auth/csrf` | 없음 |
| `POST /api/auth/signup` | `VALIDATION_ERROR`, `EMAIL_ALREADY_EXISTS`, `RATE_LIMIT_EXCEEDED`, `CSRF_TOKEN_INVALID` |
| `POST /api/auth/login` | `VALIDATION_ERROR`, `INVALID_CREDENTIALS`, `RATE_LIMIT_EXCEEDED`, `CSRF_TOKEN_INVALID` |
| `POST /api/auth/logout` | `UNAUTHENTICATED`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me` | `UNAUTHENTICATED` |
| `PATCH /api/users/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `CSRF_TOKEN_INVALID` |
| `GET /api/games` | `VALIDATION_ERROR` |
| `GET /api/games/{gameId}` | `VALIDATION_ERROR`, `GAME_NOT_FOUND` |
| `POST /api/rooms` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `GAME_NOT_FOUND`, `CSRF_TOKEN_INVALID` |
| `GET /api/rooms` | `VALIDATION_ERROR`, `ROOM_CONCURRENT_MODIFICATION` |
| `GET /api/rooms/{roomId}` | `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION` |
| `PATCH /api/rooms/{roomId}` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `GAME_NOT_FOUND`, `VALIDATION_ERROR`, `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/rooms/{roomId}` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `PATCH /api/rooms/{roomId}/status` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `VALIDATION_ERROR`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `POST /api/rooms/{roomId}/participants` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `ALREADY_PARTICIPATING`, `ROOM_NOT_RECRUITING`, `CAPACITY_EXCEEDED`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/rooms/{roomId}/participants/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_NOT_FOUND`, `PARTICIPATION_NOT_FOUND`, `FORBIDDEN`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me/rooms` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_CONCURRENT_MODIFICATION` |

- `GET /api/rooms/{roomId}`에서만 취소·종료 방을 권한 없는 사용자가 조회할 때 존재 여부를 숨기기 위해 `ROOM_NOT_FOUND`를 반환한다. 그 외 주최자 전용 쓰기 API의 비주최자 요청은 `FORBIDDEN`을 반환한다.
- `PATCH /api/rooms/{roomId}`의 `GAME_NOT_FOUND`는 요청에 `gameId`를 포함했을 때만 적용한다.
