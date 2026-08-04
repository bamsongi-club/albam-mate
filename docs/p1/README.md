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

기능 구현은 해당 기능 문서의 완료 기준과 현재 [API](../API.md), [ERD](../ERD.md), 관련 승인 ADR을 함께 충족해야 한다. API에 적은 P1 인터페이스는 구현 예정 계약이며, 코드·ERD·아키텍처와 필요한 ADR에 반영되고 검증되기 전에는 현재 제공 기능으로 보지 않는다.

P1 저장 계약의 준비 상태는 기능별로 다르다. 알림 저장 계약, 승인된 PART-04 대기열 저장 계약과 P1 채팅 저장 구조 및 [ADR-0038](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md)의 ShedLock 구조는 [ERD](../ERD.md)에 구현 예정 계약으로 반영됐고, 기능 전체의 계약 준비 여부는 아래 표의 `계약 준비` 열을 따른다. 구현 작업은 준비된 저장 계약도 전진 Flyway 마이그레이션과 생산 코드로 구현해야 하며, `선행 계약 필요`인 기능의 미확정 문서 후보를 그대로 물리 저장 계약으로 사용하지 않는다. 다만 같은 이슈·PR에서 아래 단일 이슈·PR 규칙의 1~2단계를 마쳐 선택한 계약을 정본에 반영했다면, 상태표의 중간 갱신 없이 그 계약으로 구현을 계속할 수 있다.

## 기능별 현재 상태

이 표는 P1 필수 기능과 기반 작업 ID별 계약 준비·생산 코드·자동 검증·운영 배포와 실측 상태의 정본이다. 상태를 바꾸는 계약·코드·테스트·배포 변경은 같은 변경에서 해당 행만 갱신한다. 기능 문서와 API·ERD·아키텍처에는 변하지 않는 단계·책임·완료 기준만 두고 현재 상태를 반복하지 않는다. 조건부 후속인 ID는 P1 필수 범위에 채택되기 전까지 이 표에 넣지 않는다.

| 기능 ID | 계약 준비 | 생산 코드 | 자동 검증 | 운영 배포·실측 |
| --- | --- | --- | --- | --- |
| [`AUTH-05`](social-login.md#auth-05-소셜-로그인계정-연결) | 계약 준비 완료 | 부분 구현 ([#331](https://github.com/bamsongi-club/albam-mate/issues/331), [#333](https://github.com/bamsongi-club/albam-mate/issues/333)) | 부분 검증 ([#331](https://github.com/bamsongi-club/albam-mate/issues/331), [#333](https://github.com/bamsongi-club/albam-mate/issues/333)) | 미배포·미측정 |
| [`SEARCH-01`](search.md#search-01-게임-조건-검색) | 계약 준비 완료 | 구현 완료 ([#293](https://github.com/bamsongi-club/albam-mate/issues/293), [#295](https://github.com/bamsongi-club/albam-mate/issues/295), [#348](https://github.com/bamsongi-club/albam-mate/issues/348), [#351](https://github.com/bamsongi-club/albam-mate/issues/351)) | 검증 완료 ([#293](https://github.com/bamsongi-club/albam-mate/issues/293), [#295](https://github.com/bamsongi-club/albam-mate/issues/295), [#348](https://github.com/bamsongi-club/albam-mate/issues/348), [#351](https://github.com/bamsongi-club/albam-mate/issues/351)) | 미배포·미측정 |
| [`SEARCH-02`](search.md#search-02-방-조건-검색) | 선행 계약 필요 | 미구현 | 미검증 | 미배포·미측정 |
| [`SEARCH-03`](search.md#search-03-사용자별-해-본-게임) | 계약 준비 완료 | 미구현 | 미검증 | 미배포·미측정 |
| [`ROOM-08`](room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리) | 계약 준비 완료 | 미구현 | 미검증 | 미배포·미측정 |
| [`PART-04`](room.md#part-04-선착순-대기열과-자동-승격) | 계약 준비 완료 | 미구현 | 미검증 | 미배포·미측정 |
| [`ROOM-09`](room.md#room-09-시간-기반-room-상태-자동-전환의-대량-처리-고도화) | 선행 계약 필요 | 미구현 | 미검증 | 미배포·미측정 |
| [`ROOM-10`](room.md#room-10-동시성과-락-전략-실증) | 선행 계약 필요 | 미구현 | 미검증 | 미배포·미측정 |
| [`NOTI-01`](notification.md#noti-01-모임-변경-알림-생성) | 계약 준비 완료 | 미구현 | 미검증 | 미배포·미측정 |
| [`NOTI-02`](notification.md#noti-02-내-알림-목록미확인-개수) | 계약 준비 완료 | 미구현 | 미검증 | 미배포·미측정 |
| [`NOTI-03`](notification.md#noti-03-알림-읽음-처리) | 계약 준비 완료 | 미구현 | 미검증 | 미배포·미측정 |
| [`CHAT-01`](chatting.md#chat-01-채팅방-생성접근) | 선행 계약 필요 | 부분 구현 ([#279](https://github.com/bamsongi-club/albam-mate/issues/279)) | 부분 검증 ([#279](https://github.com/bamsongi-club/albam-mate/issues/279)) | 미배포·미측정 |
| [`CHAT-02`](chatting.md#chat-02-메시지-전송이력-조회) | 계약 준비 완료 | 미구현 | 미검증 | 미배포·미측정 |
| [`CHAT-03`](chatting.md#chat-03-실시간-전달재연결-복구) | 선행 계약 필요 | 미구현 | 미검증 | 미배포·미측정 |
| [`CHAT-04`](chatting.md#chat-04-채팅-안전운영) | 선행 계약 필요 | 부분 구현 ([#289](https://github.com/bamsongi-club/albam-mate/issues/289)) | 부분 검증 ([#289](https://github.com/bamsongi-club/albam-mate/issues/289)) | 미배포·미측정 |
| [`CHAT-05`](chatting.md#chat-05-내-모임-채팅-진입) | 계약 준비 완료 | 미구현 | 미검증 | 미배포·미측정 |
| [`FND-09`](foundation.md#fnd-09-검색-성능과-인덱스-검증) | 선행 기능 계약 대기 | 미구현 | 미검증 | 미배포·미측정 |
| [`FND-10`](foundation.md#fnd-10-실시간-전달과-재연결-기반) | 선행 계약 필요 | 미구현 | 미검증 | 미배포·미측정 |

- `계약 준비 완료`: 기능 구현에 필요한 제품·API·저장·아키텍처 계약과 필수 ADR이 모두 반영·승인됐다. 생산 코드나 검증 완료를 뜻하지 않는다.
- `선행 계약 필요`: 기능 명세가 있더라도 필수 ADR 승인, ERD·아키텍처 반영 또는 `착수 전 확정`과 같은 구현 전 결정이 남아 있다.
- `선행 기능 계약 대기`: 기반 작업이 의존하는 기능 계약이 먼저 준비되어야 한다.
- `부분 구현`과 `부분 검증`은 해당 기능의 일부 구현 이슈만 생산 코드와 자동 검증을 갖춘 상태이며, 연결한 이슈가 그 증거 범위를 한정한다.
- `미구현`, `미검증`, `미배포·미측정`은 각각 요구된 생산 코드·기반 산출물, 자동 검증 증거, 실제 운영 배포·실측이 없음을 뜻한다. 한 상태의 완료를 다른 상태의 완료로 대신하지 않는다.

### 채팅의 선행 계약 사유

다음 표는 현재 상태표에서 `선행 계약 필요`인 채팅 기능의 미해결 선행 계약과 해소 위치를 한곳에 모은다. `CHAT-02`의 전송 제한 계약은 [#288 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930)과 [#372 정본 반영 이슈](https://github.com/bamsongi-club/albam-mate/issues/372)로 확정됐으므로 미해결 표에서 제외한다. `CHAT-04`의 전송 제한 계약도 같은 승인 범위에 포함되지만, 만료 삭제 운영 계약이 남아 기능 전체 상태는 `선행 계약 필요`로 유지한다. 실제 상태는 위 상태표만 갱신하며, 세부 선택을 마치면 선택값과 근거를 기능·API·ERD·아키텍처·실행 구성 중 표에 적은 위치에 반영한다. 승인 ADR의 결정 본문은 임의로 수정하지 않으며, 승인 경계를 바꿔야 하면 새 ADR로 대체한다.

| 기능 ID | 착수 전에 확정할 계약 | 결정 주체 | 위임 근거·반영 위치 |
| --- | --- | --- | --- |
| `CHAT-01` | 기존 ROOM의 채팅방 backfill과 ROOM 생성·상태 전환 경합을 막을 동시성 제어·최종 보정, 배포 절체 방식 | [#281](https://github.com/bamsongi-club/albam-mate/issues/281) 구현 담당자. 서비스 중단·트래픽 차단이 필요하면 사용자·OPS 승인 | [#279의 최신 승인 테스트 계약](https://github.com/bamsongi-club/albam-mate/issues/279#issuecomment-5161788285)에 따라 #279는 V6 스키마·제약만 소유한다. 승인된 [ADR-0045](../adr/chat/0045-chat-room-schema-and-backfill-boundary.md)는 production schema-only와 local callback 경계를 정하고, #281의 live 운영 backfill·경합·절체는 별도 범위로 남긴다. 선택 결과는 [CHAT-01](chatting.md#chat-01-채팅방-생성접근), [ERD](../ERD.md#chat_rooms), [채팅 흐름](../ARCHITECTURE.md#채팅-흐름)과 실행 작업에 반영 |
| `CHAT-03` | 세션 TTL·직렬화 방식과 정확한 Redis key·channel namespace | `FND-10`·`CHAT-03` 구현 담당자 | 위임 근거는 [ADR-0038 결정](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md#결정). 선택 결과는 [FND-10](foundation.md#fnd-10-실시간-전달과-재연결-기반), [다중 인스턴스 실행](../ARCHITECTURE.md#다중-인스턴스-실행)과 실행 구성에 반영 |
| `CHAT-04` | 채팅 만료 삭제 잠금 이름·`lockAtMostFor`·실행시간 경고 기준 | #289에서 확정 | 잠금 이름은 `chat-message-retention`이고, 로컬 PostgreSQL 방 50개·메시지 5,000개 대표 배치 126ms를 근거로 경고 1초·`lockAtMostFor` 5초를 [ADR-0034 결정](../adr/chat/0034-chat-message-retention-and-deletion.md#결정), [ADR-0038 결정](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md#결정)과 [CHAT-04](chatting.md#chat-04-채팅-안전운영)에 반영 |

`CHAT-05`는 제품·API·저장·아키텍처 계약과 필수 ADR에 남은 결정이 없어 `계약 준비 완료`다. 이는 `CHAT-01`~`CHAT-03`의 구현 없이 독립적으로 사용자 흐름을 완료할 수 있다는 뜻이 아니다.

`FND-10`은 `CHAT-03` 계약을 수동으로 기다리는 작업이 아니라 세션·Redis 구성값을 확정하고 실시간 기반을 구현하는 소유 작업이다. 따라서 별도 채팅 계약 이슈를 기다리는 `선행 기능 계약 대기`가 아니라, 같은 `FND-10` 이슈·PR에서 위 값을 먼저 확정하는 `선행 계약 필요`로 판정한다.

### 계약과 구현을 같은 이슈·PR에서 처리할 때

`선행 계약 필요`는 별도 계약 이슈나 PR을 반드시 먼저 만들라는 뜻이 아니다. 하나의 기능 이슈·PR에서 계약 확정과 구현을 함께 처리할 수 있으며 다음 순서를 지킨다.

1. 작업 시작 시 이슈·PR 설명에 남은 계약과 선택할 항목을 적는다.
2. 관련 생산 코드나 스키마를 작성하기 전에 선택값과 근거를 API·ERD·아키텍처·ADR·운영 가이드 중 해당 정본에 먼저 반영한다.
3. 저장 구조를 바꾸면 같은 PR에서 전진 Flyway 마이그레이션, JPA Entity와 ERD를 일치시킨다. 별도 계약 PR은 요구하지 않는다.
4. 승인된 범위 안의 구현 세부값은 구현 담당자가 확정한다. 제품 정책, 되돌리기 어려운 ADR 결정, 서비스 중단·트래픽 차단처럼 별도 권한이 필요한 선택은 구현 전에 사용자에게 확인한다.
5. 상태표는 작업 중간 상태를 표시하지 않고 PR이 병합될 때 계약·코드·검증·운영 증거의 최종 결과에 맞춰 해당 행을 한 번에 갱신한다.

따라서 작업 브랜치의 AI는 상태표만 보고 같은 PR의 구현을 계속 차단하지 않는다. 1~2단계의 기록과 정본 diff가 실제로 존재하는지 확인해 착수 gate 해소 여부를 판정하며, 둘 중 하나라도 없으면 구현을 멈추고 남은 계약부터 확정한다.

[P0 문서](../archive/p0/README.md)는 `v0.1.0` 완료 시점 기록으로 동결되어 있으며 P1 구현 작업의 진입점으로 사용하지 않는다. P1도 완료되면 같은 구조로 아카이브한다.
