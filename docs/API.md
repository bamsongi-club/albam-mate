# 알밤메이트 P0 API 명세서

- 최종 수정일: 2026-07-24
- 문서 상태: **P0 1차 MVP API 계약**
- 기준 문서: [PRD](PRD.md), [P0 공통 명세](P0-spec.md), [기능별 P0 명세](P0-spec.md#관련-문서), [ERD](ERD.md)

> 이 문서는 P0 1차 MVP의 17개 API만 정의한다.

## 1. 공통 규칙

- 기본 경로는 `/api`다.
- 요청·응답은 `application/json`이다.
- JSON은 camelCase, DB 컬럼은 snake_case를 사용한다.

| API JSON | DB 컬럼 |
|---|---|
| `englishName` | `english_name` |
| `imageUrl` | `image_url` |
| `recommendedPlayerCount` | `recommended_player_count` |
| `estimatedPlayTime` | `estimated_play_time` |
| `detailDescription` | `detail_description` |
| `isRulemasterLed` | `is_rulemaster_led` |
| `recruitmentCapacity` | `capacity` |
| `startsAt` | `start_at` |
| `place` | `place` |

- 요청과 응답의 시각은 ISO 8601 오프셋 날짜·시간(RFC 3339 `date-time`) 형식을 사용한다. 요청은 `Z` 또는 `±HH:MM` 오프셋을 반드시 포함해야 하며, 오프셋이 없거나 형식을 해석할 수 없으면 `VALIDATION_ERROR`로 거절한다. 서버는 요청 시각을 `Instant`로 정규화하고, P0 응답은 `Asia/Seoul` 기준 `+09:00`으로 반환한다.
- 사용자·게임·방·참가 관계의 ID는 1부터 증가하는 양의 정수다. JSON에서는 숫자로, 경로에서는 10진 정수로 전달한다. UUID는 사용하지 않는다.
- 검증 오류는 `400`, 미인증은 `401`, 권한 없음은 `403`, 대상 없음은 `404`, 상태·정합성 충돌은 `409`를 사용한다.

### 1.1 인증·세션·CSRF

- P0 인증 상태는 서버 세션으로 관리하고 브라우저는 `JSESSIONID` 쿠키로 세션을 전달한다. Bearer access token과 refresh token은 사용하지 않는다.
- `JSESSIONID`에는 `Path=/`, `HttpOnly`, `SameSite=Lax`를 적용하고 운영 HTTPS 환경에서는 `Secure`를 적용한다. P0는 웹과 API의 same-site 배포를 전제로 하며 cross-site 배포는 지원하지 않는다.
- 로그인 성공 시 세션 ID를 교체하고 응답에 새 `JSESSIONID`를 설정한다. 세션이 없거나 만료·무효화된 상태로 보호 API를 호출하면 `UNAUTHENTICATED`를 반환한다.
- CSRF 토큰은 서버 세션이 아닌 Host-only `XSRF-TOKEN` 쿠키에 저장한다. 쿠키에는 `Path=/`, `HttpOnly`, `SameSite=Lax`를 적용하고 운영 HTTPS 환경에서는 `Secure`를 적용한다.
- `POST`, `PATCH`, `DELETE` 요청은 인증 여부와 관계없이 CSRF 토큰을 요구한다. 클라이언트는 먼저 공개 API인 `GET /api/auth/csrf`를 호출하고, 응답의 `headerName`과 `token` 값을 이후 요청 헤더에 그대로 전달한다. 비로그인 CSRF 조회는 `JSESSIONID`와 서버 세션을 생성하지 않는다.
- 로그인과 로그아웃 성공 시 기존 CSRF 토큰이 무효화되므로, 다음 상태 변경 요청 전에 `GET /api/auth/csrf`를 다시 호출한다.
- CSRF 토큰이 없거나 유효하지 않으면 `CSRF_TOKEN_INVALID`를 반환한다. 보호 API에서는 유효한 세션이 없는 경우의 `UNAUTHENTICATED`를 CSRF 오류보다 우선한다.
- 로그아웃은 서버 세션과 인증 상태를 무효화하고 `JSESSIONID`를 만료시킨다.

### 1.2 공통 응답

모든 API는 아래 공통 응답 객체를 반환한다. `status`는 실제 HTTP 상태 코드와 같으며, 생성 성공 API는 HTTP `201 Created`와 `"status": 201`을 사용한다.

성공 응답 예시:

~~~json
{
  "status": 200,
  "data": {}
}
~~~

실패 응답 예시:

~~~json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "요청값 검증에 실패했습니다.",
  "data": null
}
~~~

- 성공 응답의 `data`에는 각 API의 응답 모델을 넣는다. 본문으로 반환할 값이 없으면 빈 객체 `{}`를 넣는다.
- 실패 응답의 `data`는 항상 `null`이다. `code`는 [오류 코드와 검증](#5-오류-코드와-검증)의 코드, `message`는 해당 오류를 설명하는 한국어 메시지다.

### 1.3 페이지네이션

목록 API는 기본적으로 아래 쿼리 파라미터를 사용한다.

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---:|---|
| `page` | int | 0 | 0부터 시작하는 페이지 번호 |
| `size` | int | 10 | 페이지 크기. 1 이상 100 이하 |

`page`가 음수이거나 `size`가 1~100 범위를 벗어나면 `VALIDATION_ERROR`를 반환한다.

페이지 응답 예시:

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

- `content`는 해당 목록 항목의 배열이며, 결과가 없으면 빈 배열이다.
- `hasNext`는 다음 페이지가 있으면 `true`다. `first`, `last` 필드는 반환하지 않는다.
- P0 목록 API는 클라이언트 `sort` 파라미터를 받지 않고 아래 기본 정렬을 적용한다. 모든 정렬은 마지막에 내부 `id`를 고유 tie-breaker로 사용해 페이지 이동 중 순서가 임의로 바뀌지 않게 한다.

| API | 기본 정렬 |
|---|---|
| `GET /api/games` | `name ASC, id ASC` |
| `GET /api/rooms` | 상태 보정과 필터 적용 뒤 `startsAt ASC, id ASC` |
| `GET /api/users/me/rooms` | 상태 보정, `role` 필터와 중복 제거 뒤 `startsAt DESC, id DESC` |

## 2. P0 API 목록 (17개)

| # | 도메인 | 메서드 | 경로 | 인증 | 기능 |
|---:|---|---|---|:---:|---|
| 1 | 인증 | GET | `/api/auth/csrf` | N | CSRF 토큰 조회 |
| 2 | 인증 | POST | `/api/auth/signup` | N | 회원가입 |
| 3 | 인증 | POST | `/api/auth/login` | N | 로그인·세션 생성 |
| 4 | 인증 | POST | `/api/auth/logout` | Y | 로그아웃·세션 무효화 |
| 5 | 프로필 | GET | `/api/users/me` | Y | 본인 프로필 조회 |
| 6 | 프로필 | PATCH | `/api/users/me` | Y | 본인 프로필 수정 |
| 7 | 게임 | GET | `/api/games` | N | 게임 목록·게임명 검색 |
| 8 | 게임 | GET | `/api/games/{gameId}` | N | 게임 상세 |
| 9 | 방 | POST | `/api/rooms` | Y | 게임·사람 중심 방 생성 |
| 10 | 방 | GET | `/api/rooms` | N | 유형별 방 목록 |
| 11 | 방 | GET | `/api/rooms/{roomId}` | N | 방 상세·참가자 목록 |
| 12 | 방 | PATCH | `/api/rooms/{roomId}` | Y | 주최자 방 수정 |
| 13 | 방 | DELETE | `/api/rooms/{roomId}` | Y | 주최자 방 취소 |
| 14 | 방 | PATCH | `/api/rooms/{roomId}/status` | Y | 주최자 방 종료 |
| 15 | 참가 | POST | `/api/rooms/{roomId}/participants` | Y | 선착순 참가 |
| 16 | 참가 | DELETE | `/api/rooms/{roomId}/participants/me` | Y | 본인 참가 취소 |
| 17 | 내 모임 | GET | `/api/users/me/rooms?role=all\|joined\|hosted&page=0&size=10` | Y | 참여·개설 방 이력 |

## 3. 응답 모델

이 절의 응답 모델은 공통 응답 객체의 `data` 값이다. 목록 API는 1.3절의 페이지 응답 `data.content`에 각 항목 모델을 넣는다.

### 3.1 UserSummary

~~~json
{
  "id": 1,
  "nickname": "알밤"
}
~~~

P0 프로필은 닉네임만 제공·수정한다. 이메일과 인증 정보는 응답에 포함하지 않는다.

### 3.2 CsrfTokenResponse

~~~json
{
  "headerName": "X-CSRF-TOKEN",
  "token": "발급된 CSRF 토큰"
}
~~~

- `headerName`은 다음 상태 변경 요청에 토큰을 전달할 HTTP 헤더 이름이다.
- `token`은 현재 `XSRF-TOKEN` 쿠키에 대응하는 CSRF 토큰이다. 로그인과 로그아웃 뒤에는 기존 값을 재사용하지 않는다.

### 3.3 GameListItem

~~~json
{
  "id": 1,
  "bggId": 148228,
  "name": "스플렌더",
  "englishName": "Splendor",
  "imageUrl": "https://example.com/games/splendor.png",
  "recommendedPlayerCount": "2~4명",
  "tag": "전략",
  "estimatedPlayTime": "30분",
  "complexity": 1.78,
  "upcomingRoomCount": 3
}
~~~

`upcomingRoomCount`는 미래 시점의 `GAME_FOCUSED` 방 중 `CANCELED`, `FINISHED`가 아닌 건수를 조회 시 계산한다. `games` 테이블에는 저장하지 않는다.

### 3.4 GameDetail

~~~json
{
  "id": 1,
  "bggId": 148228,
  "name": "스플렌더",
  "englishName": "Splendor",
  "alias": "스플",
  "imageUrl": "https://example.com/games/splendor.png",
  "recommendedPlayerCount": "2~4명",
  "tag": "전략",
  "estimatedPlayTime": "30분",
  "complexity": 1.78,
  "description": "보석을 모아 개발 카드를 구매하는 전략 게임입니다.",
  "detailDescription": "보석 토큰과 개발 카드를 조합해 점수를 얻는 게임입니다.",
  "upcomingRoomCount": 3
}
~~~

`bggId`는 BoardGameGeek가 부여한 외부 식별자다. `id`는 알밤메이트 내부 식별자이며, `/api/games/{gameId}`와 방 생성·수정 요청의 `gameId`에는 계속 내부 `id`를 사용한다. `GameListItem`, `GameDetail`, 방 응답의 `game` 요약은 같은 게임의 동일한 `bggId`를 반환한다.

`tag`는 표시값이다. P0 목록 API는 태그·인원·시간·복잡도 조건 필터를 받지 않는다.

### 3.5 방 응답

#### PublicRoomResponse

~~~json
{
  "id": 1,
  "roomType": "GAME_FOCUSED",
  "title": "토요일 저녁 스플렌더",
  "description": "처음 오신 분도 환영합니다.",
  "game": {"id": 1, "bggId": 148228, "name": "스플렌더"},
  "experienceLevel": "ALL_LEVELS",
  "isRulemasterLed": true,
  "startsAt": "2026-07-25T19:00:00+09:00",
  "region": "홍대",
  "recruitmentCapacity": 3,
  "participantCount": 2,
  "remainingRecruitmentSeats": 2,
  "status": "RECRUITING",
  "joinable": false
}
~~~

공개 응답은 방을 탐색·참가 판단하는 데 필요한 비식별 정보만 반환한다. `place`, 주최자, 참가자 목록과 사용자 ID는 포함하지 않는다.

#### ParticipantRoomResponse

주최자 또는 현재 `ACTIVE` 참가자가 상세를 조회하면 `PublicRoomResponse`의 모든 필드에 아래 필드가 추가된다.

~~~json
{
  "place": "홍대입구역 인근 보드게임 카페",
  "host": {"nickname": "알밤"},
  "participants": [
    {"nickname": "알밤"},
    {"nickname": "밤송이"}
  ]
}
~~~

- `GAME_FOCUSED`는 `game`이 필수다. `PERSON_FOCUSED`는 `game`이 null일 수 있다.
- `recruitmentCapacity`는 주최자를 제외한 모집 인원이다.
- `participantCount`는 주최자 1명과 현재 `ACTIVE` 참가 관계 수의 합이다.
- `participants`는 참가자 전용 상세에서만 반환하며 주최자와 현재 `ACTIVE` 참가자를 포함한다.
- `joinable`은 현재 요청자의 실제 참가 가능 여부다. 비로그인, 주최자, 이미 `ACTIVE`인 참가자, `RECRUITING`이 아닌 방, `now >= startsAt`, 남은 모집 자리가 없는 경우에는 `false`다. 로그인한 비주최자이며 현재 `ACTIVE`가 아니고 `RECRUITING && now < startsAt && remainingRecruitmentSeats > 0`일 때만 `true`다. 기존 `CANCELED` 참가 관계를 가진 유저도 이 조건이면 재참가할 수 있다.

#### RoomParticipationResponse

`POST /api/rooms/{roomId}/participants`와 `DELETE /api/rooms/{roomId}/participants/me`의 `data`는 아래 `RoomParticipationResponse`다.

~~~json
{
  "roomId": 1,
  "participationStatus": "ACTIVE",
  "roomStatus": "CLOSED",
  "participantCount": 4,
  "remainingRecruitmentSeats": 0
}
~~~

- 참가 성공 시 `participationStatus`는 `ACTIVE`, 참가 취소 성공 시 `CANCELED`다.
- `roomStatus`, `participantCount`, `remainingRecruitmentSeats`는 참가 관계 변경 또는 취소와 모집 상태 전이가 끝난 뒤의 값이다. 마지막 좌석 참가라면 각각 `CLOSED`, 최종 참가자 수, `0`이 된다.

#### MyRoomListItem

`GET /api/users/me/rooms`의 각 항목은 `PublicRoomResponse`에 아래 필드를 더한 형태다. 정확한 `place`와 참가자 목록은 내 모임 이력에도 포함하지 않는다.

| 필드 | 규칙 |
|---|---|
| `myRole` | `HOST` 또는 `JOINED` |
| `participationStatus` | `myRole = JOINED`이면 항상 `ACTIVE`, `HOST`이면 `null` |
| `joinable` | `PublicRoomResponse`와 같은 현재 요청자 기준 값. `JOINED` 항목은 현재 `ACTIVE` 참가자이므로 항상 `false`다. |

## 4. API 계약

### 4.1 인증과 프로필

#### 인증 요청 남용 제한

CSRF와 요청 형식 검증을 통과한 회원가입·로그인 요청에는 아래 제한을 적용한다. 제한은 비밀번호 해시나 사용자 생성보다 먼저 확인한다.

| 대상 | 제한 키 | 허용량 |
|---|---|---:|
| 회원가입 | 신뢰할 수 있는 원격 IP | 10분 이동 창당 5회. 성공·실패 요청을 모두 계산 |
| 로그인 | 신뢰할 수 있는 원격 IP | 10분 이동 창당 30회. 성공·실패 요청을 모두 계산 |
| 로그인 실패 | 정규화 이메일 + 신뢰할 수 있는 원격 IP | 10분 이동 창당 5회. 로그인 성공 시 해당 조합의 실패 횟수 초기화 |
| Argon2 작업 | 애플리케이션 인스턴스 | 동시 최대 4개 |

- 전달 헤더의 IP는 신뢰된 프록시를 명시적으로 설정한 경우에만 사용하고, 그 외에는 직접 연결의 원격 IP를 사용한다.
- 횟수 또는 동시 작업 한도를 초과하면 비밀번호 해시와 사용자 생성을 시작하지 않고 `429 RATE_LIMIT_EXCEEDED`를 반환한다. 응답에는 다시 요청할 수 있을 때까지의 초를 `Retry-After` 헤더로 포함한다. 동시 작업 슬롯 부족의 `Retry-After`는 `1`이다.
- 고정 계정 잠금은 사용하지 않는다. 창이 만료되면 해당 횟수를 제거하며, 존재하지 않는 이메일과 잘못된 비밀번호는 같은 실패 횟수와 `INVALID_CREDENTIALS` 규칙을 사용한다.

#### CSRF 토큰 조회

| API | 요청 핵심값 | 성공 응답 | 규칙 |
|---|---|---|---|
| `GET /api/auth/csrf` | 없음 | 200, `data: CsrfTokenResponse` | 비로그인 호출을 허용하고 서버 세션 없이 쿠키 기반 CSRF 토큰을 반환 |

클라이언트는 회원가입 또는 로그인 전에 `GET /api/auth/csrf`를 호출하고, 응답의 `token` 값을 `headerName`이 지정한 헤더에 담아 자동 전송되는 `XSRF-TOKEN` 쿠키와 함께 요청한다. 비로그인 상태에서는 이 과정에서 `JSESSIONID`가 생성되지 않는다. 로그인 성공 뒤에는 `GET /api/auth/csrf`를 다시 호출한다. 이후 모든 상태 변경 요청은 세션 쿠키와 현재 CSRF 토큰을 함께 전달한다.

#### 회원가입

| API | 요청 핵심값 | 성공 응답 | 규칙 |
|---|---|---|---|
| `POST /api/auth/signup` | email, password, nickname | 201, `data: UserSummary` | 계정만 생성하며 로그인 세션은 만들지 않음. 이메일 중복은 409 |

`SignupRequest`는 아래 계약을 사용한다.

| 필드 | 타입 | 필수 / `null` | 정규화·검증 |
|---|---|---|---|
| `email` | string | 필수 / 불가 | 앞뒤 공백을 제거하고 소문자로 변환한다. 이메일 형식이어야 하며 정규화 뒤 255자 이하다. 중복도 정규화된 값으로 판정한다. |
| `password` | string | 필수 / 불가 | 15자 이상 128자 이하이다. Unicode와 공백을 허용하고 앞뒤 공백 제거, Unicode 정규화, 자동 잘라내기를 하지 않는다. |
| `nickname` | string | 필수 / 불가 | 앞뒤 공백을 제거한 뒤 1자 이상 50자 이하이며 제어문자를 허용하지 않는다. |

- 비밀번호 원문은 저장·응답·로그에 남기지 않는다. 저장값은 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)의 `DelegatingPasswordEncoder`와 Argon2id 계약으로 생성한다.
- 비밀번호는 영문 대·소문자, 숫자, 특수문자의 조합을 강제하지 않으며 입력값을 조용히 변경하거나 잘라내지 않는다.

#### 로그인·로그아웃

| API | 요청 핵심값 | 성공 응답 | 규칙 |
|---|---|---|---|
| `POST /api/auth/login` | email, password | 200, `data: UserSummary` | 세션 ID 교체와 `JSESSIONID` 설정, 잘못된 자격증명은 401 |
| `POST /api/auth/logout` | 없음 | 200, `data: {}` | 서버 세션·인증 상태 무효화와 `JSESSIONID` 만료 |

- 로그인 `email`은 회원가입과 같은 방식으로 정규화한다.
- 로그인 `password`는 필수·`null` 불가이며 1자 이상 128자 이하로 받는다. 공백을 포함한 원문을 변경하거나 잘라내지 않는다. 필드 누락·`null`·빈 문자열·길이 초과는 `VALIDATION_ERROR`, 형식은 유효하지만 저장된 해시와 일치하지 않으면 `INVALID_CREDENTIALS`다.
- 존재하지 않는 이메일도 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)의 동일한 Argon2id 설정으로 미리 만든 더미 해시를 사용해 `PasswordEncoder.matches`를 수행한다. 계정 유무에 따른 빠른 실패 경로를 만들지 않으며 결과는 다른 잘못된 자격증명과 같은 `INVALID_CREDENTIALS`다.

#### 내 프로필 조회·수정

| API | 요청 핵심값 | 성공 응답 | 규칙 |
|---|---|---|---|
| `GET /api/users/me` | 없음 | 200, `data: UserSummary` | 본인만 조회 |
| `PATCH /api/users/me` | nickname | 200, `data: UserSummary` | P0에서는 닉네임만 수정 |

`ProfileUpdateRequest.nickname`은 필수·`null` 불가이며, 앞뒤 공백을 제거한 뒤 1자 이상 50자 이하이고 제어문자를 포함할 수 없다. 빈 객체, 빈 문자열과 검증 실패는 `VALIDATION_ERROR`다.

### 4.2 게임 조회·검색

#### 게임 목록·검색

`GET /api/games?keyword=스플렌더&page=0&size=10`은 게임명 부분 일치와 페이지 조회만 지원한다. `data.content`의 항목은 `GameListItem`이다.

- 게임 데이터는 운영자가 준비한다. 사용자용 게임 생성·수정·삭제 API는 제공하지 않는다.

#### 게임 상세 조회

`GET /api/games/{gameId}`는 `GameDetail`을 반환한다.

### 4.3 방 탐색

#### 방 목록 조회

~~~text
GET /api/rooms?type=GAME_FOCUSED&gameId={gameId}&page=0&size=10
GET /api/rooms?type=PERSON_FOCUSED&keyword=퇴근&page=0&size=10
~~~

- `type`은 필수다.
- `GAME_FOCUSED` 목록은 `gameId`가 필수이며 선택 게임의 방만 반환한다. `keyword`는 받을 수 없고 포함하면 `VALIDATION_ERROR`다.
- `PERSON_FOCUSED` 목록은 `gameId`를 받을 수 없고 포함하면 `VALIDATION_ERROR`다. `keyword`는 선택값이며 방 제목을 부분 일치로 검색한다.
- `keyword`는 사람 중심 방의 기본 제목 검색이며, P0에서 제외한 조건 필터가 아니다.
- 공개 목록은 `RECRUITING`, `CLOSED`만 반환한다.
- 방 목록의 `data.content` 각 항목은 `PublicRoomResponse` 형태를 사용한다. `joinable`은 요청자에 따라 계산한다.
- `playerCount`, `playTime`, `region`, `experienceLevel`, `tag`, `categoryIds`, `bggWeightMin`, `bggWeightMax`는 P0 쿼리 파라미터가 아니다.

#### 방 상세 조회

`GET /api/rooms/{roomId}`는 `RECRUITING`, `CLOSED` 방에 대해 비로그인·관계 없는 요청자에게 `PublicRoomResponse`를, 주최자·현재 `ACTIVE` 참가자에게 `ParticipantRoomResponse`를 반환한다. `CANCELED`, `FINISHED` 방은 주최자·현재 `ACTIVE` 참가자만 `ParticipantRoomResponse`를 조회할 수 있고 그 외 요청에는 404를 반환한다.

- 같은 URL의 응답이 요청자의 세션과 방 관계에 따라 달라지므로 `Cache-Control: private, no-store`와 `Vary: Cookie`를 반환한다.
- 참가 취소·로그아웃 뒤의 요청은 관계를 다시 판정하며 이전 `ParticipantRoomResponse`를 재사용하지 않는다. Spring Security의 기본 캐시 방지 헤더를 비활성화하거나 더 약한 캐시 정책으로 덮어쓰지 않는다.

### 4.4 방 관리

이 절의 방 쓰기 API `data`는 [공통 응답](#12-공통-응답)의 `data` 값이다.

#### 방 생성

게임 중심 생성 요청:

~~~json
{
  "roomType": "GAME_FOCUSED",
  "title": "토요일 저녁 스플렌더",
  "description": "처음 오신 분도 환영합니다.",
  "gameId": 1,
  "experienceLevel": "ALL_LEVELS",
  "isRulemasterLed": true,
  "startsAt": "2026-07-25T19:00:00+09:00",
  "place": "홍대입구역 인근 보드게임 카페",
  "recruitmentCapacity": 3
}
~~~

사람 중심 생성 요청:

~~~json
{
  "roomType": "PERSON_FOCUSED",
  "title": "퇴근 후 가볍게 보드게임 할 분",
  "description": "초보자도 환영합니다.",
  "gameId": null,
  "experienceLevel": "BEGINNER_WELCOME",
  "isRulemasterLed": false,
  "startsAt": "2026-07-25T19:00:00+09:00",
  "place": "홍대입구역 인근",
  "recruitmentCapacity": 3
}
~~~

- `roomType`은 필수·`null` 불가이며 `GAME_FOCUSED`, `PERSON_FOCUSED` 중 하나다.
- `title`과 `place`는 필수·`null` 불가다. 앞뒤 공백을 제거한 뒤 각각 1자 이상 255자 이하이며 제어문자를 포함할 수 없다.
- `experienceLevel`은 필수·`null` 불가이며 `ALL_LEVELS`, `BEGINNER_WELCOME`, `EXPERIENCED_PREFERRED` 중 하나다. 검색 필터나 참가 제한으로 사용하지 않는다.
- `isRulemasterLed`는 필수·`null` 불가인 boolean이다.
- `GAME_FOCUSED`는 존재하는 양의 정수 `gameId`가 필수다.
- `PERSON_FOCUSED`의 `gameId`는 생략, `null`, 존재하는 양의 정수를 모두 허용한다. 존재하는 ID가 오면 선택 게임으로 저장하며, 요청으로 받지 않는 항목은 태그·카테고리·BGG Weight다.
- `description`은 선택 값으로 생략하거나 `null`을 보낼 수 있다. 문자열이면 최대 50자이며 제어문자를 포함할 수 없다.
- `RoomCreateRequest.startsAt`은 필수이며 `null`을 허용하지 않는다. 오프셋을 포함한 형식이어야 하고 `Instant`로 정규화한 값이 요청 처리 시점보다 미래여야 한다. 누락, `null`, 오프셋 없음, 형식 오류와 현재·과거 시각은 `VALIDATION_ERROR`다.
- 서버는 `region`을 `홍대`로 저장한다. 생성·수정 요청에서 지역을 받지 않는다.
- `recruitmentCapacity`는 1 이상 10 이하의 정수다.
- 모든 로그인 사용자가 `isRulemasterLed`를 설정할 수 있다.

성공 응답:

| API | HTTP / wrapper `status` | `data` |
|---|---|---|
| `POST /api/rooms` | `201 Created` / `201` | 생성된 `ParticipantRoomResponse` |

#### 방 수정

`PATCH /api/rooms/{roomId}`는 부분 수정이다. 생략한 필드는 기존 값을 유지한다.

##### RoomUpdateRequest

| 필드 | 수정 규칙 |
|---|---|
| `title`, `place` | 생략하면 유지한다. 요청에 있으면 `null`을 허용하지 않고 앞뒤 공백을 제거한 1~255자 문자열이어야 하며 제어문자를 포함할 수 없다. |
| `experienceLevel` | 생략하면 유지한다. 요청에 있으면 `null`을 허용하지 않고 생성과 같은 enum 검증을 거친다. |
| `isRulemasterLed` | 생략하면 유지한다. 요청에 있으면 `null`을 허용하지 않는 boolean이어야 한다. |
| `recruitmentCapacity` | 생략하면 유지한다. 요청에 있으면 `null`을 허용하지 않는 1~10 정수여야 한다. |
| `startsAt` | 생략하면 기존 값을 유지한다. 요청에 있으면 `null`을 허용하지 않고 생성과 같은 오프셋 형식으로 검증하며, `Instant`로 정규화한 값이 요청 처리 시점보다 미래여야 한다. 위반하면 `VALIDATION_ERROR`다. |
| `description` | 생략하면 유지하고, `null`이면 값을 지우며, 문자열이면 최대 50자이고 제어문자를 포함할 수 없다. |
| `gameId` | 생략하면 유지한다. `GAME_FOCUSED`는 수정 후에도 존재하는 양의 정수 ID가 필수다. `PERSON_FOCUSED`는 `null`로 게임 선택을 지우거나 존재하는 양의 정수 ID로 선택 게임을 바꿀 수 있다. |
| `roomType`, `region`, `status` | 요청으로 받을 수 없으며 포함하면 `VALIDATION_ERROR`다. |

- 수정은 주최자만 할 수 있고, 저장된 `startsAt`보다 현재 시각이 이르며 `status = RECRUITING`이고 주최자 외 `ACTIVE` 참가자가 없을 때만 허용한다. 외부 `ACTIVE` 참가자가 있으면 `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS`, 나머지 조건을 만족하지 않으면 `INVALID_ROOM_STATUS_TRANSITION`을 반환한다.

성공 응답:

| API | HTTP / wrapper `status` | `data` |
|---|---|---|
| `PATCH /api/rooms/{roomId}` | `200 OK` / `200` | 수정된 `ParticipantRoomResponse` |

#### 방 취소

`DELETE /api/rooms/{roomId}`는 삭제가 아니라 `CANCELED` 상태 변경이다.

성공 응답:

| API | HTTP / wrapper `status` | `data` |
|---|---|---|
| `DELETE /api/rooms/{roomId}` | `200 OK` / `200` | `{ "roomId": 1, "roomStatus": "CANCELED" }` |

#### 방 종료

`PATCH /api/rooms/{roomId}/status`는 `{"status":"FINISHED"}`만 받고, 주최자가 `status = CLOSED && now >= startsAt`인 방에만 허용한다.

성공 응답:

| API | HTTP / wrapper `status` | `data` |
|---|---|---|
| `PATCH /api/rooms/{roomId}/status` | `200 OK` / `200` | `{ "roomId": 1, "roomStatus": "FINISHED" }` |

### 4.5 참가·내 모임

#### 방 참가·재참가

성공 응답:

| API | HTTP / wrapper `status` | `data` |
|---|---|---|
| `POST /api/rooms/{roomId}/participants` | `201 Created` / `201` | `participationStatus = ACTIVE`인 `RoomParticipationResponse` |

- `POST /api/rooms/{roomId}/participants`는 `RECRUITING` 상태·시작 전·중복 참가·남은 모집 자리를 확인하고 참가 관계를 `ACTIVE`로 만든다. 이 검증, 참가 관계 생성·재활성화, 모집 상태 변경은 하나의 트랜잭션으로 수행해 모집 정원을 초과하지 않는다. 신규 참가와 재활성화 모두 `201 Created`와 `RoomParticipationResponse`를 반환한다.
- 인증과 방 존재 확인 뒤 참가 요청 실패는 아래 우선순위로 반환한다.
  1. 방이 `CANCELED`, `FINISHED`인 경우: `ROOM_NOT_RECRUITING`
  2. 요청자가 주최자이거나 현재 `ACTIVE` 참가 관계가 있는 경우: `ALREADY_PARTICIPATING`
  3. `remainingRecruitmentSeats = 0`인 경우: `CAPACITY_EXCEEDED`
  4. `now >= startsAt`이거나 `status != RECRUITING`인 경우: `ROOM_NOT_RECRUITING`
- 따라서 마지막 좌석 참가로 `CLOSED`가 된 방의 다음 신규 참가 요청은 `CAPACITY_EXCEEDED`다.
- 참가 후 주최자 외 `ACTIVE` 참가자 수가 `capacity`에 도달하면 `RECRUITING → CLOSED`로 자동 전환한다.
- 시간대가 겹치는 다른 방 참가를 차단하지 않는다.

#### 참가 취소

성공 응답:

| API | HTTP / wrapper `status` | `data` |
|---|---|---|
| `DELETE /api/rooms/{roomId}/participants/me` | `200 OK` / `200` | `participationStatus = CANCELED`인 `RoomParticipationResponse` |

- `DELETE /api/rooms/{roomId}/participants/me`는 본인만 `now < startsAt`일 때 수행한다. 시작 시각 전 빈자리가 생기면 `CLOSED → RECRUITING`으로 자동 복귀하고, 시작 시각 이후 취소는 `INVALID_ROOM_STATUS_TRANSITION`으로 거절한다. 성공 시 `200 OK`와 `participationStatus = CANCELED`인 `RoomParticipationResponse`를 반환한다.
- 인증과 방 존재 확인 뒤 참가 취소 실패는 아래 우선순위로 반환한다.
  1. 요청자가 주최자인 경우: `FORBIDDEN`
  2. 현재 `ACTIVE` 참가 관계가 없는 경우: `PARTICIPATION_NOT_FOUND`
  3. `now >= startsAt`인 경우: `INVALID_ROOM_STATUS_TRANSITION`
- 주최자는 자신의 참가만 따로 취소할 수 없다.

#### 내 모임 조회

`GET /api/users/me/rooms?role=all|joined|hosted&page=0&size=10`은 `data.content`에 `MyRoomListItem` 목록을 반환한다. `joined`는 `Participation.status = ACTIVE`이고 `Room.status != CANCELED`인 본인 참가 방만, `hosted`는 본인이 개설한 방을 반환하며, `all`은 둘의 중복 없는 합집합이다. 참가 취소한 `CANCELED` 관계와 방이 취소된 `CANCELED` 방은 `joined`에서 제외하고, `FINISHED` 방은 실제 참여 이력으로 포함한다.

### 4.6 방 상태

#### 방 상태 계약

~~~text
방 생성 ───────────────→ RECRUITING
RECRUITING --모집 인원 충족 또는 시작 시각 도달--> CLOSED
CLOSED --시작 전 참가 취소로 빈자리--> RECRUITING
RECRUITING 또는 CLOSED --주최자 취소--> CANCELED
CLOSED --주최자 종료(now >= startsAt) 또는 시작 시각+24시간--> FINISHED
~~~

`CANCELED`, `FINISHED`는 최종 상태다. 수동 모집 마감·재오픈은 허용하지 않는다.

## 5. 오류 코드와 검증

오류 코드는 클라이언트가 실패 원인을 식별하는 안정적인 외부 계약이다.

- 공통 ErrorCode는 입력, 인증, 인가와 요청 보호처럼 여러 도메인의 HTTP 경계에서 같은 의미로 사용하는 실패다.
- 도메인 ErrorCode는 업무 규칙이나 도메인 리소스에서 발생한 실패다. 코드의 소유 도메인은 호출한 엔드포인트가 아니라 실패 규칙의 소유자를 기준으로 정한다.
- `code`는 `UPPER_SNAKE_CASE`를 사용하고 전체 API에서 유일해야 한다. 같은 코드를 다른 의미나 HTTP 상태로 재사용하지 않는다.
- `status`는 실제 HTTP 상태 및 실패 응답 본문의 `status`와 일치해야 한다.
- `message`는 아래 카탈로그의 한국어 기본 메시지를 사용한다. 클라이언트는 메시지가 아니라 `code`를 기준으로 분기한다.
- 오류 코드를 추가하거나 의미, HTTP 상태 또는 기본 메시지를 변경하면 이 카탈로그와 [엔드포인트별 오류 매트릭스](#53-엔드포인트별-오류-매트릭스)를 함께 갱신한다.

### 5.1 공통 ErrorCode 카탈로그

| 코드 | HTTP | 기본 메시지 | 발생 조건 |
|---|---:|---|---|
| `VALIDATION_ERROR` | 400 | 요청값 검증에 실패했습니다. | 입력값의 필수 여부, 형식, 길이 또는 범위 검증 실패 |
| `UNAUTHENTICATED` | 401 | 인증이 필요합니다. | 세션 쿠키가 없거나 세션이 만료·무효화됨 |
| `FORBIDDEN` | 403 | 요청을 수행할 권한이 없습니다. | 인증은 됐지만 요청한 작업을 수행할 권한이 없음 |
| `CSRF_TOKEN_INVALID` | 403 | CSRF 토큰이 없거나 유효하지 않습니다. | 상태 변경 요청의 CSRF 토큰이 없거나 유효하지 않음 |

### 5.2 도메인 ErrorCode 카탈로그

도메인 카탈로그의 분류는 오류 코드의 소유 경계를 나타낸다. 다른 도메인의 엔드포인트에서 발생하더라도 같은 실패 규칙에는 소유 도메인의 코드를 사용한다. 예를 들어 방 생성 중 선택한 게임이 없으면 게임 도메인의 `GAME_NOT_FOUND`를 반환한다.

#### 인증·회원

| 코드 | HTTP | 기본 메시지 | 발생 조건 |
|---|---:|---|---|
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호가 일치하지 않습니다. | 로그인 이메일 또는 비밀번호가 일치하지 않음 |
| `EMAIL_ALREADY_EXISTS` | 409 | 이미 사용 중인 이메일입니다. | 회원가입 이메일이 이미 사용 중임 |
| `RATE_LIMIT_EXCEEDED` | 429 | 인증 요청 처리 한도를 초과했습니다. 잠시 후 다시 시도해 주세요. | 인증 요청 횟수 또는 비밀번호 해시 동시 작업 한도 초과 |

`RATE_LIMIT_EXCEEDED` 응답에는 다시 요청할 수 있을 때까지의 초를 `Retry-After` 헤더로 포함한다.

#### 게임

| 코드 | HTTP | 기본 메시지 | 발생 조건 |
|---|---:|---|---|
| `GAME_NOT_FOUND` | 404 | 게임을 찾을 수 없습니다. | 요청한 게임이 없음 |

#### 방

| 코드 | HTTP | 기본 메시지 | 발생 조건 |
|---|---:|---|---|
| `ROOM_NOT_FOUND` | 404 | 방을 찾을 수 없습니다. | 방이 없거나, 권한 없는 사용자가 취소·종료 방 상세를 조회함 |
| `INVALID_ROOM_STATUS_TRANSITION` | 409 | 허용되지 않은 방 상태 변경입니다. | 현재 상태 또는 시간 조건에서 허용되지 않은 상태 전이 시도 |
| `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS` | 409 | 주최자 외 활성 참가자가 있는 방은 수정할 수 없습니다. | 주최자 외 `ACTIVE` 참가자가 있는 방 수정 시도 |
| `ROOM_CONCURRENT_MODIFICATION` | 409 | 방 정보가 동시에 변경되었습니다. 다시 시도해 주세요. | 같은 방의 동시 변경이 반복되어 요청을 완료하지 못함 |

방 상태를 보정하거나 변경하는 요청에서 낙관 락 충돌이 발생하면 최신 상태를 다시 읽는 독립 트랜잭션으로 재시도하며, 최초 시도 1회와 재시도 2회를 합쳐 최대 3회로 제한한다. 새 시도에서 업무 규칙 위반을 확인하면 해당 업무 오류를 우선 반환하고, 세 시도가 모두 방 버전 충돌로 실패한 경우에만 `ROOM_CONCURRENT_MODIFICATION`을 반환한다. 조회 요청에서 이 오류를 받은 클라이언트는 조회 요청 전체를 다시 시도한다.

#### 참가

참가 오류의 정확한 판정 순서는 [방 참가·재참가](#방-참가재참가)와 [참가 취소](#참가-취소)를 따른다. 아래 `대표 발생 조건`은 각 오류 코드의 의미를 요약한 것이며 독립적인 충분조건이 아니다. 여러 조건이 동시에 성립하면 해당 엔드포인트에서 먼저 정의된 오류를 반환한다.

| 코드 | HTTP | 기본 메시지 | 대표 발생 조건 |
|---|---:|---|---|
| `PARTICIPATION_NOT_FOUND` | 404 | 현재 참가 정보를 찾을 수 없습니다. | 현재 `ACTIVE`인 본인 참가 관계가 없음 |
| `CAPACITY_EXCEEDED` | 409 | 모집 가능한 인원을 초과했습니다. | 방의 모집 인원을 초과하는 참가 시도 |
| `ROOM_NOT_RECRUITING` | 409 | 현재 모집 중인 방이 아닙니다. | 모집 중이 아닌 방 참가 시도 |
| `ALREADY_PARTICIPATING` | 409 | 이미 참가 중인 방입니다. | 요청자가 해당 방의 주최자이거나, 같은 방에 `ACTIVE` 참가 관계가 있는데 다시 참가 시도 |

### 5.3 엔드포인트별 오류 매트릭스

| API | 오류 코드 |
|---|---|
| `GET /api/auth/csrf` | 없음 |
| `POST /api/auth/signup` | `VALIDATION_ERROR`, `EMAIL_ALREADY_EXISTS`, `RATE_LIMIT_EXCEEDED`, `CSRF_TOKEN_INVALID` |
| `POST /api/auth/login` | `VALIDATION_ERROR`, `INVALID_CREDENTIALS`, `RATE_LIMIT_EXCEEDED`, `CSRF_TOKEN_INVALID` |
| `POST /api/auth/logout` | `UNAUTHENTICATED`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me` | `UNAUTHENTICATED` |
| `PATCH /api/users/me` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `CSRF_TOKEN_INVALID` |
| `GET /api/games` | `VALIDATION_ERROR` |
| `GET /api/games/{gameId}` | `GAME_NOT_FOUND` |
| `POST /api/rooms` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `GAME_NOT_FOUND`, `CSRF_TOKEN_INVALID` |
| `GET /api/rooms` | `VALIDATION_ERROR`, `ROOM_CONCURRENT_MODIFICATION` |
| `GET /api/rooms/{roomId}` | `ROOM_NOT_FOUND`, `ROOM_CONCURRENT_MODIFICATION` |
| `PATCH /api/rooms/{roomId}` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `GAME_NOT_FOUND`, `VALIDATION_ERROR`, `ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/rooms/{roomId}` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `PATCH /api/rooms/{roomId}/status` | `UNAUTHENTICATED`, `FORBIDDEN`, `ROOM_NOT_FOUND`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `POST /api/rooms/{roomId}/participants` | `UNAUTHENTICATED`, `ROOM_NOT_FOUND`, `ALREADY_PARTICIPATING`, `ROOM_NOT_RECRUITING`, `CAPACITY_EXCEEDED`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `DELETE /api/rooms/{roomId}/participants/me` | `UNAUTHENTICATED`, `ROOM_NOT_FOUND`, `PARTICIPATION_NOT_FOUND`, `FORBIDDEN`, `INVALID_ROOM_STATUS_TRANSITION`, `ROOM_CONCURRENT_MODIFICATION`, `CSRF_TOKEN_INVALID` |
| `GET /api/users/me/rooms` | `UNAUTHENTICATED`, `VALIDATION_ERROR`, `ROOM_CONCURRENT_MODIFICATION` |

- `GET /api/rooms/{roomId}`에서만 취소·종료 방을 권한 없는 사용자가 조회할 때 존재 여부를 숨기기 위해 `ROOM_NOT_FOUND`를 반환한다. 그 외 주최자 전용 쓰기 API의 비주최자 요청은 `FORBIDDEN`을 반환한다.
- `PATCH /api/rooms/{roomId}`의 `GAME_NOT_FOUND`는 요청에 `gameId`를 포함했을 때만 적용한다.

P0 구현 완료 검증은 [P0 구현 완료 기준](P0-spec.md)을 따른다.
