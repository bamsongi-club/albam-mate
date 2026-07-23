# 알밤메이트 P0 API 명세서

- 최종 수정일: 2026-07-23
- 문서 상태: **P0 1차 MVP API 계약**
- 기준 문서: [PRD](PRD.md), [P0-spec](P0-spec.md), [ERD](ERD.md)

> 이 문서는 P0 1차 MVP의 15개 API만 정의한다.

## 1. 공통 규칙

- 기본 경로는 `/api`다.
- 요청·응답은 `application/json`이다.
- 인증 API는 `Authorization: Bearer {accessToken}` 헤더를 사용한다.
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

- 시각은 ISO 8601 오프셋 형식으로 반환한다.
- 페이지 응답은 `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`를 가진다.
- 사용자·게임·세션·참가 관계의 ID는 1부터 증가하는 양의 정수다. JSON에서는 숫자로, 경로에서는 10진 정수로 전달한다. UUID는 사용하지 않는다.
- 검증 오류는 `400`, 미인증은 `401`, 권한 없음은 `403`, 대상 없음은 `404`, 상태·정합성 충돌은 `409`를 사용한다.

## 2. P0 API 목록 (15개)

| # | 도메인 | 메서드 | 경로 | 인증 | 기능 |
|---:|---|---|---|:---:|---|
| 1 | 인증 | POST | `/api/auth/signup` | N | 회원가입 |
| 2 | 인증 | POST | `/api/auth/login` | N | 로그인 |
| 3 | 프로필 | GET | `/api/users/me` | Y | 본인 프로필 조회 |
| 4 | 프로필 | PATCH | `/api/users/me` | Y | 본인 프로필 수정 |
| 5 | 게임 | GET | `/api/games` | N | 게임 목록·게임명 검색 |
| 6 | 게임 | GET | `/api/games/{gameId}` | N | 게임 상세 |
| 7 | 세션 | POST | `/api/sessions` | Y | 게임·사람 중심 세션 생성 |
| 8 | 세션 | GET | `/api/sessions` | N | 유형별 세션 목록 |
| 9 | 세션 | GET | `/api/sessions/{sessionId}` | N | 세션 상세·참가자 목록 |
| 10 | 세션 | PATCH | `/api/sessions/{sessionId}` | Y | 주최자 세션 수정 |
| 11 | 세션 | DELETE | `/api/sessions/{sessionId}` | Y | 주최자 세션 취소 |
| 12 | 세션 | PATCH | `/api/sessions/{sessionId}/status` | Y | 주최자 세션 종료 |
| 13 | 참가 | POST | `/api/sessions/{sessionId}/participants` | Y | 선착순 참가 |
| 14 | 참가 | DELETE | `/api/sessions/{sessionId}/participants/me` | Y | 본인 참가 취소 |
| 15 | 내 모임 | GET | `/api/users/me/sessions?role=all\|joined\|hosted` | Y | 참여·개설 세션 이력 |

## 3. 응답 모델

### 3.1 UserSummary

~~~json
{
  "id": 1,
  "nickname": "알밤"
}
~~~

P0 프로필은 닉네임만 제공·수정한다. 이메일과 인증 정보는 응답에 포함하지 않는다.

### 3.2 GameListItem

~~~json
{
  "id": 1,
  "name": "스플렌더",
  "englishName": "Splendor",
  "imageUrl": "https://example.com/games/splendor.png",
  "recommendedPlayerCount": "2~4명",
  "tag": "전략",
  "estimatedPlayTime": "30분",
  "complexity": 1.78,
  "upcomingSessionCount": 3
}
~~~

`upcomingSessionCount`는 미래 시점의 `GAME_FOCUSED` 세션 중 `CANCELED`, `FINISHED`가 아닌 건수를 조회 시 계산한다. `games` 테이블에는 저장하지 않는다.

### 3.3 GameDetail

~~~json
{
  "id": 1,
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
  "upcomingSessionCount": 3
}
~~~

`tag`는 표시값이다. P0 목록 API는 태그·인원·시간·복잡도 조건 필터를 받지 않는다.

### 3.4 세션 응답

#### PublicSessionResponse

~~~json
{
  "id": 1,
  "sessionType": "GAME_FOCUSED",
  "title": "토요일 저녁 스플렌더",
  "description": "처음 오신 분도 환영합니다.",
  "game": {"id": 1, "name": "스플렌더"},
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

공개 응답은 세션을 탐색·참가 판단하는 데 필요한 비식별 정보만 반환한다. `place`, 주최자, 참가자 목록과 사용자 ID는 포함하지 않는다.

#### ParticipantSessionResponse

주최자 또는 현재 `ACTIVE` 참가자가 상세를 조회하면 `PublicSessionResponse`의 모든 필드에 아래 필드가 추가된다.

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
- `GET /api/sessions/{sessionId}`는 `RECRUITING`, `CLOSED` 세션에 대해 비로그인·관계 없는 요청자에게 `PublicSessionResponse`를, 주최자·현재 `ACTIVE` 참가자에게 `ParticipantSessionResponse`를 반환한다. `CANCELED`, `FINISHED` 세션은 주최자·현재 `ACTIVE` 참가자만 `ParticipantSessionResponse`를 조회할 수 있고 그 외 요청에는 404를 반환한다.
- `joinable`은 현재 요청자의 실제 참가 가능 여부다. 비로그인, 주최자, 이미 `ACTIVE`인 참가자, `RECRUITING`이 아닌 세션, `now >= startsAt`, 남은 모집 자리가 없는 경우에는 `false`다. 로그인한 비주최자이며 현재 `ACTIVE`가 아니고 `RECRUITING && now < startsAt && remainingRecruitmentSeats > 0`일 때만 `true`다. 기존 `CANCELED` 참가 관계를 가진 유저도 이 조건이면 재참가할 수 있다.

## 4. API 계약

### 4.1 인증과 프로필

| API | 요청 핵심값 | 성공 응답 | 규칙 |
|---|---|---|---|
| `POST /api/auth/signup` | email, password, nickname | 201, UserSummary | 이메일 중복은 409 |
| `POST /api/auth/login` | email, password | accessToken | 잘못된 자격증명은 401 |
| `GET /api/users/me` | 없음 | UserSummary | 본인만 조회 |
| `PATCH /api/users/me` | nickname | UserSummary | P0에서는 닉네임만 수정 |

### 4.2 게임 조회·검색

- `GET /api/games?keyword=스플렌더&page=0&size=20`은 게임명 부분 일치와 페이지 조회만 지원한다.
- `GET /api/games/{gameId}`는 `GameDetail`을 반환한다.
- 게임 데이터는 운영자가 준비한다. 사용자용 게임 생성·수정·삭제 API는 제공하지 않는다.

### 4.3 세션 목록

~~~text
GET /api/sessions?type=GAME_FOCUSED&gameId={gameId}&page=0&size=20
GET /api/sessions?type=PERSON_FOCUSED&keyword=퇴근&page=0&size=20
~~~

- `type`은 필수다.
- `GAME_FOCUSED` 목록은 `gameId`가 필수이며 선택 게임의 세션만 반환한다.
- `PERSON_FOCUSED` 목록은 `gameId`를 받지 않는다. `keyword`가 있으면 세션 제목을 부분 일치로 검색한다.
- 공개 목록은 `RECRUITING`, `CLOSED`만 반환한다.
- 세션 목록의 각 항목은 `PublicSessionResponse` 형태를 사용한다. `joinable`은 요청자에 따라 계산한다.
- `playerCount`, `playTime`, `region`, `experienceLevel`, `tag`, `categoryIds`, `bggWeightMin`, `bggWeightMax`는 P0 쿼리 파라미터가 아니다.

### 4.4 세션 생성·수정

게임 중심 생성 요청:

~~~json
{
  "sessionType": "GAME_FOCUSED",
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
  "sessionType": "PERSON_FOCUSED",
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

- `GAME_FOCUSED`는 존재하는 양의 정수 `gameId`가 필수다.
- `PERSON_FOCUSED`의 `gameId`는 생략, `null`, 존재하는 양의 정수를 모두 허용한다. 존재하는 ID가 오면 선택 게임으로 저장하며, 요청으로 받지 않는 항목은 태그·카테고리·BGG Weight다.
- `description`은 선택 값이고 최대 50자다.
- `experienceLevel`은 `ALL_LEVELS`, `BEGINNER_WELCOME`, `EXPERIENCED_PREFERRED` 중 하나다. 검색 필터나 참가 제한으로 사용하지 않는다.
- 서버는 `region`을 `홍대`로 저장한다. 생성·수정 요청에서 지역을 받지 않는다.
- `recruitmentCapacity`는 1 이상 10 이하의 정수다.
- 모든 로그인 사용자가 `isRulemasterLed`를 설정할 수 있다.
- `PATCH /api/sessions/{sessionId}`는 부분 수정이다. 생략한 필드는 기존 값을 유지한다.

#### SessionUpdateRequest

| 필드 | 수정 규칙 |
|---|---|
| `title`, `experienceLevel`, `isRulemasterLed`, `startsAt`, `place`, `recruitmentCapacity` | 요청에 있으면 생성과 같은 검증을 거쳐 해당 값으로 바꾼다. `startsAt`은 수정 시점보다 미래여야 하고 `recruitmentCapacity`는 1~10이어야 한다. |
| `description` | 생략하면 유지하고, `null`이면 값을 지우며, 문자열이면 최대 50자로 바꾼다. |
| `gameId` | 생략하면 유지한다. `GAME_FOCUSED`는 수정 후에도 존재하는 양의 정수 ID가 필수다. `PERSON_FOCUSED`는 `null`로 게임 선택을 지우거나 존재하는 양의 정수 ID로 선택 게임을 바꿀 수 있다. |
| `sessionType`, `region`, `status` | 요청으로 받을 수 없으며 포함하면 `VALIDATION_ERROR`다. |

- 수정은 주최자만 할 수 있고, 저장된 `startsAt`보다 현재 시각이 이르며 `status = RECRUITING`이고 주최자 외 `ACTIVE` 참가자가 없을 때만 허용한다. 외부 `ACTIVE` 참가자가 있으면 `SESSION_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS`, 나머지 조건을 만족하지 않으면 `INVALID_SESSION_STATUS_TRANSITION`을 반환한다.
- `DELETE /api/sessions/{sessionId}`는 삭제가 아니라 `CANCELED` 상태 변경이다.
- `PATCH /api/sessions/{sessionId}/status`는 `{"status":"FINISHED"}`만 받고, 주최자가 `status = CLOSED && now >= startsAt`인 세션에만 허용한다.

### 4.5 참가·내 모임

- `POST /api/sessions/{sessionId}/participants`는 `RECRUITING` 상태·시작 전·중복 참가·남은 모집 자리를 확인하고 참가 관계를 `ACTIVE`로 만든다. 이 검증, 참가 관계 생성·재활성화, 모집 상태 변경은 하나의 트랜잭션으로 수행해 모집 정원을 초과하지 않는다.
- 참가 후 주최자 외 `ACTIVE` 참가자 수가 `capacity`에 도달하면 `RECRUITING → CLOSED`로 자동 전환한다.
- `DELETE /api/sessions/{sessionId}/participants/me`는 본인만 `now < startsAt`일 때 수행한다. 시작 시각 전 빈자리가 생기면 `CLOSED → RECRUITING`으로 자동 복귀하고, 시작 시각 이후 취소는 `INVALID_SESSION_STATUS_TRANSITION`으로 거절한다.
- 주최자는 자신의 참가만 따로 취소할 수 없다.
- 시간대가 겹치는 다른 세션 참가를 차단하지 않는다.
- `GET /api/users/me/sessions?role=all|joined|hosted`는 참여·개설 이력을 반환하며 `CANCELED`, `FINISHED`도 포함한다.

### 4.6 상태 전이

~~~text
세션 생성 ───────────────→ RECRUITING
RECRUITING --모집 인원 충족 또는 시작 시각 도달--> CLOSED
CLOSED --시작 전 참가 취소로 빈자리--> RECRUITING
RECRUITING 또는 CLOSED --주최자 취소--> CANCELED
CLOSED --주최자 종료(now >= startsAt) 또는 시작 시각+24시간--> FINISHED
~~~

`CANCELED`, `FINISHED`는 최종 상태다. 수동 모집 마감·재오픈은 허용하지 않는다.

## 5. 오류 코드와 검증

| 코드 | HTTP | 의미 |
|---|---:|---|
| `VALIDATION_ERROR` | 400 | 입력값·형식·길이 검증 실패 |
| `SESSION_NOT_FOUND` | 404 | 세션이 없거나 취소·종료 세션 상세를 조회할 권한이 없음 |
| `CAPACITY_EXCEEDED` | 409 | 모집 인원 초과 |
| `SESSION_NOT_RECRUITING` | 409 | 모집 중이 아닌 세션 참가 시도 |
| `ALREADY_PARTICIPATING` | 409 | 같은 세션 중복 참가 |
| `INVALID_SESSION_STATUS_TRANSITION` | 409 | 허용되지 않은 상태 전이 |
| `SESSION_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS` | 409 | 주최자 외 활성 참가자가 있는 세션 수정 시도 |

P0 구현 완료 검증은 [P0-spec](P0-spec.md)의 8절을 따른다.
