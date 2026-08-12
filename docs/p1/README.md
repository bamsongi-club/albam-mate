# P1 2차 MVP 문서

P1 2차 MVP의 현재 계획·구현 기준 문서 묶음이다. 문서가 존재하거나 ADR이 승인됐다는 사실은 구현·API·DB 마이그레이션 또는 검증 완료를 뜻하지 않는다.

## 문서 라우팅

| 문서 | 책임 |
| --- | --- |
| [P1 공통 명세](../P1-spec.md) | P1 범위, 핵심 흐름, 공통 규칙, 구현 완료 기준 |
| [소셜 로그인](social-login.md) | `AUTH-05`의 Google·Naver·Kakao 로그인과 기존 계정의 명시적 연결 |
| [검색](search.md) | 메커니즘을 포함한 `SEARCH-01`~`SEARCH-03` |
| [ROOM·참가 고도화](room.md) | `ROOM-08`~`ROOM-10`, `PART-04`의 행동 가능성·대기열·상태 자동 전환·동시성 실증 |
| [알림](notification.md) | `NOTI-01`~`NOTI-03`의 알림 생성, 본인 목록·미확인 개수와 읽음 처리 |
| [방 채팅](chatting.md) | `CHAT-01`~`CHAT-05`의 채팅방 접근, 영속 이력, 실시간 전달·복구와 안전·운영 |
| [기반 작업](foundation.md) | `FND-09`, `FND-10`의 검색 성능·인덱스 검증과 실시간 전달 기반 |
| [인기 랭킹](ranking.md) | `RANK-01`의 내부 방 데이터 기반 전체·앞으로 7일 인기 게임 랭킹 |

기능 구현은 해당 기능 문서의 완료 기준과 현재 [API](../API.md), [ERD](../ERD.md), 관련 승인 ADR을 함께 충족해야 한다. API의 `제공 상태`는 현재 요청·응답에 사용할 수 있는지를 나타내고, 기능 전체의 생산 코드·자동 검증·운영 상태는 아래 표만 정본으로 사용한다.

P1 저장 계약의 준비 상태는 기능별로 다르다. 현재 알림, PART-04 대기열, 채팅·ShedLock과 ROOM 상태 보정 진행 상태의 물리 구조는 [ERD](../ERD.md)와 전진 Flyway 마이그레이션에 반영되어 있다. 기능 전체의 계약 준비 여부는 아래 표의 `계약 준비` 열을 따른다. `선행 계약 필요`인 기능의 미확정 문서 후보를 그대로 물리 저장 계약으로 사용하지 않는다. 다만 같은 이슈·PR에서 아래 단일 이슈·PR 규칙의 1~2단계를 마쳐 선택한 계약을 정본에 반영했다면, 상태표의 중간 갱신 없이 그 계약으로 구현을 계속할 수 있다.

## 기능별 현재 상태

이 표는 P1 필수 기능과 기반 작업 ID별 계약 준비·생산 코드·자동 검증·운영 배포와 실측 상태의 정본이다. 상태를 바꾸는 계약·코드·테스트·배포 변경은 같은 변경에서 해당 행만 갱신한다. 기능 문서와 API·ERD·아키텍처에는 변하지 않는 단계·책임·완료 기준만 두고 현재 상태를 반복하지 않는다. 조건부 후속인 ID는 P1 필수 범위에 채택되기 전까지 이 표에 넣지 않는다.

| 기능 ID | 계약 준비 | 생산 코드 | 자동 검증 | 운영 배포·실측 |
| --- | --- | --- | --- | --- |
| [`AUTH-05`](social-login.md#auth-05-소셜-로그인계정-연결) | 계약 준비 완료 | 구현 완료 ([#331](https://github.com/bamsongi-club/albam-mate/issues/331), [#332](https://github.com/bamsongi-club/albam-mate/issues/332), [#333](https://github.com/bamsongi-club/albam-mate/issues/333), [#334](https://github.com/bamsongi-club/albam-mate/issues/334)) | 검증 완료 ([#331](https://github.com/bamsongi-club/albam-mate/issues/331), [#332](https://github.com/bamsongi-club/albam-mate/issues/332), [#333](https://github.com/bamsongi-club/albam-mate/issues/333), [#334](https://github.com/bamsongi-club/albam-mate/issues/334), [#425](https://github.com/bamsongi-club/albam-mate/issues/425): 제공자 콘솔 등록이 필요한 수동 QA 수행됨) | 미배포·미측정 |
| [`SEARCH-01`](search.md#search-01-게임-조건-검색) | 계약 준비 완료 ([#420](https://github.com/bamsongi-club/albam-mate/issues/420), [#597](https://github.com/bamsongi-club/albam-mate/issues/597)) | 구현 완료 ([#293](https://github.com/bamsongi-club/albam-mate/issues/293), [#295](https://github.com/bamsongi-club/albam-mate/issues/295), [#348](https://github.com/bamsongi-club/albam-mate/issues/348), [#351](https://github.com/bamsongi-club/albam-mate/issues/351), [#357](https://github.com/bamsongi-club/albam-mate/issues/357), [PR #424](https://github.com/bamsongi-club/albam-mate/pull/424), [PR #598](https://github.com/bamsongi-club/albam-mate/pull/598)) | 검증 완료 (기존 검색 검증과 [PR #424](https://github.com/bamsongi-club/albam-mate/pull/424)의 적재 수렴·필터·170,000행 PostgreSQL 실행 계획 측정, [PR #598](https://github.com/bamsongi-club/albam-mate/pull/598)의 테마·메커니즘 ANY·ALL 회귀 검증) | 미배포·미측정 |
| [`SEARCH-02`](search.md#search-02-방-조건-검색) | 계약 준비 완료 | 구현 완료 ([#294](https://github.com/bamsongi-club/albam-mate/issues/294), [PR #317](https://github.com/bamsongi-club/albam-mate/pull/317): 기존 공개 목록 조건 조회; [#557](https://github.com/bamsongi-club/albam-mate/issues/557), [PR #574](https://github.com/bamsongi-club/albam-mate/pull/574): ADR-0055·0056의 조회 유효 상태·사전 전역 보정 제거·snapshot 반영) | 검증 완료 (기존 목록 조건 검증과 [PR #574](https://github.com/bamsongi-club/albam-mate/pull/574)의 H2 유효 상태·PostgreSQL snapshot 회귀) | 미배포·미측정 |
| [`SEARCH-03`](search.md#search-03-사용자별-해-본-게임) | 계약 준비 완료 | 구현 완료 ([#356](https://github.com/bamsongi-club/albam-mate/issues/356), [#357](https://github.com/bamsongi-club/albam-mate/issues/357): 프론트엔드 `SEARCH-03-AC7`) | 검증 완료 ([#356](https://github.com/bamsongi-club/albam-mate/issues/356): H2·PostgreSQL 대상 테스트, [#357](https://github.com/bamsongi-club/albam-mate/issues/357): 프론트엔드 표시·취소·관계 필터 테스트) | 미배포·미측정 |
| [`ROOM-08`](room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리) | 계약 준비 완료 | 구현 완료 ([#303](https://github.com/bamsongi-club/albam-mate/issues/303): 기존 행동 가능 여부; [#557](https://github.com/bamsongi-club/albam-mate/issues/557), [PR #574](https://github.com/bamsongi-club/albam-mate/pull/574): 목록·내 모임의 ADR-0055·0056 유효 상태·snapshot 반영) | 검증 완료 ([#303](https://github.com/bamsongi-club/albam-mate/issues/303)의 기존 H2·PostgreSQL 대상 테스트와 [PR #574](https://github.com/bamsongi-club/albam-mate/pull/574)의 목록·내 모임 PostgreSQL 회귀) | 미배포·미측정 |
| [`PART-04`](room.md#part-04-선착순-대기열과-자동-승격) | 계약 준비 완료 | 구현 완료 ([PR #395](https://github.com/bamsongi-club/albam-mate/pull/395), [PR #428](https://github.com/bamsongi-club/albam-mate/pull/428), [PR #434](https://github.com/bamsongi-club/albam-mate/pull/434)) | 검증 완료 (H2 API·명령 테스트와 PostgreSQL 스키마·Repository·등록·재신청·자동 승격 경합 검증) | 미배포·미측정 |
| [`ROOM-09`](room.md#room-09-시간-기반-room-상태-자동-전환의-대량-처리-고도화) | 계약 준비 완료 | 부분 구현 ([#381](https://github.com/bamsongi-club/albam-mate/issues/381): 영속 progress·ShedLock 실행 기반; [#382](https://github.com/bamsongi-club/albam-mate/issues/382): 제한 후보 선별·ROOM별 독립 처리·전체 순회 연결; [#390](https://github.com/bamsongi-club/albam-mate/issues/390): 측정으로 확정한 제한 ID·`lockAtMostFor`·실행시간 경고 초기 운영값 적용) | 부분 검증 ([#381](https://github.com/bamsongi-club/albam-mate/issues/381): T1–T6 단위·PostgreSQL·local; [#382](https://github.com/bamsongi-club/albam-mate/issues/382): H2 projection·시작 경계 원자성·PostgreSQL 실패 격리·cursor 재처리; [#383](https://github.com/bamsongi-club/albam-mate/issues/383): T1–T3와 승인된 소형·중형·대형 현행 기준선 실측; [#390](https://github.com/bamsongi-club/albam-mate/issues/390): 현행 대비 후보 실측과 확정 운영값의 설정 바인딩 검증. 실패 backoff·조건부 직접 갱신 비교는 사용자 결정 대기) | 미배포·미측정 |
| [`ROOM-10`](room.md#room-10-동시성과-락-전략-실증) | 계약 준비 완료 | 부분 구현 ([PR #439](https://github.com/bamsongi-club/albam-mate/pull/439): 참가 경합 기준선, [PR #464](https://github.com/bamsongi-club/albam-mate/pull/464): 대기·자동 승격 경합 기준선, [PR #483](https://github.com/bamsongi-club/albam-mate/pull/483): 시작 경계 경합 기준선) | 부분 검증 ([PR #439](https://github.com/bamsongi-club/albam-mate/pull/439), [PR #462](https://github.com/bamsongi-club/albam-mate/pull/462), [PR #464](https://github.com/bamsongi-club/albam-mate/pull/464), [PR #483](https://github.com/bamsongi-club/albam-mate/pull/483): 재현 가능한 PostgreSQL 원자료와 저장 불변식 검증; [#495](https://github.com/bamsongi-club/albam-mate/issues/495) 결정에 따라 P1에서는 현행 낙관적 락을 유지하고, 비관적 락 비교와 최종 잠금 전략 ADR은 배포 후로 이관했으며, 기존 `RoomOptimisticLockRetrier` WARN 로그의 `event`·`roomId`·`attempt`가 현재 관측 근거다. 별도 운영 metric은 구체적인 대시보드·알림·집계 자동화 요구가 생길 때 별도 이슈로 재검토한다. 참가 취소·자동 승격 경로에서 재시도 소진 `409`가 서로 다른 ROOM에서 반복 관측되면 재검토한다. [#539](https://github.com/bamsongi-club/albam-mate/issues/539)에서 정본 문서를 동기화함) | 미배포·미측정 |
| [`NOTI-01`](notification.md#noti-01-모임-변경-알림-생성) | 계약 준비 완료 | 구현 완료 ([PR #297](https://github.com/bamsongi-club/albam-mate/pull/297), [PR #314](https://github.com/bamsongi-club/albam-mate/pull/314), [PR #329](https://github.com/bamsongi-club/albam-mate/pull/329), [PR #340](https://github.com/bamsongi-club/albam-mate/pull/340), [PR #365](https://github.com/bamsongi-club/albam-mate/pull/365), [PR #447](https://github.com/bamsongi-club/albam-mate/pull/447), [#499](https://github.com/bamsongi-club/albam-mate/issues/499): 대기자 자동 승격 알림) | 검증 완료 (Outbox·relay·복구·cleanup 단위·통합·PostgreSQL 검증, [#499](https://github.com/bamsongi-club/albam-mate/issues/499): 승인 T1~T9 H2·PostgreSQL·프론트엔드 검증) | 미배포·미측정 |
| [`NOTI-02`](notification.md#noti-02-내-알림-목록미확인-개수) | 계약 준비 완료 | 구현 완료 ([PR #309](https://github.com/bamsongi-club/albam-mate/pull/309), [PR #327](https://github.com/bamsongi-club/albam-mate/pull/327), [PR #411](https://github.com/bamsongi-club/albam-mate/pull/411)) | 검증 완료 (목록·미확인 개수의 HTTP·H2·PostgreSQL 만료·페이지 경계와 프론트 polling 검증) | 미배포·미측정 |
| [`NOTI-03`](notification.md#noti-03-알림-읽음-처리) | 계약 준비 완료 | 구현 완료 ([PR #339](https://github.com/bamsongi-club/albam-mate/pull/339), [PR #354](https://github.com/bamsongi-club/albam-mate/pull/354)) | 검증 완료 (단건·일괄 읽음의 HTTP·PostgreSQL 스냅샷·동시성 및 프론트 재동기화 검증) | 미배포·미측정 |
| [`CHAT-01`](chatting.md#chat-01-채팅방-생성접근) | 선행 계약 필요 | 부분 구현 ([#279](https://github.com/bamsongi-club/albam-mate/issues/279)) | 부분 검증 ([#279](https://github.com/bamsongi-club/albam-mate/issues/279)) | 미배포·미측정 |
| [`CHAT-02`](chatting.md#chat-02-메시지-전송이력-조회) | 계약 준비 완료 | 구현 완료 ([#427](https://github.com/bamsongi-club/albam-mate/issues/427)) | 검증 완료 ([#427](https://github.com/bamsongi-club/albam-mate/issues/427): 백엔드 전체 테스트·프론트 자동화 테스트·Vite build) | 미배포·미측정 |
| [`CHAT-03`](chatting.md#chat-03-실시간-전달재연결-복구) | 계약 준비 완료 | 구현 완료 ([#285](https://github.com/bamsongi-club/albam-mate/issues/285), [#286](https://github.com/bamsongi-club/albam-mate/issues/286)) | 검증 완료 ([#286](https://github.com/bamsongi-club/albam-mate/issues/286): H2·PostgreSQL T1~T12 대상 테스트) | 미배포·미측정 |
| [`CHAT-04`](chatting.md#chat-04-채팅-안전운영) | 계약 준비 완료 | 구현 완료 ([PR #366](https://github.com/bamsongi-club/albam-mate/pull/366), [PR #405](https://github.com/bamsongi-club/albam-mate/pull/405), [PR #423](https://github.com/bamsongi-club/albam-mate/pull/423), [PR #444](https://github.com/bamsongi-club/albam-mate/pull/444)) | 검증 완료 (보존·ShedLock, 사용자·방 전송 제한, 입력 안전과 민감 정보 비노출의 H2·PostgreSQL 검증) | 미배포·미측정 |
| [`CHAT-05`](chatting.md#chat-05-내-모임-채팅-진입) | 계약 준비 완료 | 구현 완료 ([#290](https://github.com/bamsongi-club/albam-mate/issues/290), [#427](https://github.com/bamsongi-club/albam-mate/issues/427)) | 검증 완료 ([#290](https://github.com/bamsongi-club/albam-mate/issues/290), [#427](https://github.com/bamsongi-club/albam-mate/issues/427)) | 미배포·미측정 |
| [`FND-09`](foundation.md#fnd-09-검색-성능과-인덱스-검증) | 계약 준비 완료 | 구현 완료 (#307) | 검증 완료 (#307: PostgreSQL 검색 성능·인덱스 검증) | 미배포·미측정 |
| [`RANK-01`](ranking.md#rank-01-인기-게임-랭킹) | 계약 준비 완료 ([#596](https://github.com/bamsongi-club/albam-mate/issues/596)) | 미구현 | 미검증 | 미배포·미측정 |
| [`FND-10`](foundation.md#fnd-10-실시간-전달과-재연결-기반) | 계약 준비 완료 | 구현 완료 ([#360](https://github.com/bamsongi-club/albam-mate/issues/360), [#286](https://github.com/bamsongi-club/albam-mate/issues/286)) | 검증 완료 ([#286](https://github.com/bamsongi-club/albam-mate/issues/286): H2·PostgreSQL T1~T12 대상 테스트, [#445](https://github.com/bamsongi-club/albam-mate/issues/445): `local` 프록시 경유 WebSocket Upgrade·교차 인스턴스 실시간 전달·재연결 복구 검증) | 미배포·미측정 |

- `계약 준비 완료`: 기능 구현에 필요한 제품·API·저장·아키텍처 계약과 필수 ADR이 모두 반영·승인됐다. 생산 코드나 검증 완료를 뜻하지 않는다.
- `선행 계약 필요`: 기능 명세가 있더라도 필수 ADR 승인, ERD·아키텍처 반영 또는 `착수 전 확정`과 같은 구현 전 결정이 남아 있다.
- `선행 기능 계약 대기`: 기반 작업이 의존하는 기능 계약이 먼저 준비되어야 한다.
- `부분 구현`과 `부분 검증`은 해당 기능의 일부 구현 이슈만 생산 코드와 자동 검증을 갖춘 상태이며, 연결한 이슈가 그 증거 범위를 한정한다.
- `미구현`, `미검증`, `미배포·미측정`은 각각 요구된 생산 코드·기반 산출물, 자동 검증 증거, 실제 운영 배포·실측이 없음을 뜻한다. 한 상태의 완료를 다른 상태의 완료로 대신하지 않는다.

### 채팅의 선행 계약 사유

다음 표는 현재 상태표에서 `선행 계약 필요`인 채팅 기능의 미해결 선행 계약과 해소 위치를 한곳에 모은다. `CHAT-02`의 전송 제한 계약은 [#288 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930)과 [#372 정본 반영 이슈](https://github.com/bamsongi-club/albam-mate/issues/372)로 확정됐다. `CHAT-03`의 세션 TTL·직렬화·namespace와 현재 `local` 실행 경계는 [ADR-0052](../adr/platform/0052-local-profile-multi-instance-default.md)에서 확정·반영됐고, `CHAT-04`의 보관·잠금 구간 값도 [ADR-0049](../adr/chat/0049-chat-message-retention-lock-section-boundary.md)와 [PR #366](https://github.com/bamsongi-club/albam-mate/pull/366)에서 확정·구현됐으므로 미해결 표에서 제외한다. 실제 상태는 위 상태표만 갱신하며, 세부 선택을 마치면 선택값과 근거를 기능·API·ERD·아키텍처·실행 구성 중 표에 적은 위치에 반영한다. 승인 ADR 링크는 구현 이슈에 결정을 위임한 근거이며 승인된 결정 본문을 수정하라는 뜻이 아니다. 제안 ADR은 팀 채택 전까지 확정된 구현 근거로 사용하지 않으며, 승인 경계를 바꿔야 하면 기존 ADR을 고치지 않고 새 ADR로 대체한다.

| 기능 ID | 착수 전에 확정할 계약 | 결정 주체 | 위임 근거·반영 위치 |
| --- | --- | --- | --- |
| `CHAT-01` | 기존 ROOM의 채팅방 backfill과 ROOM 생성·상태 전환 경합을 막을 동시성 제어·최종 보정, 배포 절체 방식 | [#281](https://github.com/bamsongi-club/albam-mate/issues/281) 구현 담당자. 서비스 중단·트래픽 차단이 필요하면 사용자·OPS 승인 | [#279의 최신 승인 테스트 계약](https://github.com/bamsongi-club/albam-mate/issues/279#issuecomment-5161788285)에 따라 #279는 V6 스키마·제약만 소유한다. 승인된 [ADR-0045](../adr/chat/0045-chat-room-schema-and-backfill-boundary.md)는 production 스키마 기동과 local callback 초기화의 경계를 소유한다. #281의 승인된 선택 결과는 [CHAT-01](chatting.md#chat-01-채팅방-생성접근), [ERD](../ERD.md#chat_rooms), [채팅 흐름](../ARCHITECTURE.md#채팅-흐름)과 실행 작업에 반영 |

`CHAT-05`는 제품·API·저장·아키텍처 계약과 필수 ADR에 남은 결정이 없어 `계약 준비 완료`다. 이는 `CHAT-01`~`CHAT-03`의 구현 없이 독립적으로 사용자 흐름을 완료할 수 있다는 뜻이 아니다.

`FND-10`은 `CHAT-03` 계약을 수동으로 기다리는 작업이 아니라 세션·Redis 구성값을 확정하고 실시간 기반을 구현하는 소유 작업이다. [ADR-0052](../adr/platform/0052-local-profile-multi-instance-default.md)에서 `local`과 `production`의 공유 세션·Pub/Sub·전송 제한 계약을 확정해 `계약 준비 완료`가 됐다.

### 계약과 구현을 같은 이슈·PR에서 처리할 때

`선행 계약 필요`는 별도 계약 이슈나 PR을 반드시 먼저 만들라는 뜻이 아니다. 하나의 기능 이슈·PR에서 계약 확정과 구현을 함께 처리할 수 있으며 다음 순서를 지킨다.

1. 작업 시작 시 이슈·PR 설명에 남은 계약과 선택할 항목을 적는다.
2. 관련 생산 코드나 스키마를 작성하기 전에 선택값과 근거를 API·ERD·아키텍처·ADR·운영 가이드 중 해당 정본에 먼저 반영한다.
3. 저장 구조를 바꾸면 같은 PR에서 전진 Flyway 마이그레이션, JPA Entity와 ERD를 일치시킨다. 별도 계약 PR은 요구하지 않는다.
4. 승인된 범위 안의 구현 세부값은 구현 담당자가 확정한다. 제품 정책, 되돌리기 어려운 ADR 결정, 서비스 중단·트래픽 차단처럼 별도 권한이 필요한 선택은 구현 전에 사용자에게 확인한다.
5. 상태표는 작업 중간 상태를 표시하지 않고 PR이 병합될 때 계약·코드·검증·운영 증거의 최종 결과에 맞춰 해당 행을 한 번에 갱신한다.

따라서 작업 브랜치의 AI는 상태표만 보고 같은 PR의 구현을 계속 차단하지 않는다. 1~2단계의 기록과 정본 diff가 실제로 존재하는지 확인해 착수 gate 해소 여부를 판정하며, 둘 중 하나라도 없으면 구현을 멈추고 남은 계약부터 확정한다.

[P0 문서](../archive/p0/README.md)는 `v0.1.0` 완료 시점 기록으로 동결되어 있으며 P1 구현 작업의 진입점으로 사용하지 않는다. P1도 완료되면 같은 구조로 아카이브한다.
