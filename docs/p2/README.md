# P2 3차 MVP 문서

> **문서 상태: active · 정본 승격일: 2026-08-13**
>
> 이 문서 묶음은 P1 종료·아카이브 뒤 사용하는 팀 공통 P2 정본이다. 공통 명세가 활성화됐다는 사실은 개별 기능의 계약·구현·배포 완료를 뜻하지 않으며, 아래 상태표의 각 축을 따로 갱신한다.

P2는 AI 챗봇(게임 탐색 도우미 포함), 게임 의미 기반 검색, 실시간 매칭과 운영 관측을 팀원별 상세 명세로 나눈다. 이 문서는 각 기능 문서의 위치와 계약·생산 코드·자동 검증·배포·실측 상태를 한곳에서 찾는 라우터다.

## 문서 라우팅

| 기능 영역·문서 | 책임 | 현재 상태 |
| --- | --- | --- |
| [P2 공통 명세](../P2-spec.md) | P2 전체 범위, 기능 문서 작성 규칙, 공통 통합 원칙과 구현 완료 기준 | 정본 승격 완료 |
| [`AI-01` AI 모임 도우미](assistant.md#ai-01-ai-모임-도우미) | 사용자 동의·철회, assistant 진입·화면, 추천·확인 흐름과 기존 수동 Room 회귀 | 제품 계약 확정·T-ID 승인 필요·구현 보류 |
| [`AI-02` AI 의도·추천·Provider 운영](assistant.md#ai-02-ai-의도-추출추천provider-운영) | 구조화 조건, 후보 추천, provider adapter·fake·quota·timeout·비용·usage 경계 | 제품 계약 확정·T-ID 승인 완료·AI-02a Provider/foundation 구현 진행 (`#851` quota·비용·completion, `#852` usage·cost-warning consumer 분리) |
| [`AI-03` AI 초안·확인형 Room 생성](assistant.md#ai-03-ai-초안확인형-room-생성) | 15분 초안, 장소·지역, 멱등 확인과 Room·ChatRoom 원자성 | 제품 계약 확정·T-ID 승인 필요·구현 보류 |
| [`AI-04` AI 운영 배포·실측](assistant.md#ai-04-ai-운영-배포실측) | 인프라·secret/config, production 배포·feature gate·rollback, 배포 후 제한 실측 | 제품 계약 확정·T-ID 승인 필요·구현 보류 |
| [DISCOVERY-01 게임 탐색 도우미 명세](game-discovery-assistant.md#discovery-01) | 자연어 의도 해석·SEARCH-04 read-only tool 호출·근거 있는 응답·권한·안전·품질 | 초안 작성 완료·선행 계약 필요 |
| [SEARCH-04 게임 의미 기반 검색 명세](search.md#search-04) | 검색 대상 데이터, 색인·질의·정렬, 기존 검색과의 관계와 품질 평가 | 초안 작성 완료·선행 계약 필요 |
| [RANK-02 게임 인기순 정렬 명세](game-popularity.md#rank-02) | 국내·내부·국외 인기 원천 결합, 승인 배치·복구와 게임 목록 기본 정렬 | 구현·자동 검증 완료·배포/실측 필요 |
| [`CHAT-06` 입장·퇴장 시스템 메시지](chat.md#chat-06-입장퇴장-시스템-메시지) | 참가·참가 취소 확정의 채팅 안내 저장·조립·전달, 접근·보존과 소급 경계 | 저장·트랜잭션·활성화 gate 구현·PostgreSQL 검증 완료(`#869`), 이력·실시간 조회·전달·접근제어·가드는 구현 예정(`#870`) |
| [`CHAT-07` 채팅 목록 마지막 메시지·미읽음 상태](chat.md#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태) | 방별 읽음 커서 저장, 채팅 목록 마지막 메시지·미읽음 집계, 읽음 처리 API와 상단 배지 요약 | 계약 준비 완료·구현 예정 |
| [실시간 파티 매칭 명세](matching.md) | `MATCH-01`의 사용자 인원 범위 기반 매칭, 제안·채팅·신고·차단과 동시성·실패·복구·성능 검증 | 기능 명세 작성 완료·계약 전환 중 |
| [운영 관측](monitoring.md) | `OPS-01`~`OPS-05`의 기능별 지표·로그·검증 기준과 제외 범위 | OPS-01·OPS-02 구현·자동 검증·임시 AWS 실측·철거 완료, OPS-02 외부 API·AI는 해당 기능 미배포로 조건부 제외; OPS-03 구현·자동 검증·임시 AWS 실측·철거 완료, OPS-03 AI·Tool은 해당 기능 미배포로 조건부 제외; OPS-05 통합 검증·dashboard·임시 AWS 실측·철거 완료 |
| [운영 대시보드 정책](dashboard.md) | 생존·지연·실패·비용·업무 기능 결과의 화면·경고·증거 정책 | 정책 값 사용자 확인·정본 반영 완료 |

P2 구현은 [API](../API.md), [ERD](../ERD.md), [아키텍처](../ARCHITECTURE.md)와 승인 ADR 또는 아직 승인되지 않은 목표 계약을 구분해 유지한다. 각 기능 작성자는 자신의 상세 명세가 요구하는 변경만 식별하고, 구현 작업에서 소유 정본과 필요한 ADR을 함께 갱신한다.

## 기능별 현재 상태

이 표는 P1 종료 표와 같은 축으로 P2 기능별 계약 준비·생산 코드·자동 검증·배포·실측 상태를 구분한다. 기능 문서에는 변하지 않는 규칙과 완료 기준만 두고, 상태를 바꾸는 계약·코드·테스트·배포·측정 변경은 같은 변경에서 해당 행만 갱신한다.

| 기능 영역 | 기능 ID | 계약 준비 | 생산 코드 | 자동 검증 | 배포 상태 | 실측 상태 |
| --- | --- | --- | --- | --- | --- | --- |
| AI 모임 도우미 | [`AI-01`](assistant.md#ai-01-ai-모임-도우미) | T-ID 승인 필요 | 미구현 | 미검증 | 미배포 | 미측정 |
| AI 의도·추천·Provider | [`AI-02`](assistant.md#ai-02-ai-의도-추출추천provider-운영) | T-ID 승인 완료 | AI-02a Provider/foundation: port·fake/OpenAI adapter·payload allowlist와 공유 contract/seam 구현. Redis quota·비용·completion은 `#851`, usage·cost-warning event consumer는 `#852` 소유 | 미검증 | 미배포 | 미측정 |
| AI 초안·확인형 Room | [`AI-03`](assistant.md#ai-03-ai-초안확인형-room-생성) | T-ID 승인 필요 | 미구현 | 미검증 | 미배포 | 미측정 |
| AI 운영 배포·실측 | [`AI-04`](assistant.md#ai-04-ai-운영-배포실측) | T-ID 승인 필요 | 미구현 | 미검증 | 미배포 | 미측정 |
| 게임 탐색 도우미 | [`DISCOVERY-01`](game-discovery-assistant.md#discovery-01) | 선행 계약 필요 | 미구현 | 미검증 | 미배포 | 미측정 |
| 게임 의미 기반 검색 | [`SEARCH-04`](search.md#search-04) | 선행 계약 필요 | 미구현 | 미검증 | 미배포 | 미측정 |
| 게임 인기순 정렬 | [`RANK-02`](game-popularity.md#rank-02) | 계약 준비 완료 | 구현 완료 | 자동 검증 완료 | 미배포 | 미측정 |
| 실시간 파티 매칭 | [`MATCH-01`](matching.md#match-01-실시간-파티-매칭) | 계약 전환 중 | 부분 구현 | 미검증 | 미배포 | 미측정 |
| 채팅 시스템 메시지 | [`CHAT-06`](chat.md#chat-06-입장퇴장-시스템-메시지) | 계약 준비 완료 | 저장·트랜잭션·활성화 gate 구현 완료(`#869`). 이력·실시간 조회·전달·접근제어·가드는 `#870` 소유, 미구현 | 저장·gate PostgreSQL 검증 완료(`#869` T1~T6). 이력·전달 검증은 `#870` 소유, 미검증 | 미배포 | 미측정 |
| 채팅 목록 마지막 메시지·미읽음 | [`CHAT-07`](chat.md#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태) | 계약 준비 완료 | 미구현 | 미검증 | 미배포 | 미측정 |
| 서비스 생존·연결 | [`OPS-01`](monitoring.md#ops-01-서비스-생존과-연결-상태) | 계약 준비 완료 | 구현 완료 | 자동 검증 완료 | 임시 AWS 검증 배포·철거 완료 | `AC1`~`AC7` 실측 완료 |
| 지연·포화 | [`OPS-02`](monitoring.md#ops-02-지연과-포화) | 계약 준비 완료 | 앱·인프라 구현 완료, 미배포 외부 API·AI 조건부 제외 | 앱 CI·인프라 수집·query·주입·복구·teardown 자동 검증 완료 | 고정 SHA 임시 AWS 검증 배포·철거 완료 | `AC1`~`AC4`, `AC6` 실측 완료, `AC5` 조건부 제외 |
| 실패·이상 | [`OPS-03`](monitoring.md#ops-03-실패와-이상) | 계약 준비 완료 | 구현 완료 | 자동 검증 완료 | 임시 AWS 검증 배포·철거 완료 | `AC1`~`AC3`·`AC5` 실측 완료, `AC4` 조건부 제외 |
| AI 사용량·추정 비용 | [`OPS-04`](monitoring.md#ops-04-ai-사용량과-추정-비용) | 계약 준비 완료 | 미구현 | 미검증 | 미배포 | 미측정 |
| 핵심 업무 기능 결과 | [`OPS-05`](monitoring.md#ops-05-핵심-업무-기능-결과) | 계약 준비 완료 | 앱 검증기·인프라 실행·dashboard 구현 완료 | H2 계약·인프라 회귀·정상/통제 manifest 검증 완료 | 고정 SHA 임시 AWS 검증 배포·철거 완료 | 정상·통제 실패/복구 실측 완료 |

- `기능 명세 필요`: 기능 ID, 사용자 문제, 흐름, 데이터·권한, 완료 기준과 제외 범위가 아직 문서화되지 않았다.
- `선행 계약 필요`: 기능 명세가 있더라도 필수 ADR 승인 또는 API·ERD·아키텍처·운영 정본 반영이 남아 있다.
- `계약 준비 완료`: 구현에 필요한 제품·API·저장·아키텍처·운영 계약과 필수 ADR이 모두 반영·승인됐다. 생산 코드나 검증 완료를 뜻하지 않는다.
- `계약 전환 중`: 새 제품·API·아키텍처·운영 계약과 필수 ADR은 반영됐지만, 기존 저장 구조처럼 선행 migration이 필요한 정본이 아직 전환되지 않은 상태다. 소유 이슈가 완료될 때까지 해당 기능의 런타임 구현을 시작하지 않는다.
- `T-ID 승인 필요`: 제품·API·저장·아키텍처·운영 계약과 필수 ADR은 반영됐지만, 구현 이슈의 전체 T-ID 승인 댓글이 없어 테스트 계약은 미확정이다.
- `부분 구현`과 `부분 검증`: 연결한 기능 ID 일부만 생산 코드와 자동 증거를 갖춘 상태다.
- `미배포`와 `미측정`은 별도 상태다. 임시 검증 배포·운영 배포·유효 실측·`INVALID` 측정을 같은 값으로 합치지 않는다.

`OPS-01`·`OPS-02`·`OPS-03`·`OPS-05`는 [운영 관측 런북](../guides/MONITORING_OPERATIONS.md)에 metric·log 허용 목록, alarm query·runbook, 상태 전이·IAM·배포 증거 계약을 반영해 구현 선행 계약을 마쳤다. 이 가운데 [#730](https://github.com/bamsongi-club/albam-mate/issues/730)의 `OPS-01-AC1`~`AC3`과 [#731](https://github.com/bamsongi-club/albam-mate/issues/731)의 `OPS-01-AC4`~`AC7`은 생산 코드·운영 CLI·인프라 구현, 자동 검증, 같은 release의 AWS 임시 배포·실측과 teardown을 완료했다. [#732](https://github.com/bamsongi-club/albam-mate/issues/732)의 `OPS-02-AC1`~`AC4`, `AC6`도 앱·인프라 구현과 자동 검증, 고정 release의 baseline·slow-request·db-pool-wait·recovery 실측과 teardown을 완료했으며 [비식별 결과](../measurements/k6/jiho/ops02-latency-saturation-2026-08-19.md)에 근거를 남겼다. `AC5`는 외부 API·AI 기능이 배포되지 않은 현재 환경에서 조건부 제외하며, 이는 해당 기능의 향후 배포·관측 완료를 주장하지 않는다. [#733](https://github.com/bamsongi-club/albam-mate/issues/733)의 `OPS-03-AC1`~`AC3`·`AC5`도 앱·인프라 구현과 자동 검증, 고정 release의 4xx 기준선·대표 5xx·Redis 불능·scheduler 실패·복구, 중앙 로그 금지 field, `OK → ALARM → OK`와 실제 경고·복구 수신을 [제한 실측](../measurements/ops-03-failure-anomaly-observability-2026-08-19.md)하고 teardown을 완료했다. `AC4`는 AI·Tool 기능이 배포되지 않아 조건부 제외하며 timeout은 자동 검증까지만 완료했다. [#735](https://github.com/bamsongi-club/albam-mate/issues/735)의 `OPS-05`도 앱 검증기와 인프라 실행·dashboard, 자동 회귀를 구현하고 고정 release의 알림·채팅·참가 대기열 정상·통제 실패/복구를 [비식별 5-stage 결과](../measurements/k6/jiho/ops05-manifest-contract.md)로 채택한 뒤 teardown을 완료했다. 이 결과들은 임시 AWS 검증 배포이며 상시 운영 배포를 뜻하지 않는다. `OPS-04`는 [ADR-0074](../adr/platform/0074-p2-ai-provider-consent-and-operation-boundary.md)이 AI provider·model·호출 경계와 가격 snapshot 소유 계약을 승인해 `계약 준비 완료`다. 이는 생산 코드·자동 검증·배포·실측 완료를 뜻하지 않으며, 실제 배포·관측·가격 snapshot 전에는 `OPS-04`를 완료로 표시하지 않는다.

`AI-01`~`AI-04`는 [AI 기능군 명세](assistant.md), [AI-D01 ADR](../adr/platform/0074-p2-ai-provider-consent-and-operation-boundary.md), [AI-D02 ADR](../adr/room/0075-p2-ai-draft-confirmation-and-idempotent-room-command.md), [AI-D03 ADR](../adr/room/0076-p2-room-region-closed-set-and-compatibility.md)와 [API](../API.md), [ERD](../ERD.md), [아키텍처](../ARCHITECTURE.md)에 승인된 목표 계약을 기능별로 반영한다. 구현·운영 전달은 [AI-01~AI-04 독립 기능 표](assistant.md#ai-기능군-ai-01ai-04)의 순서와 상위 이슈를 따른다. migration·생산 코드·검증·배포·실측은 아직 남아 있다. AI 기능군은 `SEARCH-04` 읽기 전용 게임 탐색을 소유하는 `DISCOVERY-01`과 합치지 않으며, 구현 이슈는 각 소유 정본의 계약을 선행 링크로 고정한 뒤 시작한다. `DISCOVERY-01`과 `SEARCH-04`도 상세 명세를 등록했지만 필요한 API·ERD·아키텍처·ADR·운영 계약이 남아 있어 `선행 계약 필요`다. `MATCH-01`은 [API](../API.md)·[ERD](../ERD.md)·[아키텍처](../ARCHITECTURE.md)와 [MATCH ADR](../adr/matching/README.md)에 게임·플랫폼 없는 인원 범위 매칭 계약을 반영했지만, MATCH 저장 스키마에서 `game_id`를 제거하는 forward migration과 [ERD](../ERD.md) 동기화가 남아 있어 `계약 전환 중`이다. 이 저장 전환은 [#838](https://github.com/bamsongi-club/albam-mate/issues/838)이 소유하며, 완료 전에는 [#745](https://github.com/bamsongi-club/albam-mate/issues/745)의 런타임 구현을 시작하지 않는다. 기능 구현 중 드러난 `game`·`user`·`matching` 공통 공개 계약의 빈틈은 [#800](https://github.com/bamsongi-club/albam-mate/issues/800)에서 결정해 [ADR-0067](../adr/matching/0067-match-shared-contract-boundary.md)과 위 정본에 반영했고, 그 계약의 구현체·계약 테스트·구조 검사 등록은 [#801](https://github.com/bamsongi-club/albam-mate/issues/801)이 소유한다. MATCH 기능 구현 이슈는 #801과 #838이 `develop`에 반영된 뒤 공통 계약을 사용한다. 생산 코드·PostgreSQL 통합 검증·[MATCH-01 후보 탐색 baseline 측정 계약](../measurements/match-01-candidate-search-baseline-contract.md)의 실행과 결과 채택은 후속 구현에서 각각 갱신한다.

`CHAT-06`은 [채팅 기능 명세](chat.md)와 [ADR-0078](../adr/chat/0078-chat-system-message-storage-and-read-time-composition.md)로 저장 모델·문구 소유·사건 범위·소급 경계를 확정하고 [API](../API.md#chat-06-입장퇴장-시스템-메시지-계약)·[ERD](../ERD.md#chat-06-입장퇴장-시스템-메시지-저장-계약)·[아키텍처](../ARCHITECTURE.md#p2-chat-06-입장퇴장-시스템-메시지-흐름-계획미구현)에 목표 계약을 반영했다. `#869`가 `CHAT_MESSAGES`의 `sender_user_id`·`client_message_id`·`content` NOT NULL을 푸는 V33 expand migration, 종류별 CHECK 제약, `room.contract.RoomParticipantChanged`, `CHAT_SYSTEM_MESSAGE_ACTIVATION` 전역 gate와 동기 writer를 구현·PostgreSQL 검증까지 완료했다. 이력·실시간 조회가 `SYSTEM` 행을 문장으로 조립해 API로 반환하는 범위는 `#870`이 소유하며 아직 미구현이다. P1 `CHAT-01`~`CHAT-05` 아카이브 문서는 이 기능의 정본이 아니다.

`CHAT-07`은 [채팅 기능 명세](chat.md#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태)와 [ADR-0079](../adr/chat/0079-chat-room-read-cursor-and-derived-unread-count.md)로 읽음 커서 저장·미읽음 파생 계산·상단 배지 집계 방식을 확정하고 [API](../API.md#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태-계약)·[ERD](../ERD.md#chat-07-읽음-커서-저장-계약)·[아키텍처](../ARCHITECTURE.md#p2-chat-07-채팅-목록-미읽음-집계-흐름-계획미구현)에 목표 계약을 반영했다. `CHAT_ROOM_READ_STATES`는 신규 테이블이라 `CHAT-06`과 달리 인스턴스 순차 전환이 필요 없다. [CHAT-07 완료 기준의 `T-ID` 후보](chat.md#chat-07-자동-검증)는 [#810 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/810#issuecomment-5337399658)로 동결돼 `계약 준비 완료`다.

## 팀 기능 문서 작성 규칙

각 팀원은 자신의 기능 문서를 `docs/p2/` 아래에 추가하고 다음 순서를 지킨다.

1. 저장소 전체의 기존·예약 기능 ID를 확인하고 기능 접두사와 상위 ID를 이 README에 먼저 등록한다.
2. [P2 공통 명세의 필수 구성](../P2-spec.md#기능-문서-필수-구성)에 따라 구현 컨텍스트, 사용자 문제·흐름, 기능 규칙, 완료 기준과 제외 범위를 작성한다. 빈 제목이나 기술명 나열만 남기지 않는다.
3. 필요한 API·ERD·아키텍처·운영 가이드 변경과 ADR 필요 여부를 식별하고, 구현 전에 소유 정본에 반영한다.
4. 데이터·권한·보안 경계, 외부 provider·model·저장소, 프로토콜, 기능 간 이벤트, 장애·복구, 비용처럼 되돌리기 어렵거나 여러 기능에 영향을 주는 선택은 ADR로 남긴다. 단순 클래스 배치나 지역적인 구현 세부는 ADR을 만들지 않는다.
5. ADR에는 문제와 제약, 검토한 대안, 선택과 제외 이유, 장단점·위험, 배포·rollback, 검증 방법, 다시 검토할 조건을 구체적으로 적는다. `결정함`만 쓰거나 구현 이슈를 링크하는 것으로 대체하지 않는다.
6. 다른 기능 문서의 정책을 복사하지 않고 소유 문서와 정확한 절을 링크한다. 충돌하는 요구는 조용히 합치지 않는다.
7. 자신의 문서 경로와 기능 ID를 위 문서 라우팅·상태표에 추가한다.
8. 구현·검증·배포·실측이 진행되면 문서 본문에 현재 상태를 반복하지 않고 상태표의 해당 행만 갱신한다.

기능 문서의 권장 절 순서는 P1과 같이 `구현 컨텍스트 → 핵심 흐름 → 기능 규칙 → 완료 기준 → 제외 범위`다. 기능이 크면 상위 통합 완료 기준과 하위 구현 완료 단위를 나눈다.

## P1에서 P2로 전환한 기록

1. 아카이브 이동 전 P1 기준선 `develop@8676813d`를 Git 태그 [`v0.2.0`](https://github.com/bamsongi-club/albam-mate/tree/v0.2.0)으로 고정했다.
2. [P1 구현 완료 기준](../archive/p1/P1-spec.md#구현-완료-기준)과 [P1 기능 종료 상태](../archive/p1/README.md#기능별-종료-상태)를 기준으로 P1 완료와 운영 제한사항을 확정했다.
3. P1 공통·기능 문서를 `docs/archive/p1/`으로 이동하고 종료 상태를 동결했다.
4. `P2-spec.md`와 `p2/` 문서를 `active` 정본으로 승격했다.
5. 루트 README·AGENTS와 PRD의 현재 단계 라우팅을 P2로 전환했다.
6. 팀원별 P2 기능 문서·기능 ID와 구현에 필요한 API·ERD·아키텍처·ADR·운영 가이드는 각 기능 작업에서 상세히 확정한다.

## 문서 작성과 통합 순서

1. AI 챗봇 담당자는 상세 명세와 기능 ID를 작성한다. 게임 탐색 도우미·의미 기반 검색·실시간 매칭은 등록된 각 기능 명세와 기능 ID를 사용한다.
2. 공통 API·데이터·권한·이벤트 충돌을 확인하고 소유 정본과 선행 관계를 확정한다.
3. 운영 관측은 공통 수집 기반을 준비하면서 각 기능 문서가 정의한 지연·실패·사용량·최종 결과 신호를 연결한다.
4. 기능별로 계약 준비가 끝난 범위부터 독립 구현·자동 검증하고, 선행 관계가 있는 통합 흐름은 고정 release에서 함께 검증한다.
5. 문서·계약·생산 코드·자동 검증·운영 배포와 실측 상태를 구분해 이 README에 기록한다.

각 팀 기능은 병렬로 문서화할 수 있다. 한 기능의 세부 정책을 공통 명세가 대신 결정하거나, 다른 기능 문서가 끝날 때까지 모든 작업을 일괄 차단하지 않는다.
