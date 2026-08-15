# 알밤메이트 ERD

이 문서는 현재 P0·P1 데이터 모델과 데이터 제약, 그리고 승인된 P2 `MATCH-01`의 계획 저장 계약을 정의한다. 이 문서에 적은 P1 알림·채팅·다중 인스턴스 스케줄 잠금·소셜 계정·대기열·게임 검색 수치·메타데이터·메커니즘·사용자별 해 본 게임 관계는 전진 Flyway 마이그레이션과 생산 코드에 반영돼 있다. 아래 P2 MATCH 절은 구현 목표일 뿐 아직 Flyway·JPA 엔티티·생산 코드에 반영되지 않았다. P1 종료 상태는 [P1 기능 종료 상태](archive/p1/README.md#기능별-종료-상태), P2 진행 상태는 [P2 기능 상태](p2/README.md#기능별-현재-상태)를 따른다.

### 이 문서의 범위

| 구분 | 내용 |
|---|---|
| 이 문서가 정본인 것 | 테이블·컬럼·타입·DB 제약, 저장 계산식과 저장 불변식 |
| 이 문서가 담지 않는 것 | 제품 규칙(상태 전이·권한·시간·정원)은 [P2 명세](P2-spec.md), [P1 종료 명세](archive/p1/README.md)와 [P0-spec](archive/p0/P0-spec.md#공통-규칙), 요청·응답 계약은 [API](API.md), 기술 결정 이유는 [ADR](adr/README.md) |
| P2 MATCH 표기 | 아래 P2 MATCH 테이블·인덱스는 승인된 계획 계약이다. 현재 물리 schema·JPA 매핑·API 제공 여부는 [P2 기능 상태](p2/README.md#기능별-현재-상태)로만 판정한다. |
| 변경 시 함께 갱신 | 스키마를 바꾸면 Flyway 마이그레이션과 JPA 엔티티를 같은 변경에서 일치시킨다(→ [마이그레이션 작업 안내](../src/main/resources/db/migration/AGENTS.md), [ADR-0008](adr/platform/0008-flyway-database-migrations.md)) |

## 기준과 범위

- 기준: 새 P2 저장 계약은 [P2 기능 명세](p2/README.md)와 필요한 ADR을 먼저 확정하고 같은 변경에서 이 문서에 반영한다. 아래 P2 MATCH 절의 후속 물리 구현도 같은 기준을 따른다. 기존 P0·P1 규칙은 [P0 공통 명세](archive/p0/P0-spec.md), [P1 종료 명세](archive/p1/README.md)와 [관련 ADR](adr/README.md)을 따른다.
- 범위: 현재 P0의 오프라인 방·게임 목록·사용자·방 참가, P1의 소셜 계정·대기열과 게임 검색 수치·메커니즘 목록·관계·사용자별 해 본 게임 관계·서비스 내 알림·방별 채팅·공용 스케줄 잠금, 3차 MVP RANK-02 인기 점수, P2 계획의 Board Game Arena 고정 MATCH 요청·제안·성공 파티·전용 채팅·신고·차단
- 제외: 기존 ROOM을 확장한 온라인 방·온라인 ROOM 자동 매칭, 후기, 룰마스터 가능 게임, 결제·포인트. 아래 `MATCH-01`은 기존 ROOM·참가·대기열과 별개인 Board Game Arena 고정 P2 계획 계약이므로 이 제외 범위에 포함하지 않는다.
- P0 검색: 게임 목록은 게임명 `keyword`, 사람 중심 방 목록은 방 제목 `keyword` 검색을 지원한다. 게임 태그는 표시값이며 필터가 아니다.
- 시간대가 겹치는 서로 다른 방에는 같은 사용자가 동시에 참가할 수 있다. 따라서 종료 시각과 시간 중복 제약은 두지 않는다.

## 관계도

아래 관계도는 현재 구현된 P0·P1 저장 구조만 나타낸다. P2 MATCH의 계획 관계는 [P2 MATCH 저장 계약](#p2-match-저장-계약-계획미구현)에서 별도로 표현한다.

~~~mermaid
erDiagram
    USERS ||--o{ ROOMS : "개설"
    USERS ||--o{ SOCIAL_ACCOUNTS : "외부 신원 연결"
    GAMES o|--o{ ROOMS : "선택됨"
    USERS ||--o{ USER_PLAYED_GAMES : "해 본 게임 표시"
    GAMES ||--o{ USER_PLAYED_GAMES : "표시됨"
    GAMES ||--o{ GAME_MECHANISM_RELATIONS : "메커니즘 관계"
    GAME_MECHANISMS ||--o{ GAME_MECHANISM_RELATIONS : "게임 관계"
    GAMES ||--o{ GAME_CATEGORY_RELATIONS : "카테고리 관계"
    GAME_CATEGORIES ||--o{ GAME_CATEGORY_RELATIONS : "게임 관계"
    GAMES ||--o{ GAME_THEME_RELATIONS : "테마 관계"
    GAME_THEMES ||--o{ GAME_THEME_RELATIONS : "게임 관계"
    GAMES ||--o{ GAME_PLAYER_PREFERENCES : "추천·베스트 인원"
    USERS ||--o{ PARTICIPATIONS : "참가"
    ROOMS ||--o{ PARTICIPATIONS : "참가 관계 보유"
    USERS ||--o{ ROOM_WAITLISTS : "대기"
    ROOMS ||--o{ ROOM_WAITLISTS : "대기열 보유"
    ROOMS ||--o| CHAT_ROOMS : "채팅방 0 또는 하나"
    CHAT_ROOMS ||--o{ CHAT_MESSAGES : "메시지 보유"
    USERS ||--o{ CHAT_MESSAGES : "메시지 작성"

    USERS {
        BIGINT id PK
        VARCHAR email UK
        VARCHAR password_hash
        VARCHAR nickname
        VARCHAR profile_image_url
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    SOCIAL_ACCOUNTS {
        BIGINT id PK
        BIGINT user_id FK
        VARCHAR provider
        VARCHAR provider_subject
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    GAMES {
        BIGINT id PK
        BIGINT bgg_id UK
        VARCHAR name
        VARCHAR english_name
        VARCHAR alias
        VARCHAR image_url
        VARCHAR supported_player_count
        VARCHAR recommended_player_count
        VARCHAR best_player_count
        VARCHAR tag
        VARCHAR estimated_play_time
        INT min_players
        INT max_players
        INT min_play_time_minutes
        INT max_play_time_minutes
        DECIMAL complexity
        INT release_year
        INT min_age
        DECIMAL popularity_score
        TEXT description
        TEXT detail_description
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    USER_PLAYED_GAMES {
        BIGINT id PK
        BIGINT user_id FK
        BIGINT game_id FK
        TIMESTAMPTZ created_at
    }

    GAME_MECHANISMS {
        BIGINT id PK
        BIGINT bgg_mechanism_id UK
        VARCHAR code UK
        VARCHAR name_ko
        VARCHAR name_en
        SMALLINT featured_order UK
        BOOLEAN is_public
        VARCHAR source_reference
        VARCHAR reviewed_by
        TIMESTAMPTZ reviewed_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    GAME_MECHANISM_RELATIONS {
        BIGINT game_id PK, FK
        BIGINT mechanism_id PK, FK
    }

    GAME_CATEGORIES {
        BIGINT id PK
        VARCHAR code UK
        VARCHAR bgg_subdomain UK
        VARCHAR name_ko
        VARCHAR name_en
        SMALLINT display_order UK
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    GAME_CATEGORY_RELATIONS {
        BIGINT game_id PK, FK
        BIGINT category_id PK, FK
    }

    GAME_THEMES {
        BIGINT id PK
        BIGINT bgg_theme_id UK
        VARCHAR code UK
        VARCHAR name_ko
        VARCHAR name_en
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    GAME_THEME_RELATIONS {
        BIGINT game_id PK, FK
        BIGINT theme_id PK, FK
    }

    GAME_PLAYER_PREFERENCES {
        BIGINT game_id PK, FK
        INT player_count PK
        BOOLEAN is_recommended
        BOOLEAN is_best
    }

    ROOMS {
        BIGINT id PK
        BIGINT game_id FK
        BIGINT host_user_id FK
        VARCHAR(20) room_type
        VARCHAR title
        VARCHAR description
        VARCHAR(30) experience_level
        BOOLEAN is_rulemaster_led
        VARCHAR region
        INT capacity
        INT active_participant_count
        TIMESTAMPTZ start_at
        VARCHAR place
        VARCHAR(20) status
        BIGINT version
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    PARTICIPATIONS {
        BIGINT id PK
        BIGINT room_id FK
        BIGINT user_id FK
        VARCHAR(20) status
        TIMESTAMPTZ joined_at
        TIMESTAMPTZ canceled_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    ROOM_WAITLISTS {
        BIGINT room_id PK, FK
        BIGINT user_id PK, FK
        VARCHAR status
        BIGINT queue_order
        TIMESTAMPTZ queued_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    CHAT_ROOMS {
        BIGINT id PK
        BIGINT room_id FK, UK
        TIMESTAMPTZ purge_after
        TIMESTAMPTZ messages_purged_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    CHAT_MESSAGES {
        BIGINT id PK
        BIGINT chat_room_id FK
        BIGINT sender_user_id FK
        VARCHAR client_message_id
        TEXT content
        TIMESTAMPTZ created_at
    }

    ROOM_STATUS_CORRECTION_PROGRESS {
        VARCHAR job_name PK
        TIMESTAMPTZ turn_cutoff
        TIMESTAMPTZ cursor_due_at
        BIGINT cursor_room_id
        BIGINT progress_version
        BIGINT execution_generation
        TIMESTAMPTZ updated_at
    }

    SHEDLOCK {
        VARCHAR name PK
        TIMESTAMPTZ lock_until
        TIMESTAMPTZ locked_at
        VARCHAR locked_by
    }
~~~

## Enum

| 이름 | 값 | 의미 |
|---|---|---|
| room_type | `GAME_FOCUSED`, `PERSON_FOCUSED` | 게임 중심 또는 사람 중심 방 |
| room_status | `RECRUITING`, `CLOSED`, `CANCELED`, `FINISHED` | 모집 중, 모집 종료, 취소, 종료 |
| participation_status | `ACTIVE`, `CANCELED` | 활성 참가, 참가 취소 |
| waitlist_status | `WAITING`, `PROMOTED`, `CANCELED`, `EXPIRED`, `ROOM_CANCELED` | 현재 대기, 승격 완료, 본인 취소, 시작 경계 만료, ROOM 취소 종료 |
| social_provider | `GOOGLE`, `NAVER`, `KAKAO` | 외부 로그인 제공자 |
| experience_level | `ALL_LEVELS`, `BEGINNER_WELCOME`, `EXPERIENCED_PREFERRED` | 방이 권장하는 경험 수준 |
| notification_outbox_event_type | `PARTICIPATION_JOINED`, `PARTICIPATION_CANCELED`, `WAITLIST_PROMOTED`, `ROOM_CANCELED` | 참가·재참가 성공, 참가 취소 성공, 대기자 자동 승격 성공, 방 취소 성공이라는 모듈 간 원인 사실 |
| notification_outbox_status | `PENDING`, `RETRY_WAIT`, `PROCESSED`, `FAILED`, `DISCARDED` | 최초 대기, 자동·수동 재처리 대기, 처리 완료, 운영 조치 대기 실패, 운영 폐기 |
| notification_type | `PARTICIPANT_JOINED`, `PARTICIPANT_CANCELED`, `WAITLIST_PROMOTED`, `ROOM_CANCELED` | 사용자에게 표시하는 새 참가자, 빈자리, 대기자 자동 승격, 방 취소 알림 유형 |

이 절의 값 집합은 Java enum과 업무상 허용 값을 설명하는 논리 enum이다. Java의 `@Enumerated(EnumType.STRING)` 매핑은 PostgreSQL 네이티브 enum type을 만들지 않는다. `ROOMS.room_type`, `ROOMS.experience_level`, `ROOMS.status`, `PARTICIPATIONS.status`는 V1에서 각각 `VARCHAR(20)`, `VARCHAR(30)`, `VARCHAR(20)`, `VARCHAR(20)`과 이름 있는 `CHECK` 제약으로 저장한다. P1 소셜 제공자·대기 상태와 알림의 제한 값도 PostgreSQL 네이티브 enum이 아니라 `VARCHAR`와 이름 있는 `CHECK` 제약으로 저장한다.

## 테이블 명세

표기: PK = 기본 키, FK = 외래 키, UQ = 유일 제약, NN = NOT NULL, AI = 자동 증가.

별도 단일 식별자를 사용하는 업무 테이블의 기본 키는 PostgreSQL `BIGINT GENERATED BY DEFAULT AS IDENTITY`를 사용하며 기본값으로 1부터 증가한다. 외래 키는 참조 대상과 같은 `BIGINT` 값만 저장하고 자동 증가하지 않는다. 해당 JPA 엔티티에서는 `Long`과 `GenerationType.IDENTITY`로 매핑한다. `ROOM_WAITLISTS`는 예외로 `(room_id, user_id)` 복합 기본 키를 사용하며 별도 단일 ID를 두지 않는다. 기술 테이블 `ROOM_STATUS_CORRECTION_PROGRESS`와 `SHEDLOCK`은 작업 이름을 기본 키로 사용한다.

### USERS

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 사용자 식별자 |
| email | VARCHAR(255) | UQ, NULL | 이메일 회원의 로그인 이메일 또는 소셜 첫 로그인에서 신뢰 조건을 통과한 선택 이메일. 소셜 신원 키로 사용하지 않음 |
| password_hash | VARCHAR(255) | NULL | 이메일 회원은 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)의 `{bcrypt}` 식별자와 cost를 포함한 bcrypt 해시. 소셜 전용 사용자는 `NULL`, 원문 저장 금지 |
| nickname | VARCHAR(50) | NN | 방 개설자·참가자 표시명 |
| created_at | TIMESTAMPTZ | NN | 가입 시각 |
| updated_at | TIMESTAMPTZ | NN | 프로필 수정 시각 |

이메일 회원가입은 `email`과 `password_hash`를 모두 저장한다. 소셜 전용 사용자는 둘 다 `NULL`이거나 [AUTH-05의 제공자별 신뢰 조건](archive/p1/social-login.md#제공자-이메일-매핑)을 통과하고 기존 사용자와 겹치지 않는 이메일과 `NULL` 비밀번호를 가질 수 있다. 신뢰 상태가 없거나 조건을 통과하지 못한 제공자 이메일은 `NULL`로 저장한다. `password_hash`가 있으면 `email`도 반드시 있어야 한다. 반대로 `password_hash`가 `NULL`이면 값이 있는 이메일도 로그인 자격증명이 아니며, 이메일 자격증명 조회는 해당 행을 미존재와 동일하게 처리한다.

### SOCIAL_ACCOUNTS

[AUTH-05](archive/p1/social-login.md#auth-05-소셜-로그인계정-연결)의 외부 신원 연결 정본이다. 이메일·닉네임·token은 저장하지 않고 제공자와 그 제공자가 보장하는 subject만 저장한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 소셜 계정 연결 식별자 |
| user_id | BIGINT | FK → USERS.id, NN | 연결된 알밤메이트 사용자 |
| provider | VARCHAR(20) | NN | `GOOGLE`, `NAVER`, `KAKAO` 중 하나 |
| provider_subject | VARCHAR(255) | NN | 제공자 내부의 변경하지 않는 사용자 식별자 |
| created_at | TIMESTAMPTZ | NN | 연결 시각 |
| updated_at | TIMESTAMPTZ | NN | 연결 레코드 갱신 시각 |

### GAMES

게임은 운영자가 사전에 입력하는 참조 데이터다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 게임 식별자 |
| bgg_id | BIGINT | UQ, NN | BoardGameGeek가 부여한 외부 게임 식별자 |
| name | VARCHAR(255) | NN | 게임명 |
| english_name | VARCHAR(255) | NN | 영문명 |
| alias | VARCHAR(255) | NULL | 별칭 |
| image_url | VARCHAR(500) | NULL | 대표 이미지 주소 |
| supported_player_count | VARCHAR(50) | NN | 표시용 가능 인원. 게임 규칙상 플레이 가능한 범위, 예: `2~4명` |
| recommended_player_count | VARCHAR(50) | NULL | 과거 표시용 추천 인원 문자열. 검색·상세 정본은 GAME_PLAYER_PREFERENCES 관계 |
| best_player_count | VARCHAR(50) | NULL | 과거 표시용 베스트 인원 문자열. 검색·상세 정본은 GAME_PLAYER_PREFERENCES 관계 |
| tag | VARCHAR(30) | NN | 게임 스타일 태그 |
| estimated_play_time | VARCHAR(50) | NN | 표시용 예상 플레이 시간 |
| min_players | INTEGER | NULL | 검색용 가능 인원 최소값. `max_players`와 함께 `NULL`이거나 양의 정수이며 최소값 이하 |
| max_players | INTEGER | NULL | 검색용 가능 인원 최대값. `min_players`와 함께 `NULL`이거나 양의 정수이며 최소값 이상 |
| min_play_time_minutes | INTEGER | NULL | 검색용 플레이 시간 최소값(분). 최대값과 함께 `NULL`이거나 양의 정수이며 최소값 이하 |
| max_play_time_minutes | INTEGER | NULL | 검색용 플레이 시간 최대값(분). 최소값과 함께 `NULL`이거나 양의 정수이며 최소값 이상 |
| complexity | DECIMAL(3,2) | NULL, 1.00~5.00 | BGG 복잡도. 입력 `0.00`은 평가 없음으로 `NULL` 정규화 |
| release_year | INTEGER | NULL | BGG 기준 CSV의 `yearpublished`. 미상 값은 `NULL` |
| min_age | INTEGER | NULL | BGG thing XML의 `minage`. 누락 또는 `0`은 `NULL` |
| popularity_score | DECIMAL(8,6) | NN, 0 이상 1 이하, 기본값 0 | RANK-02 배치가 승인된 BoardLife·Albam·BGG 원천을 6:3:1로 결합한 저장 파생 점수. 응답에는 노출하지 않음 |
| description | TEXT | NN | 게임 상세 화면에 표시하는 간단 설명 |
| detail_description | TEXT | NN | 게임 상세 설명 |
| created_at | TIMESTAMPTZ | NN | 등록 시각 |
| updated_at | TIMESTAMPTZ | NN | 마지막 수정 시각 |

예정 모임 수는 저장하지 않는다. 미래 시점의 `GAME_FOCUSED` 방 중 `CANCELED`, `FINISHED`가 아닌 건수를 조회 시 계산한다.

### USER_PLAYED_GAMES

`SEARCH-03`이 제공하는 관계 테이블이다. 사용자가 직접 해 본 게임으로 표시한 현재 관계만 저장하며, 관계가 없다는 사실을 실제 미플레이로 해석하지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 관계 식별자. `GENERATED BY DEFAULT AS IDENTITY` |
| user_id | BIGINT | FK → USERS.id, NN, ON DELETE NO ACTION | 표시한 사용자 |
| game_id | BIGINT | FK → GAMES.id, NN, ON DELETE NO ACTION | 표시한 게임 |
| created_at | TIMESTAMPTZ | NN | 사용자가 표시한 시각. 실제 플레이 날짜가 아님 |

`updated_at`, 상태 enum, 취소 시각과 플레이 날짜·횟수는 두지 않는다. 표시 취소는 관계 행을 삭제하고, 다시 표시하면 새 관계와 새 표시 시각을 만든다.

### GAME_MECHANISMS

[ADR-0048](adr/game/0048-full-reviewed-game-mechanism-catalog.md)의 검수된 내부 메커니즘 목록이다. 표시명이 바뀌어도 `id`와 `code` 의미는 유지하고, 새 항목은 기본 비공개로 저장한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 내부 메커니즘 식별자 |
| bgg_mechanism_id | BIGINT | UQ, NN | BGG 원본 메커니즘 식별자 |
| code | VARCHAR(64) | UQ, NN | API 검색에 쓰는 안정적인 `UPPER_SNAKE_CASE` 코드 |
| name_ko | VARCHAR(100) | NN | 검수된 한국어 표시명 |
| name_en | VARCHAR(100) | NN | BGG 영문명 |
| description_ko | VARCHAR(300) | NULL | 검수된 한국어 메커니즘 설명. 기존 목록은 적재 배치에서 채운다 |
| featured_order | SMALLINT | UQ, NULL | 대표 8개는 `1`~`8`, 나머지는 `NULL` |
| is_public | BOOLEAN | NN, DEFAULT `false` | 검수 후 선택지·검색에 공개할지 여부 |
| source_reference | VARCHAR(500) | NN | 다시 확인 가능한 출처·배치 근거 |
| reviewed_by | VARCHAR(100) | NULL | 공개 항목 검수자 |
| reviewed_at | TIMESTAMPTZ | NULL | 공개 항목 검수 시각 |
| created_at | TIMESTAMPTZ | NN | 목록 행 생성 시각 |
| updated_at | TIMESTAMPTZ | NN | 표시명·공개 상태 갱신 시각 |

현재 공개 189개와 관계의 정확한 입력 checksum·매핑·검수 범위는 카탈로그 manifest와 품질 보고서가 소유한다. 원본 JSON·CSV와 생성 SQL은 저장소에 커밋하지 않는다.

### GAME_MECHANISM_RELATIONS

게임과 메커니즘의 다대다 관계다. 별도 관계 ID를 만들지 않고 두 외래 키를 복합 기본 키로 사용한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| game_id | BIGINT | PK, FK → GAMES.id, NN | 알밤메이트 내부 게임 식별자 |
| mechanism_id | BIGINT | PK, FK → GAME_MECHANISMS.id, NN | 내부 메커니즘 식별자 |

두 외래 키는 참조 행 삭제 시 관계만 함께 삭제한다. 서비스 카탈로그 적재는 `bgg_id`와 `bgg_mechanism_id`로 내부 ID를 해석해 반복 실행해도 같은 복합 키 한 건으로 수렴한다.

### GAME_CATEGORIES

[ADR-0050](adr/game/0050-game-metadata-catalog-and-filters.md)의 고정 8개 BGG subdomain 그룹이다. code와 표시명은 분리하며, rank가 없는 게임에 카테고리를 추정하지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 내부 카테고리 식별자 |
| code | VARCHAR(64) | UQ, NN | API 검색에 쓰는 안정적인 UPPER_SNAKE_CASE code |
| bgg_subdomain | VARCHAR(32) | UQ, NN | 순위 CSV의 BGG subdomain |
| name_ko | VARCHAR(100) | NN | 화면 표시 한글명 |
| name_en | VARCHAR(100) | NN | BGG 영문 그룹명 |
| display_order | SMALLINT | UQ, NN, 1~8 | 화면 고정 노출 순서 |
| created_at | TIMESTAMPTZ | NN | 목록 행 생성 시각 |
| updated_at | TIMESTAMPTZ | NN | 목록 행 갱신 시각 |

### GAME_CATEGORY_RELATIONS

게임과 카테고리의 다대다 관계다. 순위 CSV의 해당 subdomain rank가 양수인 경우만 연결한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| game_id | BIGINT | PK, FK → GAMES.id, NN, ON DELETE CASCADE | 알밤메이트 내부 게임 식별자 |
| category_id | BIGINT | PK, FK → GAME_CATEGORIES.id, NN, ON DELETE CASCADE | 내부 카테고리 식별자 |

### GAME_THEMES

BGG boardgamecategory 원본을 검수된 한국어 표시명과 안정 code로 관리하는 내부 목록이다. 한글명·영문명·원본 ID·code가 완성되지 않은 항목은 적재·공개하지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 내부 테마 식별자 |
| bgg_theme_id | BIGINT | UQ, NN | BGG boardgamecategory 원본 식별자 |
| code | VARCHAR(64) | UQ, NN | API 검색에 쓰는 안정적인 UPPER_SNAKE_CASE code |
| name_ko | VARCHAR(100) | NN | 검수된 화면 표시 한글명 |
| name_en | VARCHAR(100) | NN | BGG 원문 영문명 |
| created_at | TIMESTAMPTZ | NN | 목록 행 생성 시각 |
| updated_at | TIMESTAMPTZ | NN | 목록 행 갱신 시각 |

### GAME_THEME_RELATIONS

게임과 테마의 다대다 관계다. XML에 같은 link가 반복되어도 복합 키 한 행만 저장한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| game_id | BIGINT | PK, FK → GAMES.id, NN, ON DELETE CASCADE | 알밤메이트 내부 게임 식별자 |
| theme_id | BIGINT | PK, FK → GAME_THEMES.id, NN, ON DELETE CASCADE | 내부 테마 식별자 |

### GAME_PLAYER_PREFERENCES

가능 인원과 분리한 BGG suggested_numplayers 투표의 게임별 추천·베스트 인원 관계다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| game_id | BIGINT | PK, FK → GAMES.id, NN, ON DELETE CASCADE | 알밤메이트 내부 게임 식별자 |
| player_count | INTEGER | PK, NN, > 0 | 정규화된 실제 인원 수 |
| is_recommended | BOOLEAN | NN | 추천 인원 여부 |
| is_best | BOOLEAN | NN, is_best → is_recommended | 베스트 인원 여부 |

### ROOMS

ERD의 `ROOMS` 표기는 물리 테이블명 `rooms`를 뜻한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 방 식별자 |
| game_id | BIGINT | FK → GAMES.id, NULL | 선택 게임. GAMES.id와 같은 BIGINT여야 함 |
| host_user_id | BIGINT | FK → USERS.id, NN | 개설자 사용자 식별자 |
| room_type | VARCHAR(20) | NN, `ck_rooms_room_type`: `CHECK (room_type IN ('GAME_FOCUSED', 'PERSON_FOCUSED'))` | `GAME_FOCUSED` 또는 `PERSON_FOCUSED` |
| title | VARCHAR(100) | NN | 모임 제목 |
| description | VARCHAR(255) | NULL | 선택 모임 소개 |
| experience_level | VARCHAR(30) | NN, `ck_rooms_experience_level`: `CHECK (experience_level IN ('ALL_LEVELS', 'BEGINNER_WELCOME', 'EXPERIENCED_PREFERRED'))` | `ALL_LEVELS`, `BEGINNER_WELCOME`, `EXPERIENCED_PREFERRED` |
| is_rulemaster_led | BOOLEAN | NN | 개설자의 룰마스터 진행 자기신고 |
| region | VARCHAR(50) | NN, DEFAULT `홍대` | 모임 지역 |
| capacity | INT | NN | 방 생성 시 입력하는 개설자 제외 모집 정원 |
| active_participant_count | INT | NN, DEFAULT 0 | 개설자를 제외한 현재 `ACTIVE` 참가 관계 수 |
| start_at | TIMESTAMPTZ | NN | 실제 모임 시작 시각 |
| place | VARCHAR(100) | NN | 모임 장소 |
| status | VARCHAR(20) | NN, `ck_rooms_status`: `CHECK (status IN ('RECRUITING', 'CLOSED', 'CANCELED', 'FINISHED'))` | `RECRUITING`, `CLOSED`, `CANCELED`, `FINISHED` |
| version | BIGINT | NN, DEFAULT 0 | `ROOMS`의 동시 변경을 감지하는 낙관 락 버전 |
| created_at | TIMESTAMPTZ | NN | 생성 시각 |
| updated_at | TIMESTAMPTZ | NN | 수정 시각 |

`idx_rooms_public_start_at_id (start_at, id) WHERE status IN ('RECRUITING', 'CLOSED')`는 공개 방의 날짜 범위와 `startsAt ASC, id ASC` 페이지를 위한 PostgreSQL 전용 부분 인덱스다.

### PARTICIPATIONS

개설자는 이 테이블에 넣지 않는다. 이 테이블은 개설자 외 사용자의 방 참가 관계만 저장한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 참가 관계 식별자 |
| room_id | BIGINT | FK → ROOMS.id, NN | 대상 방 |
| user_id | BIGINT | FK → USERS.id, NN | 참가 사용자 |
| status | VARCHAR(20) | NN, `ck_participations_status`: `CHECK (status IN ('ACTIVE', 'CANCELED'))` | `ACTIVE` 또는 `CANCELED` |
| joined_at | TIMESTAMPTZ | NN | 최근 참가 확정 시각 |
| canceled_at | TIMESTAMPTZ | NULL | 참가 취소 시각 |
| created_at | TIMESTAMPTZ | NN | 참가 관계 생성 시각 |
| updated_at | TIMESTAMPTZ | NN | 마지막 수정 시각 |

### ROOM_WAITLISTS

> 이 절은 [ADR-0046](adr/participation/0046-room-waitlist-persistence-conditional-transition-retry.md)과 [PART-04a 최신 전체 테스트 계약](https://github.com/bamsongi-club/albam-mate/issues/302#issuecomment-5177035904)이 승인한 저장 기반 계약이다. `PART-04`의 현재 구현·자동 검증 상태는 [P1 기능 종료 상태의 `PART-04`](archive/p1/README.md#기능별-종료-상태)에 기록된 `구현 완료`·`검증 완료`를 따른다.

`V14__create_room_waitlist_schema.sql`은 같은 ROOM과 사용자의 최신 대기 결과를 한 행으로 저장하고, PostgreSQL 전용 `V15__create_room_waitlist_partial_indexes.sql`은 현재 `WAITING` 행만 대상으로 하는 부분 인덱스를 만든다. 상태 변경별 이력 행, 별도 단일 `id`와 대기 행의 `version`은 두지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| room_id | BIGINT | PK, FK → ROOMS.id, NN | 대상 방 식별자 |
| user_id | BIGINT | PK, FK → USERS.id, NN | 대기 사용자 식별자 |
| status | VARCHAR(20) | NN | `WAITING`, `PROMOTED`, `CANCELED`, `EXPIRED`, `ROOM_CANCELED` 중 하나 |
| queue_order | BIGINT | NN, `> 0`, DEFAULT 없음 | 전역 sequence가 발급하는 FIFO 정렬 키 |
| queued_at | TIMESTAMPTZ | NN | 현재 순번을 받은 신청·재신청 시각 |
| created_at | TIMESTAMPTZ | NN | 최초 대기 관계 생성 시각 |
| updated_at | TIMESTAMPTZ | NN | 마지막 상태 변경 시각 |

#### 대기열 sequence

| 항목 | 값 |
|---|---|
| 이름 | `room_waitlist_queue_order_seq` |
| 타입 | `BIGINT` |
| 시작·증가 | `START 1`, `INCREMENT 1` |
| 순환·캐시 | `NO CYCLE`, `CACHE 1` |
| `queue_order` 기본값 | 없음. ROOM version claim 성공 뒤 코드가 명시적으로 발급 |

트랜잭션 롤백으로 소비된 sequence 번호의 공백은 정상이며 반환하거나 재사용하지 않는다. 현재 ROOM별 FIFO는 현재 `WAITING` 행의 `queue_order ASC`만으로 결정하고 화면의 `position`은 저장하지 않고 조회 시 계산한다.

#### 대기열 제약

| 이름 | 정의 | 의미 |
|---|---|---|
| `pk_room_waitlists` | `PRIMARY KEY (room_id, user_id)` | 같은 ROOM·사용자의 최신 대기 관계를 한 행으로 제한 |
| `fk_room_waitlists_room` | `FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE NO ACTION` | ROOM 삭제로 대기 결과를 자동 삭제하지 않음 |
| `fk_room_waitlists_user` | `FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE NO ACTION` | 사용자 삭제로 대기 결과를 자동 삭제하지 않음 |
| `ck_room_waitlists_status` | `CHECK (status IN ('WAITING', 'PROMOTED', 'CANCELED', 'EXPIRED', 'ROOM_CANCELED'))` | 승인된 다섯 상태만 저장 |
| `ck_room_waitlists_queue_order_positive` | `CHECK (queue_order > 0)` | 순번을 양수로 제한 |

#### 대기열 인덱스

| 인덱스 | 정의 | 대표 쿼리 |
|---|---|---|
| `uq_room_waitlists_waiting_room_queue_order` | `UNIQUE (room_id, queue_order) WHERE status = 'WAITING'` | ROOM의 현재 WAITING FIFO와 동일 순번 충돌 방어 |
| `idx_room_waitlists_waiting_user_room` | `(user_id, room_id) WHERE status = 'WAITING'` | 사용자의 여러 ROOM 현재 WAITING 일괄 조회 |

부분 인덱스를 사용하는 전용 SQL은 predicate를 정확히 `status = 'WAITING'` literal로 작성한다. `CANCELED`·`PROMOTED` 재신청은 `queued_at`과 `updated_at`을 새 request time으로 바꾸고 `created_at`을 보존한다. 종료 전이는 `queue_order`·`queued_at`·`created_at`을 보존하고 `updated_at`만 해당 유스케이스의 고정 시각으로 바꾼다.

### CHAT_ROOMS

P1 채팅방을 저장하는 구현된 테이블이다. `V6__create_p1_chat_room_schema.sql`은 테이블·제약만 생성하고 기존 `ROOMS`를 조회하거나 `CHAT_ROOMS` 행을 backfill하지 않는다. [#279의 최신 승인 테스트 계약](https://github.com/bamsongi-club/albam-mate/issues/279#issuecomment-5161788285)은 기존 ROOM 초기화와 ROOM 생성·상태 전환의 경합·최종 보정·배포 절체를 [#281](https://github.com/bamsongi-club/albam-mate/issues/281)의 후속 범위로 분리한다. [ADR-0045](adr/chat/0045-chat-room-schema-and-backfill-boundary.md)은 production 스키마 기동과 local profile callback 초기화의 경계를 승인한다. production profile은 `db/local`을 로드하지 않으므로 일반 애플리케이션 기동과 Flyway 자동 실행에는 live ROOM 데이터 작업이 없다.

활성화 뒤 새 방은 방 생성 트랜잭션에서 `ROOMS`와 `CHAT_ROOMS`를 함께 생성한다. 채팅 회원을 별도로 저장하지 않고, 접근 권한은 `ROOMS.host_user_id`와 현재 `ACTIVE PARTICIPATIONS`를 매 요청에서 계산한다.

명시적 #281 backfill 작업은 하나의 초기화 기준 시각과 승인된 쓰기 통제 경계 안에서 기존 `RECRUITING`·`CLOSED` 방의 보관 시각을 `NULL`로 두고, 기존 `CANCELED`·`FINISHED` 방은 같은 기준 시각의 빈 보관 완료 값으로 초기화한다. `ROOMS.updated_at`을 과거 최종 상태 전환 시각으로 추정하지 않는다. 활성화 뒤 최종 상태로 전환되는 방은 30일 보관 계약을 그대로 따른다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 채팅방 식별자 |
| room_id | BIGINT | FK → ROOMS.id, UQ, NN | 연결된 ROOM. 채팅방은 ROOM 없이 존재할 수 없고, ROOM당 채팅방은 최대 하나 |
| purge_after | TIMESTAMPTZ | NULL | 방이 최종 상태로 전환된 시각부터 30일 뒤의 메시지 삭제 기준 시각 |
| messages_purged_at | TIMESTAMPTZ | NULL | 만료 메시지 일괄 삭제 완료 시각 |
| created_at | TIMESTAMPTZ | NN | 채팅방 생성 시각 |
| updated_at | TIMESTAMPTZ | NN | 마지막 채팅방 메타데이터 변경 시각 |

채팅방의 사용자 접근 가능 상태를 별도 상태 컬럼으로 복제하지 않는다. `ROOMS.status`가 `RECRUITING`·`CLOSED`이고 요청자가 주최자 또는 현재 `ACTIVE` 참가자일 때만 일반 사용자 접근을 허용한다. `CANCELED`·`FINISHED` 방은 일반 사용자에게 채팅 이력·전송·실시간 구독을 제공하지 않는다.

### CHAT_MESSAGES

P1 CHAT-02의 V9 전진 Flyway가 생성하는 메시지 저장의 최종 정본이다([ADR-0033](adr/chat/0033-postgresql-source-after-commit-delivery.md)). `id`는 승인된 [ADR-0031](adr/chat/0031-chat-history-cursor-pagination.md)의 커서와 실시간 catch-up 기준으로 사용하며, 클라이언트 시각으로 순서를 정하지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 서버가 부여하는 메시지 식별자·정렬 기준 |
| chat_room_id | BIGINT | FK → CHAT_ROOMS.id, NN | 대상 채팅방 |
| sender_user_id | BIGINT | FK → USERS.id, NN | 메시지 작성자 |
| client_message_id | VARCHAR(100) | NN | 재시도 멱등성 키 |
| content | TEXT | NN | 앞뒤 공백 제거 후 1~500자의 일반 텍스트 |
| created_at | TIMESTAMPTZ | NN | 서버 저장 시각 |

### ROOM_STATUS_CORRECTION_PROGRESS

`V22__create_room_status_correction_progress.sql`이 생성하는 ROOM-09 Scheduler 전용 영속 진행 상태다. `SHEDLOCK`이 현재 실행 주체만 조정하는 것과 달리, 이 행은 인스턴스 재시작·실행 주체 변경 뒤 이어갈 순회 경계와 마지막 시도 위치를 저장한다. API 요청 경계 상태 보정과 채팅 만료 삭제는 이 테이블을 사용하지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| job_name | VARCHAR(64) | PK, NN | 고정값 `room-status-correction` |
| turn_cutoff | TIMESTAMPTZ | NULL | 현재 순회가 포함할 due 시각 상한. 최초 실행 전만 `NULL` |
| cursor_due_at | TIMESTAMPTZ | NULL | 마지막으로 시도한 후보의 선별 당시 논리적 처리 예정 시각 |
| cursor_room_id | BIGINT | NULL | 같은 시각의 동률을 해소하는 마지막 시도 ROOM ID. 위치 표식이므로 FK가 아님 |
| progress_version | BIGINT | NN | 모든 실행 세대 점유·cursor 전진·순회 회전마다 증가하는 CAS 값. 초깃값 `0` |
| execution_generation | BIGINT | NN | ShedLock 획득 뒤 새 실행 주체가 증가시키는 fencing 값. 초깃값 `0` |
| updated_at | TIMESTAMPTZ | NN, DEFAULT `CURRENT_TIMESTAMP` | 마지막 진행 상태 변경의 PostgreSQL 기준 시각 |

전진 마이그레이션은 위 고정 `job_name` 행 하나를 `turn_cutoff`·cursor `NULL`, 두 카운터 `0`으로 함께 생성한다. 런타임은 행 누락을 자동 삽입하거나 임의 값으로 복구하지 않는다.

### SHEDLOCK

`V16__create_p1_chat_retention_schema.sql`에 구현된 공용 기술 테이블이다. [ADR-0038](adr/platform/0038-multi-instance-session-and-scheduler-coordination.md)에 따라 모든 애플리케이션 인스턴스에 등록된 ROOM 상태 보정과 채팅 만료 삭제 Spring Scheduler 중 한 실행만 작업을 소유하도록 PostgreSQL 기반 ShedLock이 사용한다. 알림 relay, ROOM·참가 업무 락이나 영속 Job 큐로 사용하지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| name | VARCHAR(64) | PK, NN | 구현 이슈에서 작업별로 확정하는 고유 잠금 이름 |
| lock_until | TIMESTAMPTZ | NN | 다른 인스턴스가 잠금을 획득할 수 있는 시각 |
| locked_at | TIMESTAMPTZ | NN | 현재 잠금을 획득한 PostgreSQL 기준 시각 |
| locked_by | VARCHAR(255) | NN | 잠금을 획득한 애플리케이션 인스턴스 식별자 |

## 정원·참가자 표시 규칙

`ROOMS.capacity`는 데이터베이스에 저장하는 모집 정원이며, 개설자를 포함하지 않는다. `ROOMS.active_participant_count`는 개설자를 제외한 현재 점유 인원 수이며 현재 `ACTIVE PARTICIPATIONS` 수와 일치해야 한다. 화면에 표시하는 값은 아래처럼 계산한다.

| 표시 항목 | 계산식 |
|---|---|
| 방 생성 시 입력하는 모집 정원 | `capacity` |
| 방 상세의 정원 | `capacity + 1` |
| 방 상세의 참가자 수 | `active_participant_count + 1` |
| 방 상세의 참가자 목록 | 개설자 1명 + `ACTIVE PARTICIPATIONS` 사용자 목록 |

예를 들어 생성 시 모집 정원을 4명으로 입력하고 네 명이 모두 참가했다면, 방 상세에는 정원 5명, 참가자 수 5명이 표시되며 참가자 목록에는 개설자와 참가자 네 명이 함께 보인다.

## P1 알림 저장 계약

> 이 절의 저장 계약은 `V4__create_p1_notification_schema.sql`과 현재 알림 생산 코드에 구현돼 있다. 기능·자동 검증·운영 상태는 [P1 기능 종료 상태의 `NOTI-01`~`NOTI-03`](archive/p1/README.md#기능별-종료-상태)을 따른다.

### 알림 관계도

~~~mermaid
erDiagram
    ROOMS ||--o{ NOTIFICATION_OUTBOX_EVENTS : "알림 원인"
    NOTIFICATION_OUTBOX_EVENTS ||--o{ NOTIFICATION_OUTBOX_RECIPIENTS : "수신자 스냅샷"
    USERS ||--o{ NOTIFICATION_OUTBOX_RECIPIENTS : "수신 대상"
    USERS ||--o{ NOTIFICATIONS : "수신"
    ROOMS ||--o{ NOTIFICATIONS : "관련 모임"
~~~

`NOTIFICATIONS.source_event_id`는 Outbox의 식별자를 복사한 논리적 멱등성 키이며 FK가 아니다. 따라서 관계도에는 Outbox와 Notification 사이의 물리 관계선을 그리지 않는다. `(source_event_id, recipient_user_id)` 유일 제약은 아래 알림 관계 제약에서 정의한다.
이 절은 알림 저장 필드·타입·제약·인덱스의 정본이다. 원인 업무와 Outbox 기록 경계는 [ADR-0029](adr/notification/0029-room-integration-event-transactional-outbox.md), relay·복구·정리는 [ADR-0040](adr/notification/0040-postgresql-notification-relay-recovery-retention.md), 표시 투영과 조회·읽음 시각은 [ADR-0039](adr/notification/0039-notification-presentation-and-bulk-read-snapshot.md)을 따른다.

relay·재시도·보존·복구·cleanup 수치는 [알림 운영 파라미터 정본](guides/NOTIFICATION_OPERATIONS.md#현재-운영-파라미터-정본)이 소유한다. 아래의 대문자 파라미터 키는 현재 값을 마이그레이션에 대입하라는 뜻이며 SQL 식별자가 아니다. 값을 바꿀 때는 승인된 후속 ADR, 운영 정본, 전진 마이그레이션과 검증을 같은 변경에서 맞춘다.

### Notification Outbox Events

물리 테이블명은 `notification_outbox_events`다. 알림 원인 업무와 같은 트랜잭션에 저장하는 relay 작업이며, `id`가 Notification의 `sourceEventId`가 된다. 이벤트 payload를 범용 JSON으로 저장하지 않고 현재 승인된 방 변경 사실을 타입 컬럼으로 고정한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 원인 이벤트 식별자이자 Notification의 논리적 `source_event_id` |
| event_type | VARCHAR(30) | NN | `PARTICIPATION_JOINED`, `PARTICIPATION_CANCELED`, `WAITLIST_PROMOTED`, `ROOM_CANCELED` 중 하나 |
| room_id | BIGINT | FK → ROOMS.id, NN, ON DELETE NO ACTION | 원인 방. 방 조회 권한을 뜻하지 않음 |
| occurred_at | TIMESTAMPTZ | NN | Command Coordinator가 최초 시도 전에 고정하고 모든 낙관 락 재시도에 재사용한 업무 `requestTime` |
| recorded_at | TIMESTAMPTZ | NN | 최종 성공 트랜잭션이 PostgreSQL `clock_timestamp()`를 한 번 평가해 고정한 Outbox 기록 `operationTime` |
| status | VARCHAR(20) | NN, DEFAULT `PENDING` | relay 처리 상태 |
| available_at | TIMESTAMPTZ | NULL | `PENDING`, `RETRY_WAIT` 상태의 다음 처리 가능 시각. 최초 저장은 `recorded_at`과 같음 |
| failure_count | INT | NN, DEFAULT 0 | 현재 자동 처리 주기에 기록된 실패 횟수, `0..AUTO_PROCESS_MAX_ATTEMPTS` |
| total_failure_count | INT | NN, DEFAULT 0 | 수동 재처리 전후를 합친 누적 기록 실패 횟수 |
| last_failure_code | VARCHAR(50) | NULL | 마지막 구조화 실패 코드 |
| last_failed_at | TIMESTAMPTZ | NULL | 마지막 실패 기록 트랜잭션의 PostgreSQL `operationTime` |
| last_failure_class | VARCHAR(255) | NULL | 마지막 예외 분류. stack trace나 원본 SQL은 저장하지 않음 |
| last_failure_message | VARCHAR(500) | NULL | 길이를 제한하고 민감정보를 제거한 마지막 오류 설명 |
| reprocess_count | INT | NN, DEFAULT 0 | 운영자가 시작한 새 자동 처리 주기 수 |
| last_reprocessed_at | TIMESTAMPTZ | NULL | 마지막 수동 재처리 트랜잭션의 PostgreSQL `operationTime` |
| last_reprocess_reason | VARCHAR(500) | NULL | 비어 있지 않은 마지막 수동 재처리 자유 서술 원문. 구조화 로그에 기록하지 않음 |
| processed_at | TIMESTAMPTZ | NULL | 모든 수신자의 Notification 저장과 `PROCESSED` 전환에 사용한 relay `operationTime` |
| discarded_at | TIMESTAMPTZ | NULL | 운영자가 재처리하지 않고 폐기한 PostgreSQL `operationTime` |
| discard_reason | VARCHAR(500) | NULL | 비어 있지 않은 운영 폐기 자유 서술 원문. 구조화 로그에 기록하지 않음 |
| cleanup_at | TIMESTAMPTZ | NULL | `PROCESSED` 또는 `DISCARDED` 최소 이벤트 기록을 물리 삭제할 시각 |

### Notification Outbox Recipients

물리 테이블명은 `notification_outbox_recipients`다. 원인 업무의 최종 성공 트랜잭션이 확정한 수신자 스냅샷이며, relay 시점의 방·참가 관계를 다시 조회해 대상을 바꾸지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| outbox_event_id | BIGINT | PK, FK → NOTIFICATION_OUTBOX_EVENTS.id, NN, ON DELETE CASCADE | 원인 이벤트 |
| recipient_user_id | BIGINT | PK, FK → USERS.id, NN, ON DELETE NO ACTION | 확정 수신자. 사용자 삭제 시 자동 삭제하지 않음 |

`(outbox_event_id, recipient_user_id)`가 복합 기본 키이므로 같은 이벤트에 같은 사용자를 두 번 넣을 수 없다. 방 취소는 커밋 시점의 `ACTIVE` 참가자, 참가·재참가와 자동 승격 없는 참가 취소는 주최자 한 명을 저장한다. 자동 승격은 실제 `PROMOTED`·`ACTIVE` 전이를 커밋한 사용자 한 명을 저장한다. 수신자 수와 역할은 원인 업무가 같은 트랜잭션에서 검증하며 행 개수를 이용한 교차 행 CHECK는 두지 않는다. 수신자가 없는 방 취소는 Outbox 이벤트 자체를 만들지 않는다.

### NOTIFICATIONS

물리 테이블명은 `notifications`다. relay가 사용자별로 생성하는 앱 내 알림이며, `source_event_id`는 추적·멱등성용 논리 키다. 완료 Outbox 정리를 허용하기 위해 Outbox FK를 두지 않는다.

표시 문구와 방 제목 스냅샷은 저장하지 않는다. 클라이언트는 API의 `type`으로 표시 문구를 렌더링하고, 목록 응답의 `roomTitle`은 조회 시점의 현재 `ROOMS.title`을 결합해 반환한다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | 알림 식별자 |
| source_event_id | BIGINT | NN | 원인 Outbox 식별자를 복사한 논리 키. FK 아님 |
| recipient_user_id | BIGINT | FK → USERS.id, NN, ON DELETE NO ACTION | 알림 수신자. 사용자 삭제 시 자동 삭제하지 않음 |
| room_id | BIGINT | FK → ROOMS.id, NN, ON DELETE NO ACTION | 관련 방. 알림만으로 방 조회 권한을 부여하지 않음 |
| type | VARCHAR(30) | NN | `PARTICIPANT_JOINED`, `PARTICIPANT_CANCELED`, `WAITLIST_PROMOTED`, `ROOM_CANCELED` 중 하나 |
| read_at | TIMESTAMPTZ | NULL | 단건·일괄 읽음 SQL 내부에서 PostgreSQL `clock_timestamp()`를 한 번 평가해 고정한 최초 `operationTime`. 미확인이면 NULL이며 다시 NULL로 되돌리지 않음 |
| created_at | TIMESTAMPTZ | NN | Outbox의 `occurred_at`을 복사한 원인 업무 기준 시각. relay 저장 시각이 아님 |
| recorded_at | TIMESTAMPTZ | NN | relay 처리 트랜잭션이 PostgreSQL `clock_timestamp()`를 한 번 평가해 고정한 `operationTime`. 같은 원인 이벤트의 새 Notification이 같은 값을 사용함 |
| expires_at | TIMESTAMPTZ | NN | 읽음 여부와 관계없이 사용자 조회에서 제외하고 물리 정리할 시각. `created_at + NOTIFICATION_RETENTION` |

### 알림 관계 제약

| 테이블 | 제약 | 의미 |
|---|---|---|
| NOTIFICATION_OUTBOX_EVENTS | FK (room_id) → ROOMS(id) ON DELETE NO ACTION | 원인 방이 있는 동안 이벤트를 보존하며 방 삭제로 전달 근거를 자동 제거하지 않는다. |
| NOTIFICATION_OUTBOX_RECIPIENTS | PRIMARY KEY (outbox_event_id, recipient_user_id) | 같은 이벤트의 수신자는 한 번만 저장한다. |
| NOTIFICATION_OUTBOX_RECIPIENTS | FK (outbox_event_id) → NOTIFICATION_OUTBOX_EVENTS(id) ON DELETE CASCADE | 완료·폐기 Outbox를 정리할 때 남은 수신자 스냅샷도 함께 정리한다. |
| NOTIFICATION_OUTBOX_RECIPIENTS | FK (recipient_user_id) → USERS(id) ON DELETE NO ACTION | 사용자 삭제로 아직 처리하지 않은 수신자 스냅샷을 자동 제거하지 않는다. |
| NOTIFICATIONS | UNIQUE (source_event_id, recipient_user_id) | at-least-once relay의 같은 이벤트·수신자 알림을 한 건으로 수렴시킨다. |
| NOTIFICATIONS | FK (recipient_user_id) → USERS(id), FK (room_id) → ROOMS(id), 모두 ON DELETE NO ACTION | 사용자·방 삭제가 알림을 암묵적으로 연쇄 삭제하지 않게 한다. `source_event_id`에는 FK를 두지 않는다. |

### P1 알림 CHECK 제약

물리 마이그레이션은 아래 식과 동등한 이름 있는 PostgreSQL CHECK 제약을 둔다. `<PARAMETER_KEY>` 표기는 [운영 파라미터 정본](guides/NOTIFICATION_OPERATIONS.md#현재-운영-파라미터-정본)의 현재 값을 Flyway SQL 식에 펼치는 자리표시자다.

| 테이블 | CHECK | 의미 |
|---|---|---|
| NOTIFICATION_OUTBOX_EVENTS | `event_type IN ('PARTICIPATION_JOINED', 'PARTICIPATION_CANCELED', 'WAITLIST_PROMOTED', 'ROOM_CANCELED')` | 승인되지 않은 범용 이벤트를 저장하지 않는다. |
| NOTIFICATION_OUTBOX_EVENTS | `status IN ('PENDING', 'RETRY_WAIT', 'PROCESSED', 'FAILED', 'DISCARDED')` | 영속 `PROCESSING` 상태나 lease 상태를 추가하지 않는다. |
| NOTIFICATION_OUTBOX_EVENTS | `failure_count BETWEEN 0 AND <AUTO_PROCESS_MAX_ATTEMPTS> AND total_failure_count >= failure_count AND reprocess_count >= 0` | 현재 주기 상한과 누적 횟수의 역전을 막는다. |
| NOTIFICATION_OUTBOX_EVENTS | `status IN ('PENDING', 'RETRY_WAIT')`와 `available_at IS NOT NULL`이 서로 동치 | 두 처리 가능 상태만 relay 선점 대상이다. |
| NOTIFICATION_OUTBOX_EVENTS | `total_failure_count = 0`이면 마지막 실패 4개 컬럼이 모두 NULL이고, 양수면 모두 NN·문자열 비공백 | 실패 코드·시각·분류·정제 설명을 한 묶음으로 보존한다. |
| NOTIFICATION_OUTBOX_EVENTS | `reprocess_count = 0`이면 마지막 재처리 시각·사유가 모두 NULL이고, 양수면 모두 NN·사유 비공백 | 재처리 요약 컬럼의 부분 기록을 막는다. |
| NOTIFICATION_OUTBOX_EVENTS | `PENDING`이면 `available_at = recorded_at`이고 실패·재처리 횟수가 모두 0이며, `FAILED` 또는 `DISCARDED`이면 `total_failure_count > 0` | 최초 상태와 운영 종결 상태의 최소 근거를 고정한다. |
| NOTIFICATION_OUTBOX_EVENTS | `RETRY_WAIT`이면 `failure_count > 0 OR reprocess_count > 0` | 자동 실패나 수동 재처리 근거가 없는 재시도 대기를 금지한다. |
| NOTIFICATION_OUTBOX_EVENTS | `PROCESSED`이면 `processed_at`·`cleanup_at` NN, 폐기 컬럼 NULL, `cleanup_at = processed_at + <PROCESSED_OUTBOX_RETENTION>` | 처리 완료와 정리 시점을 함께 고정한다. |
| NOTIFICATION_OUTBOX_EVENTS | `DISCARDED`이면 `discarded_at`·비공백 사유·`cleanup_at` NN, `processed_at` NULL, `cleanup_at = discarded_at + <DISCARDED_OUTBOX_RETENTION>` | 운영 폐기와 최소 기록 보존을 함께 고정한다. |
| NOTIFICATION_OUTBOX_EVENTS | `PENDING`, `RETRY_WAIT`, `FAILED`이면 `processed_at`, 폐기 컬럼, `cleanup_at`이 모두 NULL | 완료·폐기 전 정리 시각 생성을 금지한다. |
| NOTIFICATIONS | `type IN ('PARTICIPANT_JOINED', 'PARTICIPANT_CANCELED', 'WAITLIST_PROMOTED', 'ROOM_CANCELED')` | API의 NotificationType과 저장값을 일치시킨다. |
| NOTIFICATIONS | `read_at IS NULL OR read_at >= recorded_at` | 같은 PostgreSQL 시계에서 Notification 기록보다 앞선 읽음 시각을 금지한다. |
| NOTIFICATIONS | `expires_at = created_at + <NOTIFICATION_RETENTION>` | 읽음 여부와 무관한 보존 기간을 적용한다. |

Outbox의 `occurred_at`과 Notification의 `created_at`은 애플리케이션 `Clock`이 만든 업무 시각이고, Outbox·Notification의 `recorded_at`과 Notification의 `read_at`은 PostgreSQL 작업 시각이다. 두 시계 도메인 사이에는 상대 순서를 보장하지 않으므로 `recorded_at >= occurred_at`이나 `recorded_at >= created_at` CHECK를 두지 않는다. 문자열 CHECK는 값이 있을 때 `btrim(value) <> ''`도 검사한다.

### P1 알림 인덱스

| 인덱스 | 정의 | 대표 쿼리 |
|---|---|---|
| `idx_notification_outbox_events_relay` | `(available_at, id) WHERE status IN ('PENDING', 'RETRY_WAIT')` | 처리 가능한 두 상태를 합쳐 가장 이른 이벤트부터 `FOR UPDATE SKIP LOCKED`로 선점 |
| `idx_notification_outbox_events_failed` | `(id) WHERE status = 'FAILED'` | `OPS_MAX_EVENT_IDS` 이하 실패 이벤트의 운영 명령 조회 |
| `idx_notification_outbox_events_cleanup` | `(cleanup_at, id) WHERE cleanup_at IS NOT NULL` | `PROCESSED`, `DISCARDED` 최소 기록의 기한 기반 정리 |
| `idx_notifications_recipient_created` | `(recipient_user_id, created_at DESC, id DESC)` | 사용자별 목록의 고정 정렬과 페이지 조회 |
| `idx_notifications_recipient_unread` | `(recipient_user_id, id) WHERE read_at IS NULL` | 사용자별 미확인 개수와 경계 이내 일괄 읽음 |
| `idx_notifications_expiry` | `(expires_at, id)` | `NOTIFICATION_RETENTION`이 지난 Notification의 제한된 묶음 정리 |

`UNIQUE (source_event_id, recipient_user_id)`와 수신자 복합 기본 키가 만드는 인덱스는 별도로 중복 생성하지 않는다. relay 인덱스는 `status`를 선두 키로 두지 않고 partial predicate로 고정해 두 처리 가능 상태 전체에서 `(available_at, id)` 순서를 바로 사용한다.

### P1 알림 처리·보존 규칙

이 절은 상태 전이와 저장 효과만 설명한다. 파라미터 키의 현재 값은 [알림 운영 파라미터 정본](guides/NOTIFICATION_OPERATIONS.md#현재-운영-파라미터-정본)에서만 확인한다.

- 최종 성공한 원인 업무는 이벤트와 한 명 이상의 수신자 스냅샷을 같은 트랜잭션으로 저장한다. 업무 변경이나 Outbox 기록이 롤백되면 둘 다 남지 않는다.
- 원인 Command Coordinator가 최초 시도 전에 고정한 `requestTime`을 이벤트의 `occurred_at`으로 전달한다. 낙관 락 재시도마다 새 시각을 만들지 않으며, relay는 이 값을 Notification의 `created_at`으로 복사한다.
- 원인 업무의 최종 성공 트랜잭션은 PostgreSQL `clock_timestamp()`를 한 번 평가한 `operationTime`을 Outbox의 `recorded_at`과 최초 `available_at`에 함께 사용한다.
- relay는 `available_at <= operationTime`인 가장 이른 이벤트를 `FOR UPDATE SKIP LOCKED`로 선점한다. 수신자별 Notification을 멱등 저장하고 이벤트를 `PROCESSED`로 바꾸는 작업은 한 트랜잭션에서 함께 커밋하거나 함께 롤백한다.
- 자동 처리 상한 전 실패는 `AUTO_RETRY_DELAYS`에 따라 `RETRY_WAIT`과 새 `available_at`을 기록한다. `AUTO_PROCESS_MAX_ATTEMPTS`에 도달하거나 결정적 오류이면 `FAILED`와 `available_at = NULL`로 격리한다.
- 수동 재처리는 `FAILED`이면서 PostgreSQL `operationTime`이 `occurred_at + FAILED_REPROCESS_WINDOW`보다 앞선 이벤트만 허용한다. `failure_count`를 0으로 초기화하고 `reprocess_count`와 마지막 재처리 시각·사유를 갱신해 `RETRY_WAIT`로 전환하되, `total_failure_count`와 마지막 실패 정보는 보존한다.
- 재처리 가능 기간이 지난 `FAILED` 이벤트는 자동 삭제하거나 뒤늦은 Notification을 만들지 않고 `DISCARDED`로만 종결한다. 폐기 트랜잭션은 수신자 스냅샷을 즉시 삭제하고 폐기 사유·시각과 `cleanup_at`을 기록한다.
- `PROCESSED` 이벤트와 남은 수신자 행은 `PROCESSED_OUTBOX_RETENTION`, `DISCARDED` 최소 이벤트 행은 `DISCARDED_OUTBOX_RETENTION` 동안 보존한 뒤 삭제한다. `PENDING`, `RETRY_WAIT`, `FAILED`는 자동 삭제하지 않는다.
- Notification은 `created_at + NOTIFICATION_RETENTION` 전까지만 사용자에게 보인다. 목록·페이지 count와 미확인 개수는 각 읽기 트랜잭션의 PostgreSQL `transaction_timestamp()`, 단건·일괄 읽음은 SQL이 고정한 PostgreSQL `operationTime`에 `expires_at`이 뒤인 행만 대상으로 한다.
- 만료 Notification은 물리 정리 전에도 목록·미확인 개수·읽음 처리에서 존재하지 않는 알림처럼 취급한다. 정리 작업은 `expires_at` 인덱스 순서의 제한된 묶음으로 삭제하며 읽음 여부는 보존 기간을 바꾸지 않는다.
- 사용자·방 삭제 기능은 P1 알림 범위에 없으므로 관련 FK는 `ON DELETE NO ACTION`으로 둔다. 향후 계정 삭제나 방 물리 삭제를 도입할 때 알림 익명화·삭제 순서를 별도로 결정한다.
- 별도 복구 이력 테이블은 두지 않는다. 현재·누적 실패 횟수, 재처리 횟수와 마지막 실패·재처리·폐기 근거만 Outbox에 보존하며 강한 감사 이력이 필요해지면 후속 저장 계약으로 확장한다.

## P2 MATCH 저장 계약 (계획·미구현)

> 이 절은 `MATCH-01`의 승인된 목표 저장 계약이며 아직 Flyway·JPA 엔티티·생산 테이블이 없다. 현재 제공·검증·배포·실측 여부는 [P2 기능 상태](p2/README.md#기능별-현재-상태)에서만 판정한다. 후보 선점·멱등성은 [ADR-0061](adr/matching/0061-postgresql-candidate-reservation-idempotency.md), MATCH 채팅 handoff·복구·보존은 [ADR-0062](adr/matching/0062-match-chat-handoff-recovery-retention.md), URL 텍스트 표현은 [ADR-0064](adr/matching/0064-match-chat-url-text-storage.md), 기준 부하와 Redis 재검토 gate는 [ADR-0063](adr/matching/0063-match-baseline-measurement-gate.md)를 따른다.

이 절은 저장 이름·타입·제약·인덱스와 저장 효과만 소유한다. 제품 흐름과 HTTP 필드는 [MATCH-01 명세](p2/matching.md#match-01-실시간-파티-매칭), API 필드·오류는 [API](API.md)가 소유하며 여기서 반복하지 않는다.

### 소유 경계와 관계도

`matching`은 요청·제안·응답·성공 파티·참가자 접근, 멱등성·신고·차단을 소유한다. `chat`은 `MATCH_CHAT_ROOMS`와 URL 텍스트를 포함한 메시지·실시간 전달만 소유한다. P1 `CHAT_ROOMS.room_id`는 계속 `ROOMS.id`만 참조하며 P1 ROOM 접근 계산과 30일 메시지 보존에만 사용한다. MATCH는 이를 넓히거나 재사용하지 않고 `MATCH_CHAT_ROOMS.party_id`의 별도 관계를 사용한다.

~~~mermaid
erDiagram
    USERS ||--o{ MATCH_REQUESTS : "요청"
    GAMES ||--o{ MATCH_REQUESTS : "선택 게임"
    MATCH_PROPOSALS ||--|{ MATCH_PROPOSAL_MEMBERS : "고정 후보"
    MATCH_REQUESTS ||--o{ MATCH_PROPOSAL_MEMBERS : "제안 이력"
    MATCH_PROPOSALS ||--o| MATCH_PARTIES : "전원 수락 성공"
    MATCH_PARTIES ||--|{ MATCH_PARTY_PARTICIPANTS : "접근 관계"
    USERS ||--o{ MATCH_PARTY_PARTICIPANTS : "성공 파티 참가"
    MATCH_PARTIES ||--o| MATCH_CHAT_ROOMS : "MATCH 전용 채팅"
    MATCH_CHAT_ROOMS ||--o{ MATCH_CHAT_MESSAGES : "메시지"
    USERS ||--o{ MATCH_CHAT_MESSAGES : "작성"
    USERS ||--o{ MATCH_IDEMPOTENCY_RECORDS : "멱등성 기록"
    USERS ||--o{ MATCH_BLOCKS : "차단 시작"
    USERS ||--o{ MATCH_BLOCKS : "차단 대상"
    USERS ||--o{ MATCH_REPORTS : "신고자"
    USERS ||--o{ MATCH_REPORTS : "피신고자"
~~~

### P2 MATCH 논리 enum

| 이름 | 저장값 |
|---|---|
| `match_request_status` | `WAITING`, `PROPOSED`, `PAUSED`, `MATCHED`, `CANCELED` |
| `match_proposal_status` | `OPEN`, `CONFIRMED`, `DECLINED`, `EXPIRED`, `CANCELED` |
| `match_proposal_response_status` | `PENDING`, `ACCEPTED`, `REQUEUED`, `CANCELED`, `EXPIRED` |
| `match_party_status` | `PREPARING`, `ACTIVE`, `CLOSED` |
| `match_chat_message_type` | `USER`, `SYSTEM` |
| `match_chat_system_event_key` | `CHAT_OPENED`, `CLOSES_IN_ONE_HOUR` |
| `match_idempotency_operation` | `MATCH_REQUEST_CREATE`, `MATCH_PROPOSAL_RESPONSE` |
| `match_report_reason` | `ABUSE_OR_HARASSMENT`, `HATE_OR_DISCRIMINATION`, `SEXUAL_CONTENT`, `SPAM_OR_SCAM`, `OTHER_RULE_VIOLATION` |

물리 구현은 기존 P1과 같이 PostgreSQL native enum 대신 `VARCHAR`와 이름 있는 `CHECK` 제약을 사용한다. `PURGED`는 행을 남기는 저장 상태가 아니라 물리 삭제 완료 결과이므로 `MATCH_PARTIES`와 `MATCH_CHAT_ROOMS`에 저장하지 않는다.

### MATCH_REQUESTS

물리 테이블명은 계획상 `match_requests`다. Board Game Arena가 P2 MVP에서 유일한 플랫폼이므로 중복 플랫폼 컬럼은 두지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, NN, AI | `matchRequestId` |
| user_id | BIGINT | FK → USERS.id, NN | 요청 사용자 |
| game_id | BIGINT | FK → GAMES.id, NN | 고정 게임 |
| min_party_size | SMALLINT | NN | 허용 파티 인원 하한 |
| max_party_size | SMALLINT | NN | 허용 파티 인원 상한 |
| status | VARCHAR(20) | NN | `match_request_status` |
| queued_at | TIMESTAMPTZ | NN | 현재 대기 시도 시작 시각 |
| priority_since | TIMESTAMPTZ | NN | 현재 대기 시도의 FIFO 기준 시각 |
| proposed_at | TIMESTAMPTZ | NULL | 현재 제안으로 전환한 시각 |
| matched_at | TIMESTAMPTZ | NULL | 성공 파티 확정 시각 |
| purge_after | TIMESTAMPTZ | NULL | 종료 원자료의 물리 삭제 기준 |
| created_at, updated_at | TIMESTAMPTZ | NN | 생성·마지막 상태 변경 시각 |

### MATCH_PROPOSALS와 MATCH_PROPOSAL_MEMBERS

`MATCH_PROPOSALS`는 고정된 후보 파티와 응답 기한을, `MATCH_PROPOSAL_MEMBERS`는 그 제안에 포함된 요청·사용자와 최초 유효 응답을 저장한다.

| 테이블 | 주요 컬럼 | 타입·제약 |
|---|---|---|
| MATCH_PROPOSALS | id, game_id, party_size, status, respond_by, confirmed_at, purge_after, created_at, updated_at | `id BIGINT PK`; `game_id BIGINT FK → GAMES.id`; 상태·기한은 NN, 확정·삭제 기준은 NULL 가능 |
| MATCH_PROPOSAL_MEMBERS | proposal_id, match_request_id, user_id, response_status, responded_at, created_at, updated_at | `(proposal_id, match_request_id) BIGINT PK`; `proposal_id BIGINT FK → MATCH_PROPOSALS.id ON DELETE CASCADE`, `(match_request_id, user_id) FK → MATCH_REQUESTS(id, user_id) ON DELETE CASCADE`, `user_id`는 USERS FK, 응답 시각은 NULL 가능 |

### MATCH_PARTIES와 MATCH_PARTY_PARTICIPANTS

`MATCH_PARTIES`는 전원 수락 뒤 한 번만 생기는 성공 파티다. `MATCH_PARTY_PARTICIPANTS`는 성공 파티의 사용자별 채팅 접근 근거이며, 일반 사용자 접근은 이 행만으로 부여되지 않고 Party 상태와 함께 판정한다.

| 테이블 | 주요 컬럼 | 타입·제약 |
|---|---|---|
| MATCH_PARTIES | id, proposal_id, game_id, status, preparing_started_at, chat_opened_at, closes_at, closed_at, purge_after, created_at, updated_at | `id BIGINT PK`; `proposal_id BIGINT NULL FK → MATCH_PROPOSALS.id ON DELETE SET NULL`; 게임·상태·준비 시작 시각은 NN, 이후 lifecycle 시각은 NULL 가능 |
| MATCH_PARTY_PARTICIPANTS | party_id, user_id, participant_ref, left_at, created_at | `(party_id, user_id) BIGINT PK`; `party_id BIGINT FK → MATCH_PARTIES.id ON DELETE CASCADE`, `user_id BIGINT FK → USERS.id`, `participant_ref UUID NN`, `UNIQUE (party_id, participant_ref)`, `left_at TIMESTAMPTZ NULL`. `participant_ref`는 해당 Party 안에서만 의미를 갖는 외부용 불투명 식별자이며, `left_at IS NULL`인 행만 현재 채팅 접근 관계다. 명시적 나가기 뒤에도 7일 보존을 위해 행은 남긴다. |

### MATCH 전용 채팅 저장

이 두 테이블은 `chat` 소유의 계획 저장 구조다. `MATCH_CHAT_ROOMS`는 `MATCH_PARTIES`와 0 또는 1 관계이고 `CHAT_ROOMS`와 FK·공유 행·공유 접근 규칙을 갖지 않는다.

| 테이블 | 주요 컬럼 | 타입·제약 |
|---|---|---|
| MATCH_CHAT_ROOMS | id, party_id, created_at, updated_at | `id BIGINT PK`; `party_id BIGINT FK → MATCH_PARTIES.id ON DELETE RESTRICT`, NN. matching이 파티를 연쇄 삭제하지 않도록 chat 정리 후에만 파티를 삭제한다. |
| MATCH_CHAT_MESSAGES | id, match_chat_room_id, sender_user_id, message_type, client_message_id, system_event_key, content, created_at | `id BIGINT PK`; `match_chat_room_id BIGINT FK → MATCH_CHAT_ROOMS.id ON DELETE CASCADE`, 유형·본문·시각은 NN. USER는 `sender_user_id`·`client_message_id`를, SYSTEM은 내부 `system_event_key`를 사용해 각각 재시도 중복을 막는다. |

### MATCH_IDEMPOTENCY_RECORDS, MATCH_BLOCKS와 MATCH_REPORTS

| 테이블 | 주요 컬럼 | 타입·제약 |
|---|---|---|
| MATCH_IDEMPOTENCY_RECORDS | id, user_id, idempotency_key, operation, payload_fingerprint, result_entity_type, result_entity_id, result_state, created_at, expires_at | `id BIGINT PK`; 사용자 FK, key·operation·fingerprint·만료 시각 NN. result 참조는 24시간 기록 보존을 위해 FK가 아닌 논리 메타데이터다. |
| MATCH_BLOCKS | id, blocker_user_id, blocked_user_id, created_at | `id BIGINT PK`; blocker·blocked는 USERS FK이며 `(blocker_user_id, blocked_user_id)`는 UNIQUE |
| MATCH_REPORTS | id, reporter_user_id, reported_user_id, reason, reported_at, purge_after | `id BIGINT PK`; 신고자·피신고자 USERS FK, `reason VARCHAR(30) NN`, 접수·삭제 기준 시각 NN |

### P2 MATCH 제약과 인덱스

| 대상 | 제약 또는 인덱스 | 의미 |
|---|---|---|
| MATCH_REQUESTS | `ck_match_requests_party_size`: `min_party_size > 0 AND min_party_size <= max_party_size` | 유효하지 않은 인원 범위를 저장하지 않는다. |
| MATCH_REQUESTS | `ck_match_requests_status`: `status IN ('WAITING', 'PROPOSED', 'PAUSED', 'MATCHED', 'CANCELED')` | 승인된 요청 상태만 저장한다. |
| MATCH_REQUESTS | `uq_match_requests_active_user`: `UNIQUE (user_id) WHERE status IN ('WAITING', 'PROPOSED', 'PAUSED')` | 한 사용자의 현재 비종료 요청을 하나로 제한한다. 이 요청 테이블 내부 제약만으로는 성공 파티 참가자 접근 관계와의 cross-table 불변식을 보장하지 않는다. |
| MATCH_REQUESTS | `uq_match_requests_id_user`: `UNIQUE (id, user_id)` | Proposal Member의 `match_request_id`와 `user_id`가 같은 요청 소유자를 가리키도록 복합 FK의 참조 대상을 제공한다. |
| MATCH_REQUESTS | `idx_match_requests_waiting_candidate`: `(game_id, priority_since ASC, id ASC) WHERE status = 'WAITING'` | 같은 게임 후보의 결정적 선점 순서 `prioritySince ASC, matchRequestId ASC`를 지원한다. |
| MATCH_REQUESTS, MATCH_PROPOSALS, MATCH_PARTIES | 각 `purge_after`의 `(purge_after, id) WHERE purge_after IS NOT NULL` 인덱스 | 종료 원자료를 제한된 묶음으로 물리 삭제한다. |
| MATCH_PROPOSALS | `ck_match_proposals_status`: `status IN ('OPEN', 'CONFIRMED', 'DECLINED', 'EXPIRED', 'CANCELED')` | 제안의 저장 상태를 고정한다. |
| MATCH_PROPOSAL_MEMBERS | `UNIQUE (proposal_id, user_id)`, `ck_match_proposal_members_response_status`, `proposal_id FK ON DELETE CASCADE`, `(match_request_id, user_id) FK → MATCH_REQUESTS(id, user_id) ON DELETE CASCADE` | 한 제안에 같은 사용자를 중복으로 넣거나 요청 소유자와 다른 사용자를 Proposal Member로 저장하지 않는다. 제안 또는 요청의 종료 원자료를 각 `purge_after`에 물리 삭제할 때 남은 응답 관계가 삭제를 막지 않는다. |
| MATCH_PARTIES | `UNIQUE (proposal_id) WHERE proposal_id IS NOT NULL`, `proposal_id FK ON DELETE SET NULL`, `ck_match_parties_status`, `idx_match_parties_preparing_due (preparing_started_at, id) WHERE status = 'PREPARING'` | 하나의 제안이 연결돼 있는 동안 둘 이상의 성공 파티를 만들지 않으며 `PREPARING`·`ACTIVE`·`CLOSED`만 저장한다. 제안이 먼저 purge되어도 늦게 `CLOSED`된 파티의 7일 보존은 막지 않는다. recovery scan은 준비 기한이 지난 대상만 제한적으로 찾는다. |
| MATCH_PARTY_PARTICIPANTS | `PRIMARY KEY (party_id, user_id)`, `UNIQUE (party_id, participant_ref)`, `idx_match_party_participants_current (party_id, user_id) WHERE left_at IS NULL` | 성공 파티의 같은 사용자 접근 관계와 Party-scoped participant reference는 한 번만 저장한다. `left_at IS NULL`만 현재 접근·마지막 사용자 판정에 포함하고, 나간 관계는 Party purge까지 보존한다. |
| MATCH_CHAT_ROOMS | `UNIQUE (party_id)`, `party_id FK ON DELETE RESTRICT` | 복구·재시도로 MATCH 채팅방을 중복 생성하지 않는다. matching이 파티를 연쇄 삭제하지 않고 chat 정리 경계가 방을 먼저 삭제하게 한다. |
| MATCH_CHAT_MESSAGES | `ck_match_chat_messages_kind`: USER는 `sender_user_id`·`client_message_id` NN 및 `system_event_key` NULL, SYSTEM은 sender·client ID NULL 및 `system_event_key IN ('CHAT_OPENED', 'CLOSES_IN_ONE_HOUR')`; `uq_match_chat_messages_user_client (match_chat_room_id, sender_user_id, client_message_id) WHERE client_message_id IS NOT NULL`; `uq_match_chat_messages_system_event (match_chat_room_id, system_event_key) WHERE system_event_key IS NOT NULL` | USER와 SYSTEM의 멱등성 근거를 섞지 않는다. 같은 사용자의 같은 USER 재전송과 같은 채팅방의 같은 SYSTEM lifecycle 알림은 각각 한 행으로 수렴한다. |
| MATCH_BLOCKS | `CHECK (blocker_user_id <> blocked_user_id)`, `UNIQUE (blocker_user_id, blocked_user_id)`, `idx_match_blocks_blocked_blocker (blocked_user_id, blocker_user_id)` | 자기 차단을 막고 차단 리소스 ID로 반복·해제할 수 있으며 양방향 차단 후보 제외 조회를 지원한다. |
| MATCH_REPORTS | `ck_match_reports_reason`: `reason IN ('ABUSE_OR_HARASSMENT', 'HATE_OR_DISCRIMINATION', 'SEXUAL_CONTENT', 'SPAM_OR_SCAM', 'OTHER_RULE_VIOLATION')`, `UNIQUE (reporter_user_id, reported_user_id)`, `CHECK (reporter_user_id <> reported_user_id)`, `(purge_after, id)` | 고정 신고 사유 다섯 값만 저장하며, 7일 보관 중 같은 신고자·피신고자 쌍을 한 건으로 제한한다. 만료 판정·행 교체는 이 물리 UNIQUE를 우회하지 않고 명령 트랜잭션에서 수행한다. |
| MATCH_IDEMPOTENCY_RECORDS | `UNIQUE (user_id, idempotency_key)`, `ck_match_idempotency_operation`, `idx_match_idempotency_records_expiry (expires_at, id)` | 사용자 단위 키의 물리 중복을 막는다. 24시간이 지난 행은 배치 purge를 기다리지 않고 명령 트랜잭션에서 만료 판정 후 교체한다. |

`MATCH_IDEMPOTENCY_RECORDS.operation`은 `MATCH_REQUEST_CREATE`, `MATCH_PROPOSAL_RESPONSE`만 허용한다. `payload_fingerprint`은 method·path·body action을 포함한 canonical 의미의 fingerprint이며, 같은 사용자·키의 다른 operation 또는 payload는 충돌이다.

### P2 MATCH 저장 경계

이 절은 MATCH 테이블의 저장 구조·제약·인덱스와 물리 삭제 효과만 소유한다. 후보 선점, Proposal terminal 경합, 사용자·Party 잠금, 채팅 handoff·복구, 현재 상태 조회와 만료 행의 명령 처리 순서는 [아키텍처](ARCHITECTURE.md#p2-match-제안채팅-복구-흐름-계획미구현)가 유일한 정본이다.

- `MATCH_PROPOSAL_MEMBERS`의 복합 FK와 `MATCH_PARTY_PARTICIPANTS.participant_ref`는 각각 요청 소유자와 Party-scoped 외부 식별자의 저장 불변식을 DB에서 보장한다.
- `MATCH_REPORTS`와 `MATCH_IDEMPOTENCY_RECORDS`의 시간 없는 UNIQUE 제약은 보관 기간 자체를 판정하지 않는다. 만료된 행을 새 명령이 재사용할 수 있는 규칙은 아키텍처의 operation-time 계약을 따르며, 지연 batch purge에 의존하지 않는다.
- `purge_after` 인덱스는 종료 원자료의 제한된 묶음 삭제를 지원한다. chat message·room, Party/access의 삭제 순서와 PREPARING 실패 시 즉시 삭제 여부는 아키텍처의 Cleanup Executor가 소유한다.

## 필수 제약과 계산 규칙

### DB 제약

| 테이블 | 제약 | 의미 |
|---|---|---|
| USERS | UNIQUE (email) | 값이 있는 정규화 이메일은 중복될 수 없다. PostgreSQL은 여러 `NULL`을 허용한다. |
| USERS | CHECK (password_hash IS NULL OR email IS NOT NULL) | 비밀번호 자격증명은 로그인 이메일 없이 존재할 수 없다. |
| SOCIAL_ACCOUNTS | CHECK (provider IN ('GOOGLE', 'NAVER', 'KAKAO')) | 승인된 세 제공자만 저장한다. |
| SOCIAL_ACCOUNTS | UNIQUE (provider, provider_subject) | 한 외부 계정은 한 알밤메이트 사용자에게만 연결된다. |
| SOCIAL_ACCOUNTS | UNIQUE (user_id, provider) | 한 사용자는 제공자마다 외부 계정 하나만 연결한다. |
| GAMES | UNIQUE (bgg_id) | 하나의 BGG 게임은 게임 목록에 한 번만 저장한다. |
| USER_PLAYED_GAMES | UNIQUE (user_id, game_id) | 한 사용자는 한 게임을 최대 한 번만 표시한다. |
| GAME_MECHANISMS | UNIQUE (bgg_mechanism_id) | 하나의 BGG 메커니즘은 내부 목록에 한 번만 매핑한다. |
| GAME_MECHANISMS | UNIQUE (code) | 공개 검색 코드는 중복될 수 없다. |
| GAME_MECHANISMS | UNIQUE (featured_order) | 값이 있는 대표 순서는 한 항목에만 배정한다. 여러 `NULL`은 허용한다. |
| GAME_MECHANISMS | CHECK (featured_order IS NULL OR featured_order BETWEEN 1 AND 8) | 대표 순서는 `1`~`8`만 허용한다. |
| GAME_MECHANISMS | CHECK (NOT is_public OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)) | 공개 항목은 검수자와 검수 시각을 반드시 가진다. |
| GAME_MECHANISM_RELATIONS | PRIMARY KEY (game_id, mechanism_id) | 같은 게임과 메커니즘 관계는 하나만 저장한다. |
| GAME_CATEGORIES | UNIQUE (code), UNIQUE (bgg_subdomain), UNIQUE (display_order) | 고정 카테고리 code·원본 subdomain·표시 순서는 각각 하나다. |
| GAME_CATEGORIES | CHECK (display_order BETWEEN 1 AND 8) | 고정 카테고리의 화면 순서는 1~8이다. |
| GAME_CATEGORY_RELATIONS | PRIMARY KEY (game_id, category_id) | 같은 게임과 카테고리 관계는 하나만 저장한다. |
| GAME_THEMES | UNIQUE (bgg_theme_id), UNIQUE (code) | 하나의 BGG 테마와 공개 검색 code는 각각 하나의 내부 목록에만 매핑한다. |
| GAME_THEME_RELATIONS | PRIMARY KEY (game_id, theme_id) | 같은 게임과 테마 관계는 하나만 저장한다. |
| GAME_PLAYER_PREFERENCES | PRIMARY KEY (game_id, player_count), CHECK (player_count > 0) | 게임별 실제 인원은 한 번만 저장하고 양의 정수다. |
| GAME_PLAYER_PREFERENCES | CHECK (NOT is_best OR is_recommended) | 베스트 인원은 반드시 추천 인원이다. |
| ROOMS | CHECK (room_type <> 'GAME_FOCUSED' OR game_id IS NOT NULL) | 게임 중심 방은 게임을 반드시 선택한다. |
| ROOMS | CHECK (capacity BETWEEN 1 AND 10) | 개설자를 제외한 모집 정원은 1명 이상 10명 이하다. |
| ROOMS | CHECK (active_participant_count BETWEEN 0 AND capacity) | 현재 점유 인원은 음수이거나 모집 정원을 초과할 수 없다. |
| PARTICIPATIONS | UNIQUE (room_id, user_id) | 한 사용자와 한 방의 참가 관계는 한 행만 가진다. |
| PARTICIPATIONS | CHECK ((status = 'ACTIVE' AND canceled_at IS NULL) OR (status = 'CANCELED' AND canceled_at IS NOT NULL)) | 참가 상태와 취소 시각이 일치해야 한다. |
| CHAT_ROOMS | UNIQUE (room_id) | ROOM당 채팅방은 최대 하나만 둔다. |
| CHAT_ROOMS | CHECK (messages_purged_at IS NULL OR purge_after IS NOT NULL) | 삭제 완료 시각은 삭제 기준 시각이 설정된 채팅방에만 기록한다. |
| CHAT_MESSAGES | UNIQUE (chat_room_id, sender_user_id, client_message_id) | 같은 사용자의 같은 방·멱등성 키는 하나의 메시지만 저장한다. |
| ROOM_STATUS_CORRECTION_PROGRESS | CHECK (job_name = 'room-status-correction') | ROOM Scheduler 진행 상태는 고정된 단일 작업만 저장한다. |
| ROOM_STATUS_CORRECTION_PROGRESS | CHECK ((cursor_due_at IS NULL AND cursor_room_id IS NULL) OR (cursor_due_at IS NOT NULL AND cursor_room_id IS NOT NULL)) | cursor의 두 구성 요소는 함께 존재하거나 함께 비어 있다. |
| ROOM_STATUS_CORRECTION_PROGRESS | CHECK (cursor_due_at IS NULL OR (turn_cutoff IS NOT NULL AND cursor_due_at <= turn_cutoff)) | 저장된 cursor는 현재 순회 경계를 넘지 않는다. |
| ROOM_STATUS_CORRECTION_PROGRESS | CHECK ((cursor_room_id IS NULL OR cursor_room_id > 0) AND progress_version >= 0 AND execution_generation >= 0) | cursor ID와 진행 카운터는 유효한 음이 아닌 범위를 유지한다. |
| SHEDLOCK | PRIMARY KEY (name) | 같은 이름의 스케줄 잠금은 하나의 임대 상태만 가진다. |

- 사람 중심 방의 `game_id`는 NULL일 수 있으며, 게임을 선택한 사람 중심 방도 허용한다.
- `GAME_MECHANISMS`의 공개 상태는 애플리케이션 enum이 아니다. 선택지와 검색은 `is_public = true`인 항목만 사용하고, 새 항목은 검수 전 공개하지 않는다.
- `GAME_MECHANISM_RELATIONS(mechanism_id, game_id)` 인덱스로 공개 코드의 관계 존재 여부를 조회하며 관계 조인으로 게임 행을 중복시키지 않는다.
- `GAME_CATEGORY_RELATIONS(category_id)`와 `GAME_THEME_RELATIONS(theme_id)` 단일 컬럼 인덱스는 category/theme FK의 삭제 비용과 역방향 조회를 지원한다. 각 복합 기본 키는 `game_id`가 선두이며, 목록 필터는 관계별 `EXISTS`로 판정해 게임 행을 중복시키지 않는다.
- GAME_PLAYER_PREFERENCES는 복합 기본 키의 game_id 선두 인덱스로 상세 인원 배열을 조회한다. player_count 선두의 검색 인덱스와 다른 역방향 인덱스는 170,000건 실행 계획에서 병목이 재현될 때만 추가한다.
- `SOCIAL_ACCOUNTS.user_id`는 `ON DELETE NO ACTION`이다. 계정 삭제는 AUTH-05 범위에 없으며 도입 시 외부 연결 처리 순서를 별도로 결정한다.
- `USER_PLAYED_GAMES.user_id`와 `game_id`는 모두 `ON DELETE NO ACTION`이다. 사용자·게임 삭제로 관계를 암묵적으로 연쇄 삭제하지 않으며, 삭제 기능을 도입할 때 정리 순서를 별도로 결정한다.
- 재참가 시 기존 `PARTICIPATIONS` 행을 재활성화한다. `CANCELED`를 `ACTIVE`로 바꾸고 `joined_at`을 갱신하며 `canceled_at`은 NULL로 되돌린다.
- `capacity`는 개설자를 제외한 1명 이상 10명 이하의 모집 인원이다. 전체 참여 가능 인원은 2명 이상 11명 이하다.

### 게임명 부분일치 검색 인덱스

- PostgreSQL은 `pg_trgm` extension과 `ix_games_name_lower_trgm` GIN 인덱스(`lower(name) gin_trgm_ops`)로 기존 `lower(name) LIKE '%keyword%'` 게임명 부분일치 조회를 지원한다. 검색 의미·HTTP 계약·최소 검색어 길이는 바꾸지 않는다.
- 3글자 이상 검색어는 planner가 이 인덱스 경로를 선택할 수 있다. 1·2글자는 trgm 선택도가 낮을 수 있으므로 기존 planner 경로를 허용한다.

### 해 본 게임 관계 인덱스

- `UNIQUE (user_id, game_id)`가 만드는 유일 인덱스로 본인 관계 확인과 `PLAYED_ONLY`·`EXCLUDE_PLAYED`의 사용자 선두 `EXISTS`·`NOT EXISTS` 조회를 지원한다. 같은 선두 열의 중복 인덱스는 만들지 않는다.
- `game_id` 선두 인덱스나 다른 복합 인덱스는 미리 확정하지 않는다. [FND-09](archive/p1/foundation.md#fnd-09-검색-성능과-인덱스-검증)에서 관계 수·선택도·실행 계획을 측정한 뒤 채택 여부를 정하고, 채택하면 이 문서와 전진 마이그레이션을 함께 갱신한다.

### 채팅·스케줄 제약과 인덱스

- `CHAT_ROOMS` 생성 마이그레이션은 스키마·제약만 만들고 기존 `ROOMS`를 backfill하지 않는다. 기존 ROOM backfill·ROOM 생성·상태 전환 경합·최종 보정과 배포 절체는 [#281](https://github.com/bamsongi-club/albam-mate/issues/281)의 후속 범위다. [ADR-0045](adr/chat/0045-chat-room-schema-and-backfill-boundary.md)은 production 스키마 기동과 local profile callback 초기화의 경계를 승인한다. 기존 최종 상태 방은 빈 보관 완료 시각을 기록하고 메시지 이력을 만들지 않는다.
- 방 생성과 `CHAT_ROOMS` 생성은 하나의 트랜잭션에서 성공하거나 함께 롤백한다.
- `CHAT_MESSAGES(chat_room_id, id DESC)` 인덱스로 최신 이력과 `beforeMessageId` 커서 조회를 지원한다.
- `CHAT_ROOMS(purge_after)` 조건부 인덱스로 삭제 기준 시각이 지났고 아직 `messages_purged_at`이 없는 채팅방을 선별한다.
- `CHAT_MESSAGES(chat_room_id, sender_user_id, client_message_id)` 유일 제약으로 재시도 중복 저장을 막는다. 같은 키로 다른 본문을 보내면 저장하지 않고 `VALIDATION_ERROR`를 반환한다.
- 같은 채팅방의 메시지는 `CHAT_ROOMS` 행을 잠근 뒤 ID를 할당해 방별 ID 순서와 커밋 가시성 순서를 일치시킨다.
- 방이 `CANCELED`·`FINISHED`로 전환되면 `purge_after`를 전환 시각에서 30일 뒤로 설정한다. 만료 뒤 일일 스케줄러가 메시지를 물리 삭제하고 `messages_purged_at`을 기록한다([ADR-0049](adr/chat/0049-chat-message-retention-lock-section-boundary.md)).
- ShedLock은 [ADR-0038](adr/platform/0038-multi-instance-session-and-scheduler-coordination.md)에 따라 PostgreSQL 시각으로 `lock_until`을 비교한다. 잠금 획득·해제 트랜잭션은 ROOM별 상태 전환과 채팅 삭제 묶음의 업무 트랜잭션에 결합하지 않는다.
- `SHEDLOCK`은 중복 스케줄 실행을 줄이는 조정 테이블이며 `Room.version` 낙관 락, 참가·대기 불변식과 채팅 메시지 정본을 대체하지 않는다.
- `ROOM_STATUS_CORRECTION_PROGRESS`는 행 하나를 기본 키로 직접 조회하므로 별도 인덱스를 만들지 않는다. `cursor_room_id`는 삭제될 수 있는 ROOM과의 관계가 아니라 마지막 선별 위치이므로 FK를 두지 않는다.
- ROOM Scheduler는 실행 시작 때 진행 행을 잠가 generation을 점유하고, 이후 cursor 전진·회전은 기대 generation과 version이 모두 맞는 조건부 갱신으로만 확정한다. ROOM 처리 커밋 뒤 cursor 갱신 전 장애에서는 같은 ROOM을 다시 선별할 수 있지만 cursor를 먼저 전진해 미처리 ROOM을 건너뛰지는 않는다.

### 서비스 규칙

현행 제품 규칙의 소유 경계는 [P1 명세의 P0 계약 상속](archive/p1/P1-spec.md#p0-계약-상속)과 관련 [P1 기능 문서](archive/p1/README.md)를 따른다. [P0 공통 명세](archive/p0/P0-spec.md#공통-규칙)는 `v0.1.0` 완료 시점의 제품 배경으로만 참조한다. 아래는 저장 계층이 지켜야 하는 불변식과 현행 제품 규칙을 저장에 반영하는 방식만 정의한다.

- 개설자는 `PARTICIPATIONS`에 참가 행을 만들지 않는다. 현재 총 인원과 참가자 목록 계산은 [정원·참가자 표시 규칙](#정원참가자-표시-규칙)을 따른다.
- `active_participant_count = ACTIVE 상태의 PARTICIPATIONS 수`는 서비스가 유지하는 불변식이며, `ROOMS`의 CHECK 제약은 카운터의 범위(`0`~`capacity`)만 보장한다.
- 참가·재참가·참가 취소는 참가 관계, `active_participant_count`, [P0 방 상태](archive/p0/P0-spec.md#방-상태roomstatus)의 전이를 한 트랜잭션에서 변경한다.
- 참가 가능성에 영향을 주는 변경은 `ROOMS` 행도 갱신해 `version`을 올린다. 모든 요청에서 `active_participant_count <= capacity`를 지켜야 하며, 동시성 제어는 [ADR-0005](adr/participation/0005-room-participation-optimistic-locking.md)를 따른다.
- `start_at`과 상태 전이 시각 비교는 [ADR-0009](adr/platform/0009-utc-time-standard.md)의 UTC 기준을 따른다.
- 해 본 게임 관계는 사용자의 명시적 표시·취소로만 생성·삭제한다. 방 생성·참가·종료 이력으로 자동 변경하지 않으며 다른 사용자의 관계를 공개 조회에 사용하지 않는다.
- 채팅 접근 여부는 `CHAT_ROOMS`에 회원 행을 복제하지 않고 요청 시점의 주최자·`ACTIVE` 참가 관계와 `ROOMS.status`로 판정한다.
- 방이 `CANCELED`·`FINISHED`로 전이된 뒤에도 저장된 메시지는 30일 보관하지만 일반 사용자 조회·전송·실시간 구독은 허용하지 않는다. 만료 메시지는 다음 일일 삭제 작업에서 최대 24시간 안에 제거한다.
- ROOM Scheduler 상태 보정은 영속 순회 경계에서 제한된 ID를 선별한 뒤 ROOM마다 독립 트랜잭션으로 처리하고, cursor는 별도 조건부 갱신 트랜잭션으로 전진한다. 채팅 만료 삭제는 소량 묶음마다 독립 트랜잭션으로 처리한다. ShedLock 임대 만료로 실행이 겹쳐도 ROOM은 최신 상태를 다시 확인하고 늦은 실행 주체의 진행 상태 갱신은 generation·version 불일치로 거절한다.

> 문서 관리: 소유자 `밤송이클럽 백엔드 팀` · 최종 검증일 `2026-08-15` · 폐기 조건 `저장 계약이 승인된 schema 생성 문서로 완전히 대체될 때`
