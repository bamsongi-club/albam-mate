# P1 검색 기능 명세

이 문서는 P1 필수 범위인 메커니즘을 포함한 `SEARCH-01`~`SEARCH-03`의 구현 규칙과 완료 기준을 정의한다. 현재 계약·생산 코드·자동 검증·운영 상태는 [P1 기능 상태 정본](README.md#기능별-현재-상태)을 따른다.

전체 범위·공통 검색 규칙은 [P1 명세](../P1-spec.md), 기존 동작은 [P0 완료 문서](../archive/p0/README.md), 요청·응답·오류는 [API 명세](../API.md), 저장 구조와 제약은 [ERD](../ERD.md)를 따른다. 메커니즘과 `SEARCH-03` 저장 계약은 ERD에 반영하며, 해당 저장 계약을 구현할 때는 전진 Flyway 마이그레이션과 PostgreSQL 검증을 함께 추가한다. 기존 `ROOMS` 필드만 사용하는 `SEARCH-02`에는 신규 저장 계약이나 마이그레이션을 요구하지 않는다.

P1 필수 게임 데이터 적재·검증 대상은 현재 확보한 약 2,000건이다. 전체 카탈로그 확장과 새 외부 데이터 취득은 별도 승인 범위다. 수치 검색은 [ADR-0026](../adr/game/0026-p1-game-search-normalized-numeric-fields.md), 메커니즘은 [ADR-0048](../adr/game/0048-full-reviewed-game-mechanism-catalog.md), 사용자별 해 본 게임은 [ADR-0028](../adr/game/0028-explicit-user-played-game-state.md)을 따른다.

`SEARCH-03`은 2026-08-04 P1 필수 범위로 채택됐다. 승인된 결정이 바뀌면 후속 ADR로 기존 결정을 대체하고 이 문서를 함께 갱신한다.

## 필터별 데이터 출처와 구현 가능 여부

| 필터 | 현재 저장 필드·구조 | 구현 가능 여부와 선행 조건 |
| --- | --- | --- |
| 플레이어 수 | `Game.supportedPlayerCount` 표시 문자열 | 가능. 적재 단계에서 최소·최대 수치로 변환하고 전수 검증해야 한다 |
| 플레이 시간 | `Game.estimatedPlayTime` 표시 문자열 | 가능. 현재 입력을 분 단위로 수치화한다. BGG 상세 `minplaytime`·`maxplaytime` 신규 취득은 이용 범위를 확인한 뒤 별도로 보강한다 |
| 복잡도 | nullable `Game.complexity` | 가능. 입력 `0.00`을 `NULL`로 정규화하고 `1.00`~`5.00`만 필터에 사용한다 |
| 메커니즘 | 검수된 내부 목록과 게임 다대다 관계 계약 | 가능. 정확한 승인 입력·manifest·검수 증거가 일치한 공개 항목만 사용한다 |
| 해 본 게임 포함·제외 | `USER_PLAYED_GAMES` 관계 | 가능. 사용자·게임 유일 관계와 본인 등록·취소 계약을 사용한다 |
| 방 날짜 | `Room.startAt` | 가능. 기존 필드에 범위 조건만 추가한다 |
| 방 남은 자리 | `Room.capacity`, `Room.activeParticipantCount` | 가능. 상태 정합화 뒤 두 값의 파생식으로 판정하고 별도 저장값을 추가하지 않는다 |
| 방 경험 수준 | `Room.experienceLevel` | 가능. 기존 enum 목록 조건만 추가한다 |
| 룰마스터 진행 | `Room.rulemasterLed` | 가능. 기존 boolean 조건만 추가한다 |

표시 문자열의 표현 종류 수, 복잡도의 실제 값 범위와 BGG 기준 스냅샷 행 수는 [입력 검수 기록](../game-catalog/2026-07-24-input-review.md)과 [출처 manifest](../game-catalog/2026-07-24-source-manifest.draft.json)를 정본으로 참조한다. 이 문서는 해당 수치를 복제하지 않는다.

BGG 기준 순위 스냅샷에는 플레이 시간 열이 없다. 약 2,000건을 넘는 범위의 인원·시간 값을 보강하려면 승인된 취득 경로와 배치 계획이 따로 필요하다. P0 적재 승인이 새 데이터 취득·재가공·공개 운영 권리를 자동으로 포함한다고 보지 않는다.

## SEARCH-01 게임 조건 검색

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기존 기능 | [GAME-01 게임 목록·검색](../archive/p0/game-catalog.md#game-01-게임-목록검색) |
| API 계약 | [게임 목록·검색](../API.md#game-01-게임-목록검색) |
| 공통 규칙 | [게임 데이터 정규화](../P1-spec.md#게임-데이터-정규화), [검색 조건과 결과](../P1-spec.md#검색-조건과-결과) |
| 데이터 모델 | [GAMES](../ERD.md#games), [GAME_MECHANISMS](../ERD.md#game_mechanisms), [GAME_MECHANISM_RELATIONS](../ERD.md#game_mechanism_relations) |
| 필수 ADR | [ADR-0026](../adr/game/0026-p1-game-search-normalized-numeric-fields.md), [ADR-0048](../adr/game/0048-full-reviewed-game-mechanism-catalog.md) — 승인됨 |
| 출처·적재 | [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md), [입력 검수 기록](../game-catalog/2026-07-24-input-review.md), [적재 절차](../guides/GAME_CATALOG_IMPORT.md) |
| 입력 데이터 | 입력 CSV와 변환 산출물은 저장소에 커밋하지 않는다. 적재 작업은 [입력 검수 기록](../game-catalog/2026-07-24-input-review.md)의 SHA-256과 일치하는 팀 공유 입력을 먼저 확보해야 하며, 입력을 새로 수집하거나 생성하지 않는다 |
| 성능 검증 | [FND-09 검색 성능과 인덱스 검증](foundation.md#fnd-09-검색-성능과-인덱스-검증) |
| HTTP 경계 | `GameController#listGames`, `GameListRequest`의 기존 조건과 반복 `mechanism`; 선택지는 `GET /api/game-mechanisms` |
| 현재 조회 경계 | `GameQueryService#findPage`, 불변 `GameListSearchCriteria`, `GameRepository#findAll(Specification, Pageable)`, `GameListRow`, `UpcomingRoomCountQuery`; 모든 조건은 단일 동적 조회에 전달하고 정렬은 엔티티 필드 `name`, `id` 오름차순 고정 |
| 저장 계약 | 기존 게임 표시·검색 필드, `GAME_MECHANISMS` 공개 목록과 `GAME_MECHANISM_RELATIONS` 다대다 관계. `Game.tag` 의미는 유지 |

### 기능 규칙

- 쿼리 파라미터 이름·타입·허용값·기본값은 [게임 목록·검색 API](../API.md#game-01-게임-목록검색)가 정본이다. 이 절은 필터의 의미와 판정 규칙만 정의하며 파라미터 이름을 새로 만들지 않는다.
- 게임 목록은 P0와 같이 비로그인도 조회한다. 필터를 모두 생략하면 P0의 공개 범위와 기존 응답 필드, `name ASC, id ASC` 정렬과 페이지네이션을 유지하되 `SEARCH-03`의 `playedByMe`만 추가한다.
- 기존 표시 문자열은 화면 표시와 입력 추적을 위해 유지하고, 조회 요청마다 문자열을 해석하지 않는다. 적재·마이그레이션 단계에서 검증한 수치만 필터에 사용한다.
- 아래 인원·시간 이름은 검색용 논리 필드이며, 실제 열 이름·타입·제약은 [GAMES](../ERD.md#games)에서 확정한다.
- 검색용 가능 인원은 양의 정수 `min_players`, `max_players`로 표현하고 두 값은 함께 존재하며 `min_players <= max_players`여야 한다.
- 검색용 플레이 시간은 분 단위 양의 정수 `min_play_time_minutes`, `max_play_time_minutes`로 표현하고 두 값은 함께 존재하며 `min <= max`여야 한다. 구간 판정은 최대값만 사용하고 최소값은 표시·검증용으로 저장한다.
- 복잡도는 `NULL` 또는 `1.00`~`5.00`이다. 입력 `0.00`은 난이도 0이 아니라 평가 없음으로 `NULL` 정규화한다.
- 기존 `supported_player_count`, `estimated_play_time`, `tag`는 표시 의미를 유지한다. `tag`를 메커니즘 목록으로 재사용하지 않는다.

| 조건 | 판정 규칙 |
| --- | --- |
| `1명`~`9명` | 선택한 인원이 검증된 최소·최대 가능 인원 범위에 포함됨 |
| `10명 이상` | 실제 최대 가능 인원이 10 이상임 |
| 인원 `최소 ~ 최대` | 게임이 요청 범위 전체를 지원함. 한쪽만 고르면 그 경계만 판정함 |
| 인원 정확히 일치 | 전달한 인원 경계가 게임의 경계와 같음 |
| `1인 전용`·`2인 전용` | 최소·최대 가능 인원이 모두 1 또는 모두 2임 |
| `UP_TO_10` | 검증된 최대 플레이 시간이 10분 이내 |
| `OVER_10_TO_20` | 검증된 최대 플레이 시간이 10분 초과 20분 이하 |
| `OVER_20_TO_30` | 검증된 최대 플레이 시간이 20분 초과 30분 이하 |
| `OVER_30_TO_60` | 검증된 최대 플레이 시간이 30분 초과 60분 이하 |
| `OVER_60_UNDER_90` | 검증된 최대 플레이 시간이 60분 초과 90분 미만 |
| `AT_LEAST_90` | 검증된 최대 플레이 시간이 90분 이상 |
| 복잡도 | 사용자가 지정한 최소·최대 닫힌 구간에 포함됨 |
| 메커니즘 | 선택한 공개 코드 중 하나 이상과 관계가 있음. 선택 목록 안 OR, 다른 필터와 AND |

- 인원은 보드라이프 방식의 `최소 ~ 최대`, `인원 정확히 일치`, `1인 전용`, `2인 전용`을 제공한다.
- 인원 최소·최대는 양의 정수이며 한쪽만 입력해도 된다. 두 계열의 조건을 함께 전달하거나 최소가 최대보다 크면 검증 오류다.
- `인원 정확히 일치`는 범위 경계에 붙는 수정자다. 맞출 경계가 없으면, 즉 최소·최대를 모두 생략하면 인원 조건을 적용하지 않는다.
- 전용 인원의 허용값은 `1`, `2`이며 둘을 함께 선택하면 OR로 결합한다.
- 플레이 시간은 6구간을 제공하고 여러 구간을 함께 선택하면 OR로 결합한다. 구간 경계값은 정확히 한 구간에만 속하고 `0분`과 음수는 어떤 구간에도 포함하지 않는다.
- 이전 플레이 시간 값 `SHORT`, `MEDIUM`, `LONG`은 제거했다. 단독으로 전달하거나 새 값과 섞어 전달하면 검증 오류이며 조용히 무시하지 않는다.
- 기존 `playerCount` 조건은 그대로 유지한다.
- 복잡도 최소값과 최대값은 각각 생략할 수 있지만 둘 다 전달하면 최소값이 최대값보다 크지 않아야 한다.
- 메커니즘은 안정적인 내부 코드를 반복 전달한다. 같은 코드를 반복해도 한 번 전달한 것과 같고, 존재하지 않거나 비공개인 코드는 검증 오류다.
- 특정 필터를 적용하면 그 조건을 판정할 검증값이 있는 게임만 결과에 포함한다. 필터를 생략한 조회는 해당 값이 없다는 이유만으로 게임을 제외하지 않는다.
- 서로 다른 필터 종류와 기존 `keyword`, `upcomingOnly`는 AND로 결합한다. 같은 식별자를 반복 전달해도 한 번 전달한 것과 같은 결과여야 한다.
- 모든 필터를 적용한 뒤 전체 건수를 계산하고 `name ASC, id ASC` 정렬과 페이지네이션을 적용한다. 내용 조회와 전체 건수 조회는 같은 조건을 사용한다.
- 필터링 전에 페이지를 자르거나 이미 잘린 페이지를 다시 걸러 빈 페이지와 잘못된 전체 건수를 만들지 않는다.
- 검색 결과에 기존 공개 계약이 허용하지 않는 사용자 식별자, 정확한 장소와 참가자 정보를 추가하지 않는다.
- 카탈로그 배치는 필드별 전체·유효·누락·제외 건수와 제외 사유를 품질 보고서에 남긴다. 값이 없거나 잘못됐다는 이유로 인원·시간·복잡도를 추정하지 않는다.
- 구체적인 물리 인덱스는 기능 정확성을 먼저 검증한 뒤 `FND-09`의 실행 계획과 측정 결과로 결정한다.

메커니즘 검색은 [ADR-0048](../adr/game/0048-full-reviewed-game-mechanism-catalog.md)의 검수된 189개 내부 목록과 게임 다대다 관계를 사용한다. 안정적인 내부 ID·코드와 변경 가능한 표시명을 분리하며 `tag`, 쉼표 문자열이나 JSON 배열에 관계를 저장하지 않는다.

- 선택지 API는 공개 항목만 `code`, `nameKo`, `nameEn`, `featuredOrder`로 반환한다.
- 대표 8개는 확정 순서로 먼저 반환하고 나머지는 한국어명·코드 오름차순으로 반환한다.
- exact checksum 입력에서 189개 목록과 13,263개 관계를 산출하며 입력·매핑·검수자·검수일·출처를 manifest와 품질 보고서로 다시 확인할 수 있어야 한다.
- 새 메커니즘은 기본 비공개이며 사람이 출처·표시명·관계를 검수한 뒤에만 서비스 선택지와 검색에 포함한다.
- 관계가 없는 게임은 메커니즘 조건을 생략한 조회에서 기존과 같이 반환하고, 누락을 채우려고 `기타`나 추정 관계를 만들지 않는다.

### 권장 조회 구조

- 현재 `GameQueryService#findPage`는 `keyword`, `upcomingOnly` 조합마다 `GameRepository`의 파생 조회 메서드를 골라 쓴다. P1 조건을 같은 방식으로 늘리면 조합 수만큼 메서드가 증가하므로, 새 조건은 불변 검색 조건 하나로 묶어 단일 동적 조회 경계에 전달한다.
- 약 2,000건 범위에서는 `upcomingOnly`가 사용하는 기존 `UpcomingRoomCountQuery`의 예정 모임 게임 ID 집합을 다른 조건과 함께 전달해 현재 모듈 경계를 유지할 수 있다. 전체 카탈로그로 확장할 때는 `FND-09` 측정 결과에 따라 DB `EXISTS` 조회 경계를 재검토한다.
- 메커니즘과 `PLAYED_ONLY`는 `EXISTS`, `EXCLUDE_PLAYED`는 `NOT EXISTS`로 판정해 관계 조인으로 게임 행이 중복되지 않게 한다.

### 완료 기준

- `SEARCH-01-AC1` `1명`부터 `9명`까지 각 선택값이 게임의 최소·최대 가능 인원 범위에 포함되는 게임만 반환한다.
- `SEARCH-01-AC2` `10명 이상`은 실제 최대 가능 인원이 10 이상인 게임을 반환한다.
- `SEARCH-01-AC3` 플레이 시간 6구간이 10·20·30·60·90분 경계를 각각 정확히 한 구간에만 넣고, 여러 구간을 함께 전달하면 OR로 결합하며 유효하지 않은 시간은 포함하지 않는다. 제거한 `SHORT`, `MEDIUM`, `LONG`은 검증 오류로 거절한다.
- `SEARCH-01-AC4` 복잡도 `1.00`~`5.00` 범위의 최소·최대 단독·조합 조건이 동작하고 입력 `0.00`은 평가 없음으로 처리된다.
- `SEARCH-01-AC5` 기존 검색어, 예정 모임 존재 여부와 모든 P1 필수 게임 조건을 조합해도 각 결과가 전달한 조건을 모두 만족한다.
- `SEARCH-01-AC6` 모든 조건을 적용한 뒤 `name ASC, id ASC` 정렬과 페이지네이션을 수행하고 필터 결과 기준 페이지 메타데이터를 반환한다.
- `SEARCH-01-AC7` 필터를 생략하면 `SEARCH-03`의 `playedByMe` 추가를 제외하고 P0 게임 검색의 공개 범위, 기존 응답 필드와 기본 정렬·페이지네이션을 유지한다.
- `SEARCH-01-AC8` 표시용 인원·시간 문자열을 유지하면서 검색용 값의 유효·누락·제외 건수와 출처를 재현 가능한 품질 보고서로 검증한다.
- `SEARCH-01-AC9` 인원 범위·경계 정확 일치·전용 인원이 확정한 판정대로 동작하고, 범위 조건과 전용 인원을 함께 전달하거나 최소가 최대보다 크면 검증 오류로 거절한다.
- `SEARCH-01-AC10` 공개 선택지 API가 검수된 189개만 안정적인 코드·한글명·영문명·대표 순서와 함께 반환한다.
- `SEARCH-01-AC11` 단일·다중 메커니즘이 각각 관계 게임과 선택 목록 OR를 중복 없이 반환하고 존재하지 않거나 비공개인 코드는 검증 오류다.
- `SEARCH-01-AC12` 메커니즘과 기존 조건을 조합하면 모든 조건을 만족한 결과 기준 정렬·페이지 메타데이터를 유지한다.
- `SEARCH-01-AC13` 메커니즘을 생략하면 관계가 없는 게임도 유지하고 적재를 반복해도 같은 게임·메커니즘 관계를 중복 저장하지 않는다.

### 제외 범위

- 특수 조건·테마·튜토리얼 보유·번역 완료 여부 필터
- 추천 인원·최적 인원을 가능 인원처럼 검색하는 기능
- 사용자 지정 정렬, 오타·초성 검색과 패싯별 결과 건수
- 개인화 추천과 별도 검색 엔진
- 약 2,000건을 넘는 전체 카탈로그 확장

## SEARCH-02 방 조건 검색

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기존 기능 | [ROOM-01 방 탐색](../archive/p0/room.md#room-01-방-탐색) |
| API 계약 | [방 목록 조회](../API.md#room-01-방-목록-조회) |
| 공통 규칙 | [검색 조건과 결과](../P1-spec.md#검색-조건과-결과), [P0 방 상태](../archive/p0/P0-spec.md#방-상태roomstatus) |
| 데이터 모델 | [ROOMS](../ERD.md#rooms) |
| 상태 정합화 | [ROOM-06 방 상태 정합화](../archive/p0/room.md#room-06-방-상태-정합화) |
| 성능 검증 | [FND-09 검색 성능과 인덱스 검증](foundation.md#fnd-09-검색-성능과-인덱스-검증) |
| 현재 HTTP 경계 | `RoomController#listRooms`, `RoomListRequest`, `RoomQueryParameterAllowlistValidator`; 제공 조건은 `type`, `gameId`, `keyword`, `startsAtFrom`, `startsAtTo`, `minRemainingSeats`, `experienceLevels`, `rulemasterOnly`, `page`, `size` |
| 현재 조회 경계 | `RoomListQueryService#findPage` → `RoomStatusCorrectionCoordinator#correctDueRooms` → `RoomListReadService#findPublicRooms` → `RoomRepository`; 모든 조건은 하나의 동적 조회에서 적용하고 정렬은 엔티티 필드 `startAt`, `id` 오름차순 고정 |
| 현재 저장 필드 | `Room.startAt`, `Room.capacity`, `Room.activeParticipantCount`, `Room.experienceLevel`, `Room.rulemasterLed` |

### 기능 규칙

- 쿼리 파라미터 이름·타입·허용값·기본값은 [방 목록 조회 API](../API.md#room-01-방-목록-조회)가 정본이다. 이 절은 필터의 의미와 판정 규칙만 정의하며 파라미터 이름을 새로 만들지 않는다.
- 방 목록은 P0와 같이 인증 없이 조회한다. 필터를 모두 생략하면 `RECRUITING`, `CLOSED` 공개 범위, 요청자 기준 `joinable`, `startsAt ASC, id ASC` 정렬과 페이지네이션을 유지한다.
- 날짜 범위는 방 시작 시각이 시작 경계 이상이고 종료 경계 미만인 반열린 구간 `[from, to)`이다. 한쪽 경계만 전달할 수 있고, 둘 다 전달하면 시작 경계가 종료 경계보다 빨라야 한다.
- 날짜 UI가 일 단위를 사용하면 `Asia/Seoul`의 해당 날짜 시작부터 다음 날짜 시작 전까지를 오프셋이 있는 시각으로 변환해 요청한다.
- 필요한 최소 남은 모집 자리는 1 이상 10 이하이며, 상한은 [DB 제약](../ERD.md#db-제약)의 모집 정원 범위를 따른다. 상태 정합화 뒤 `Room.capacity - Room.activeParticipantCount`가 요청값 이상인 방을 반환한다. 응답과 요청의 정원 필드 이름은 `recruitmentCapacity`이고 저장 열은 `capacity`이며, 같은 값에 새 열이나 별도 남은 자리 저장값을 추가하지 않는다.
- 경험 수준은 기존 `ExperienceLevel` 중 하나 이상을 선택할 수 있고 목록 안에서는 OR로 결합한다. 경험 수준은 참가 자격이 아니라 권장 조건이라는 의미를 유지한다.
- 룰마스터 진행만 보기를 선택하면 자기신고 값이 `true`인 방만 반환하며, 생략하면 룰마스터 여부를 조건으로 사용하지 않는다.
- 기존 방 유형, 게임 ID, 제목 검색과 P1 필터는 서로 독립적인 선택 조건이며 종류 사이에는 AND를 적용한다.
- 목록 필터와 페이지 계산 전에 현재 기준 시각으로 상태를 정합화한다. 모든 조건을 페이지네이션 전에 적용하고 내용 조회와 전체 건수 조회에 같은 조건을 사용한다.
- 유효한 세션이 있으면 필터 적용 여부와 관계없이 현재 사용자 기준 `joinable`을 계산한다. 검색 결과가 정확한 장소, 참가자 목록이나 사용자 식별자를 새로 노출하지 않는다.

### 권장 조회 구조

- 현재 `상태 정합화 → 공개 방·필터 조회 → 응답 조립` 순서를 유지하고, 새 조건은 `RoomListReadService`와 `RoomRepository`까지 전달한다. 정합화보다 앞에서 필터를 판정하지 않는다.
- 현재 `RoomListReadService#findPublicRooms`는 `keyword` 유무로 저장소 메서드를 나눈다. P1 조건을 조합마다 메서드로 늘리지 않고 하나의 동적 조회 경계로 모은다.
- 날짜, 경험 수준, 룰마스터와 `Room.capacity - Room.activeParticipantCount` 조건은 페이지네이션 전 SQL 조건으로 적용한다. 서비스에서 페이지 결과를 다시 걸러내지 않는다.

### 완료 기준

- `SEARCH-02-AC1` 시작·종료 시각의 단독·조합 필터가 `[from, to)` 경계대로 동작한다.
- `SEARCH-02-AC2` 필요한 남은 자리 필터는 상태 정합화 후 현재 `ACTIVE` 참가 관계를 반영한 파생값으로 판정한다.
- `SEARCH-02-AC3` 경험 수준 단일·다중 선택은 목록 안 OR로 동작하고 P0의 권장 조건 의미를 바꾸지 않는다.
- `SEARCH-02-AC4` 룰마스터 진행만 선택하면 자기신고 값이 `true`인 방만 반환한다.
- `SEARCH-02-AC5` 기존 방 유형, 게임 ID, 제목 검색과 모든 P1 조건을 조합하면 전달한 조건을 모두 만족하는 공개 방만 반환한다.
- `SEARCH-02-AC6` 모든 조건을 적용한 뒤 `startsAt ASC, id ASC` 정렬과 페이지네이션을 수행하고 필터 결과 기준 페이지 메타데이터를 반환한다.
- `SEARCH-02-AC7` 필터를 생략하면 P0 공개 범위, 현재 사용자 기준 `joinable`, 기본 정렬과 페이지네이션을 유지한다.

### 제외 범위

- 홍대 외 지역 필터
- 정확한 장소와 참가자 정보 검색
- 플레이어 수·시간·복잡도·메커니즘을 방 자체 조건으로 중복 저장
- 참가 가능 여부만 보는 별도 필터
- 사용자 지정 정렬과 개인화 방 추천

## SEARCH-03 사용자별 해 본 게임

`SEARCH-03`은 2026-08-04 P1 필수 범위로 채택됐다. 사용자가 직접 표시한 관계만 저장하고, 그 관계로 본인 표시 상태와 포함·제외 검색을 제공한다.

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 범위 상태 | P1 필수. 현재 상태는 [P1 기능 상태 정본의 `SEARCH-03`](README.md#기능별-현재-상태)을 따른다 |
| 검색 진입점 | [게임 목록·검색](../API.md#game-01-게임-목록검색) |
| 인증·공개 범위 | [P1 P0 계약 상속](../P1-spec.md#p0-계약-상속) |
| 저장 계약 | [USER_PLAYED_GAMES](../ERD.md#user_played_games), [ADR-0006](../adr/platform/0006-p0-bigint-identity-ids.md) |
| 필수 ADR | [ADR-0028](../adr/game/0028-explicit-user-played-game-state.md), [ADR-0047](../adr/platform/0047-http-method-and-target-state-idempotency.md) |
| 백엔드 구현 | [#356](https://github.com/bamsongi-club/albam-mate/issues/356) |
| 프론트엔드 구현 | [#357](https://github.com/bamsongi-club/albam-mate/issues/357) |

### 기능 규칙

- 사용자가 자신이 해 본 게임을 직접 표시하면 사용자·게임 관계가 생기고, 표시를 취소하면 관계가 사라진다. 관계가 없다는 사실을 `실제로 해보지 않음`으로 해석하지 않는다.
- 관계는 `id BIGINT GENERATED BY DEFAULT AS IDENTITY` 기본 키와 `user_id`, `game_id`, `created_at`을 가진다. 사용자·게임 조합은 최대 하나고 두 FK는 `ON DELETE NO ACTION`이다. 생성 시각은 표시한 시각이며 실제 플레이 날짜가 아니다.
- 등록은 `PUT /api/users/me/played-games/{gameId}`, 취소는 같은 경로의 `DELETE`다. 둘 다 로그인·CSRF가 필요하고 request body는 없다.
- 이미 등록한 게임의 재등록과 미등록 게임의 재취소도 `200 OK`다. 성공 `data`는 `{ gameId, playedByMe }`이며 등록은 `true`, 취소는 `false`로 같은 최종 상태에 수렴한다.
- 등록·취소 오류는 `UNAUTHENTICATED` → `CSRF_TOKEN_INVALID` → `VALIDATION_ERROR` → `GAME_NOT_FOUND` 순서로 판정한다.
- 방 생성·참가·종료나 외부 기록으로 관계를 자동 변경하지 않는다.
- 다른 사용자의 상태를 조회하거나 변경하는 공개 인터페이스를 제공하지 않는다.
- 공개 게임 목록·상세의 `playedByMe`는 유효한 세션에서 관계가 있으면 `true`, 없으면 `false`, 비로그인이면 `null`이다. `false`는 실제 미플레이가 아니라 표시되지 않음을 뜻한다.
- 게임 목록의 선택 파라미터 `playedFilter`는 단일 값 `PLAYED_ONLY` 또는 `EXCLUDE_PLAYED`만 허용한다. 생략하면 관계 필터를 적용하지 않는다.
- 잘못된 `playedFilter` 값이나 중복 전달은 로그인 여부와 관계없이 먼저 `400 VALIDATION_ERROR`다. 유효한 관계 필터를 비로그인으로 전달하면 `401 UNAUTHENTICATED`다.
- `PLAYED_ONLY`는 현재 사용자가 표시한 게임만 반환하고, `EXCLUDE_PLAYED`는 그 관계가 있는 게임을 결과에서 제외한다. 후자의 결과를 `해보지 않은 게임`이라고 단정하지 않는다.
- 관계 필터는 다른 게임 필터와 AND로 결합하고 모든 조건을 적용한 뒤 전체 건수, `name ASC, id ASC` 정렬과 페이지를 계산한다.
- 별도 `GET /api/users/me/played-games`는 만들지 않고 `GET /api/games?playedFilter=PLAYED_ONLY`를 사용한다.
- 웹은 목록 카드와 상세 화면 모두에 `해봤어요` 표시·취소를 둔다. 요청 중에는 조작을 잠그고 서버 성공 뒤 `playedByMe`를 반영하며 실패하면 기존 상태를 유지한다.
- 관계 필터는 게임 난이도와 같은 단일 `FilterRadioGroup`으로 `전체 / 해 본 게임만 / 해 본 게임 제외`를 제공한다. `전체`는 `playedFilter`를 보내지 않는다.
- 플레이 횟수, 플레이 날짜, 평점, 후기와 하고 싶은 게임 상태는 저장하지 않는다.

### 완료 기준

- `SEARCH-03-AC1` `USER_PLAYED_GAMES`가 identity 기본 키, 사용자·게임 FK, 표시 시각을 가지며 `(user_id, game_id)` 유일성과 두 FK의 `ON DELETE NO ACTION`을 보장한다.
- `SEARCH-03-AC2` 등록 API가 로그인·CSRF를 요구하고 신규·반복 요청 모두 `200 OK`의 `{ gameId, playedByMe: true }`로 수렴하며 확정한 오류 우선순위를 지킨다.
- `SEARCH-03-AC3` 취소 API가 로그인·CSRF를 요구하고 기존·반복 요청 모두 `200 OK`의 `{ gameId, playedByMe: false }`로 수렴하며 확정한 오류 우선순위를 지킨다.
- `SEARCH-03-AC4` 사용자는 자신의 관계만 조회·변경할 수 있고 방 이력 자동 표시와 다른 사용자 관계 공개가 발생하지 않는다.
- `SEARCH-03-AC5` 게임 목록·상세의 `playedByMe`가 유효한 세션에는 본인 관계의 `true`·`false`, 비로그인에는 `null`을 반환하며 `false`를 실제 미플레이로 표현하지 않는다.
- `SEARCH-03-AC6` 단일 `playedFilter`의 생략·두 허용값·잘못된 값·중복·비로그인 요청이 확정한 검증·인증 계약을 따르고, 다른 필터와 AND 결합한 뒤 전체 건수·`name ASC, id ASC` 정렬·페이지를 계산한다.
- `SEARCH-03-AC7` 목록 카드·상세의 표시·취소와 단일 선택 관계 필터가 제공되고 요청 중 중복 조작을 막으며 서버 성공 뒤에만 `playedByMe`를 반영한다. 실패하면 기존 화면 상태를 유지하고 공통 오류 흐름을 사용한다.

### 제외 범위

- 방 참가·종료 이력에서 해 본 게임 자동 표시
- 플레이 횟수·날짜·점수·후기
- 하고 싶은 게임, 보유 게임과 즐겨찾기 상태
- 다른 사용자의 해 본 게임 표시 공개

플레이 기록·통계 기능이 승인되면 별도 이력 모델을 추가한다. 현재 관계의 생성 시각을 실제 플레이 날짜나 과거 플레이 이력으로 변환하지 않는다.

## 부록: 구현 준비 메모

이 부록은 구현 범위와 변경 지점을 확인하기 위한 작업 메모다. 최종 계약은 [P1 명세](../P1-spec.md), [API 명세](../API.md), [ERD](../ERD.md)와 승인된 ADR을 따른다.

### 정본별 반영 시점

현재 P1 필수 범위는 한 승인 시점에 정본에 반영한다.

| 정본 | P1 필수 범위 반영 |
| --- | --- |
| [P1 명세](../P1-spec.md) | 메커니즘을 포함한 `SEARCH-01`~`SEARCH-03` 기능 목록·완료 기준 |
| [API 명세](../API.md) | 게임·방 검색과 메커니즘 선택지, 해 본 게임 파라미터·등록·취소·본인 표시 상태 |
| [ERD](../ERD.md) | 인원·시간 수치 열, 메커니즘 목록·관계와 사용자별 해 본 게임 관계·제약 |
| ADR | [ADR-0026](../adr/game/0026-p1-game-search-normalized-numeric-fields.md), [ADR-0028](../adr/game/0028-explicit-user-played-game-state.md), [ADR-0048](../adr/game/0048-full-reviewed-game-mechanism-catalog.md) 승인 |
| 카탈로그 manifest·가이드 | 인원·시간·메커니즘 필드의 출처, 정규화·검수 결과와 반복 적재 계약 |
| [기반 작업](foundation.md) | 구현된 필수 검색의 대표 데이터·쿼리·측정 기준 |

### 예상 변경 지점

아래는 현재 존재하는 파일 기준의 변경 지점이다. `USER_PLAYED_GAMES` 물리 계약은 ERD가 정본이며 Java 타입·파일 배치는 구현 이슈에서 기존 구조와 컨벤션에 맞춘다. Java 경로는 `src/main/java/cloud/bamsongi/albammate/` 기준이고, 그 밖의 경로는 저장소 루트 기준이다.

| 영역 | 현재 파일 |
| --- | --- |
| 게임 저장 모델 | `game/entity/Game.java` |
| 게임 요청·응답 | `game/dto/GameListRequest.java`, `game/dto/GameListItem.java`, `game/dto/GameDetail.java` |
| 게임 HTTP·조회 | `game/controller/GameController.java`, `game/service/GameQueryService.java`, `game/repository/GameRepository.java`, `game/repository/GameListRow.java` |
| 해 본 게임 관계 | 신규 Entity·Repository·Service와 `USER_PLAYED_GAMES` 전진 마이그레이션 |
| 카탈로그 변환 | `scripts/game-catalog/`의 변환·분석 스크립트와 테스트 |
| 방 요청·HTTP | `room/dto/RoomListRequest.java`, `room/controller/RoomController.java`, `room/controller/RoomQueryParameterAllowlistValidator.java` |
| 방 조회 | `room/service/query/RoomListQueryService.java`, `room/service/query/RoomListReadService.java`, `room/repository/RoomRepository.java` |
| DB 마이그레이션 | `src/main/resources/db/migration/`의 신규 전진 Flyway 파일 |
| 단위·통합 테스트 | `src/test/java/cloud/bamsongi/albammate/game/`, 같은 경로의 `room/` |
| PostgreSQL 테스트 | `src/postgresTest/`의 게임 카탈로그·방 목록 검증과 필요한 신규 테스트 |

### 구현·테스트 경계

- 게임 데이터 정규화는 전진 Flyway 마이그레이션, JPA 매핑과 PostgreSQL 검증을 함께 포함한다.
- 카탈로그 변환 테스트는 인원·시간 경계, `0분` 거절, 복잡도 `0.00`의 `NULL` 정규화와 누락값 처리를 검증한다.
- 게임·방 조회 테스트는 단독 필터, 종류 사이 AND, 모든 필수 조건 조합과 필터 후 페이지 계산을 검증한다.
- 해 본 게임 테스트는 사용자 격리, 등록·취소 멱등성, 오류 우선순위, 목록·상세 표시값과 관계 필터의 검증·인증·복합 검색을 HTTP와 PostgreSQL 경계에서 검증한다.
- 필터가 없는 게임·방 요청은 `SEARCH-03`의 `playedByMe` 추가를 제외한 기존 P0 동작의 회귀 테스트를 유지한다.
- PostgreSQL 전용 제약·마이그레이션·실행 계획은 H2 테스트만으로 검증했다고 보지 않는다.
- 인덱스의 필요성과 효과는 기능 테스트 통과 뒤 `FND-09`에서 별도로 측정한다.

### 구현 전 확인 필요

아래 항목은 현재 채택 범위 밖이거나 별도 데이터 승인 사항이다. 팀이 결정하기 전에는 구현 담당자나 에이전트가 임의로 확정하지 않는다.

- BGG 상세 플레이 시간을 전체 백필해 시간 구간 필터로 사용하는 행위가 현재 승인 범위에 포함되는지 여부
- 약 2,000건 이후 전체 카탈로그로 확장할 때 인원·시간·복잡도가 없는 게임의 공개·필터 정책
