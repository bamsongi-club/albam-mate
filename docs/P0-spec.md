# 알밤메이트 P0-spec

- 최종 수정일: 2026-07-23
- 문서 상태: **P0 1차 MVP 구현 계약**
- 연계 문서: [PRD](PRD.md), [ERD](ERD.md), [API](API.md)

> 이 문서는 P0 1차 MVP에 실제로 구현·검증할 범위만 정의한다. 전체 제품 목표와 P0 밖 기능은 PRD에서 관리한다.

## 1. 목표와 범위

P0의 목표는 사용자가 홍대 오프라인 보드게임 세션을 만들고, 다른 사용자가 남은 자리에 선착순으로 참가해 모임을 성립시키는지 검증하는 것이다.

- 세션 유형: `GAME_FOCUSED`, `PERSON_FOCUSED`
- 장소: 홍대 지역의 오프라인 장소만 지원한다.
- API: 아래 15개 API만 구현·검증한다.
- 참가 정합성: 일반적인 **순차 요청**에서 중복 참가와 모집 인원 초과를 막는다.

## 2. 사용자 흐름

### 게임부터 찾기

~~~text
게임명 검색
→ 게임 목록·상세 조회
→ 선택한 gameId의 게임 중심 세션 목록
→ 세션 상세
→ 로그인
→ 참가 신청
→ 내 모임 확인
~~~

### 사람부터 만나기

~~~text
사람 중심 세션 목록·제목 keyword 검색
→ 세션 상세
→ 로그인
→ 참가 신청
→ 내 모임 확인
~~~

### 세션 만들기

~~~text
로그인
→ 게임 중심 또는 사람 중심 선택
→ 제목·한 줄 소개·경험 수준·모집 인원·시작 시각·장소·진행 여부 입력
→ 세션 생성
→ 세션 상세에서 참가자 확인
~~~

## 3. 포함 기능

| 구분 | P0 범위 |
|---|---|
| 계정 | 이메일·비밀번호 회원가입, 로그인, 닉네임 기준 본인 프로필 조회·수정 |
| 게임 | 읽기 전용 게임 목록·게임명 검색·상세 조회 |
| 게임 정보 | 영문명, 별칭, 이미지, 권장 인원, 게임 태그, 예상 시간, 복잡도, 간단 설명, 상세 설명, 계산된 예정 모임 수 |
| 세션 | 게임 중심·사람 중심 생성, 목록·상세·수정·취소·종료 |
| 검색 | 게임명 검색, 선택 게임의 게임 중심 세션 목록, 사람 중심 세션 제목 `keyword` 검색 |
| 참가 | 순차 요청 기준 선착순 참가, 본인 참가 취소, 중복·모집 인원 초과 차단 |
| 내 활동 | `role` 기준 내 참여·개설 세션 이력, 세션 상세의 참가자 목록 |
| 룰마스터 진행 | 모든 로그인 사용자의 세션 단위 `isRulemasterLed` 자기신고 |

## 4. P0 API 목록 (15개)

| # | 기능 | 메서드/경로 |
|---:|---|---|
| 1 | 회원가입 | `POST /api/auth/signup` |
| 2 | 로그인 | `POST /api/auth/login` |
| 3 | 내 프로필 조회 | `GET /api/users/me` |
| 4 | 내 프로필 수정 | `PATCH /api/users/me` |
| 5 | 게임 목록·검색 | `GET /api/games?keyword=...&page=...&size=...` |
| 6 | 게임 상세 | `GET /api/games/{gameId}` |
| 7 | 세션 생성 | `POST /api/sessions` |
| 8 | 세션 목록 | `GET /api/sessions?type=GAME_FOCUSED&gameId=...` 또는 `type=PERSON_FOCUSED&keyword=...` |
| 9 | 세션 상세·참가자 목록 | `GET /api/sessions/{sessionId}` |
| 10 | 세션 수정 | `PATCH /api/sessions/{sessionId}` |
| 11 | 세션 취소 | `DELETE /api/sessions/{sessionId}` |
| 12 | 세션 종료 | `PATCH /api/sessions/{sessionId}/status` |
| 13 | 세션 참가 | `POST /api/sessions/{sessionId}/participants` |
| 14 | 참가 취소 | `DELETE /api/sessions/{sessionId}/participants/me` |
| 15 | 내 모임 | `GET /api/users/me/sessions?role=all\|joined\|hosted` |

## 5. 데이터와 검색 규칙

### 게임

- 게임은 운영자가 미리 입력하는 읽기 전용 데이터다.
- `tag`는 게임 카드·상세에 표시하는 단일 게임 스타일 값이다. P0에서는 태그 필터·태그 검색·사람 중심 세션의 선택 조건으로 쓰지 않는다.
- 게임 목록은 `name`의 부분 일치 `keyword` 검색만 지원한다.
- `upcomingSessionCount`는 저장 컬럼이 아니다. 미래 시점의 `GAME_FOCUSED` 세션 중 `CANCELED`, `FINISHED`가 아닌 수를 조회 시 계산한다.

### 세션

- `GAME_FOCUSED`는 유효한 게임 1개를 반드시 선택한다.
- `PERSON_FOCUSED`는 게임 선택이 선택 사항이며, 게임·태그·카테고리·BGG Weight 없이 생성할 수 있다.
- 세션 소개 `description`은 선택 입력이며 최대 50자다.
- `region`은 서버에서 `홍대`로 저장한다. 생성 요청에서 지역을 받거나 지역 필터를 제공하지 않는다.
- `place`는 실제 오프라인 모임 장소다.
- 게임 중심 목록은 선택한 `gameId`로 조회하고, 사람 중심 목록의 `keyword`는 세션 제목을 부분 일치로 검색한다.
- 공개 세션 목록에는 `RECRUITING`, `CLOSED`만 포함한다. `CANCELED`, `FINISHED`는 본인 내 모임 이력과 직접 상세 조회에서 확인한다.

### 참가자와 모집 인원

- 주최자는 `sessions.host_user_id`에만 저장하며 `participations` 행을 만들지 않는다.
- 세션 상세 참가자 목록은 주최자 1명과 `ACTIVE` 참가 관계 사용자로 구성한다.
- API의 `recruitmentCapacity`는 DB의 `capacity`에 저장하는 **주최자 제외 모집 인원**이다.
- 모집 인원 3명은 주최자 1명과 추가 참가자 3명, 총 4인 모임이다.
- `participantCount = 1 + activeParticipationCount`
- `remainingRecruitmentSeats = capacity - activeParticipationCount`
- 같은 세션·사용자 조합은 하나의 참가 관계만 가지며, 재신청은 기존 `CANCELED` 행을 `ACTIVE`로 되돌린다.

## 6. 권한과 상태 전이

- 세션 생성·수정·취소·종료는 주최자만 할 수 있다.
- 주최자 외 `ACTIVE` 참가자가 한 명 이상이면 세션을 수정할 수 없다.
- 모든 로그인 사용자는 `isRulemasterLed`를 설정할 수 있다. 사용자 프로필의 룰마스터 상태·등록·인증은 확인하지 않는다.
- 참가자는 시작 시각 전 `RECRUITING` 상태에서 남은 자리가 있을 때 즉시 `ACTIVE`가 된다.
- 시간대가 겹치는 다른 세션 참가를 차단하지 않는다.

~~~text
세션 생성 ───────────────→ RECRUITING
RECRUITING --모집 인원 충족 또는 시작 시각 도달--> CLOSED
CLOSED --시작 전 참가 취소로 빈자리 발생--> RECRUITING
RECRUITING 또는 CLOSED --주최자 취소--> CANCELED
CLOSED --주최자 종료 또는 시작 시각+24시간--> FINISHED
~~~

- `CANCELED`, `FINISHED`는 최종 상태다.
- `CLOSED`는 수동 마감 상태가 아니다. 수동 모집 마감·재오픈은 제공하지 않는다.
- P0는 순차 요청만 정합성 검증 대상이다. 다수 동시 참가의 잠금·트랜잭션 방식은 P0 범위에 넣지 않는다.

## 7. 완료 기준

- 15개 API를 Swagger 또는 Postman으로 모두 재현한다.
- 게임 중심과 사람 중심 세션이 모두 생성된다.
- 게임명 검색과 사람 중심 세션 제목 검색이 각각 동작한다.
- 사람 중심 세션은 게임·태그·카테고리·BGG Weight 없이 생성된다.
- 남은 모집 자리가 1개일 때 순차 참가 요청 중 첫 요청만 성공하고, 다음 요청은 모집 인원 초과로 실패한다.
- 참가·취소에 따라 참가자 수·남은 자리·`RECRUITING`/`CLOSED` 상태가 일관되게 계산된다.
- `CLOSED` 세션은 주최자가 종료할 수 있고, 시작 시각 24시간 후에는 자동으로 `FINISHED`가 된다.

## 8. P0 제외 범위

- 인원·시간·난이도·지역·경험 수준·태그·카테고리·BGG Weight 조건 필터
- 온라인 세션, 플레이룸, 채팅, 외부 플랫폼 접속
- 후기·참석·노쇼·인기 항목
- 사용자 룰마스터 프로필·가능 게임 등록
- 대기열·주최자 승인·자동 승격·다수 동시 참가 잠금
- 포인트·결제·알림·공개 프로필 이미지
