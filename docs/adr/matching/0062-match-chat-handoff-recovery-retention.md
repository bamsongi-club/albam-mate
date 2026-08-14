# ADR-0062: MATCH 전용 채팅 handoff·복구와 최소 보관

- 상태: 승인됨
- 작성일: 2026-08-14
- 결정일: 2026-08-14
- 관련: [MATCH-01 기술 계약 이슈 #737](https://github.com/bamsongi-club/albam-mate/issues/737), [MATCH-01 성공 파티 채팅 규칙](../../p2/matching.md#성공-파티-채팅), [MATCH-01 신고와 차단 규칙](../../p2/matching.md#신고와-차단), [P2 기능 상태](../../p2/README.md#기능별-현재-상태), [API 명세](../../API.md), [ERD](../../ERD.md), [아키텍처](../../ARCHITECTURE.md), [ADR-0049 ROOM 채팅 보관·삭제](../chat/0049-chat-message-retention-lock-section-boundary.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

MATCH-01의 성공 파티는 기존 ROOM·참가 상태나 별도 온라인 ROOM으로 표현하지 않는다. 매칭이 전원 수락을 확정한 뒤에만 성공 파티와 참가자 접근 관계가 생기며, chat은 그 MATCH 전용 채팅방의 메시지 저장과 실시간 전달만 담당한다. 기존 P1 ROOM 채팅의 의미·경로·30일 보관을 재사용하면 ROOM 접근 근거와 MATCH 접근 근거가 섞이고, MATCH의 5분 준비 실패와 CLOSED 뒤 7일 물리 삭제를 만족할 수 없다.

채팅 생성은 matching의 전원 수락 확정 뒤 시작하므로 애플리케이션 재시작·재시도에서 같은 성공 파티의 채팅을 두 번 만들지 않아야 한다. 준비가 실패하면 성공 파티·접근 관계·부분 채팅을 남긴 채 요청만 재대기시키면 접근 권한과 실제 채팅 상태가 어긋난다.

컴파일 의존은 `chat → matching.contract`만 허용하므로 matching이 chat Entity·Repository를 직접 삭제할 수 없다. 그렇다고 party/access만 먼저 삭제하면 MATCH chat room·message·link가 고아로 남는다. 따라서 cleanup도 `matching.contract`가 소유한 `partyId` 기반 port로 호출하고, chat 구현이 호출한 matching Recovery/Cleanup Executor의 DB 트랜잭션에 참여해야 party/access lifecycle과 함께 하나의 결과로 수렴한다.

MVP 신고는 운영자 검토·자동 제재·이의제기와 자유 입력·첨부를 제공하지 않는 비공개 접수다. 같은 신고자와 피신고자 조합의 반복 raw record를 축적해도 MVP에서 처리할 운영 결과가 없으며, 불필요한 개인정보 보관만 늘어난다. 판단 기준은 MATCH와 ROOM의 책임 분리, 재시작 뒤 하나의 최종 상태 수렴, 실패 시 접근 흔적 제거, 사용자에게 보이지 않는 최소 기간 보관, 그리고 불필요한 신고 원자료의 비축 방지다.

## 검토한 대안

### 채팅 handoff와 복구

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 성공 파티를 기존 ROOM·참가와 기존 ROOM 채팅 경로로 표현 | 기존 기능을 재사용하는 것처럼 보인다. | ROOM의 접근 의미·경로·30일 보관을 MATCH에 섞고 5분 PREPARING 복구와 7일 삭제 책임이 불명확해진다. | 제외 |
| matching이 party·접근 관계와 chat 메시지 저장·전달까지 모두 소유 | handoff 호출이 줄어든다. | chat 도메인의 메시지 저장·실시간 전달 책임을 중복하고 채팅 복구·보관 변경이 매칭 모델에 결합된다. | 제외 |
| matching이 chat Entity·Repository를 직접 삭제 | 호출 구조가 짧아 보인다. | `matching → chat` 직접 의존과 소유 경계를 만들고, 구조 검사에서 금지한 순환 위험을 되살린다. | 제외 |
| matching이 party·참가자 접근 관계와 PREPARING 복구를 소유하고, `matching.contract`의 provision·cleanup port를 통해 chat이 MATCH 전용 채팅의 메시지 저장·실시간 전달·삭제를 수행 | 성공 확정과 메시지 기능의 책임을 분리하면서, 영속 PREPARING 상태의 재기동 복구와 고아 chat 없는 삭제 수렴을 이어 갈 수 있다. | 도메인 간 멱등 handoff·cleanup과 동일 DB 트랜잭션 참여를 구현·검증해야 한다. | 선택 |

### 신고 원자료

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 동일 조합의 신고를 매번 raw record로 추가 | 반복 발생 횟수를 나중에 볼 수 있다. | MVP에는 운영 검토·제재가 없고 자유 입력도 없으므로 사용자의 신고 관계·사유를 불필요하게 더 오래·더 많이 축적한다. | 제외 |
| 신고를 저장하지 않고 즉시 폐기 | 개인정보 보관이 가장 적다. | 고정 사유의 비공개 접수와 7일 보관이라는 안전 범위를 수행할 수 없다. | 제외 |
| 신고자·피신고자 조합별 7일 동안 하나의 고정 사유 raw record만 보관 | 최소한의 접수 사실을 유지하면서 반복 원자료를 늘리지 않는다. | 반복 신고의 횟수·최신 시각·별도 사유를 운영 데이터로 얻지 못한다. | 선택 |

## 결정

1. matching은 성공 파티와 참가자 접근 관계의 정본·생성·삭제를 소유한다. chat은 MATCH 전용 채팅방의 메시지 저장과 실시간 전달만 소유한다. 기존 ROOM 채팅의 업무 의미·접근 경로·30일 보관 정책은 MATCH에 재사용하지 않는다.
2. 전원 수락이 확정되면 matching은 성공 파티와 모든 참가자 접근 관계를 한 번만 원자적으로 확정하고 CHAT PREPARING 상태를 기록한다. 이 확정 전에는 MATCH 채팅 접근 권한을 주지 않는다. chat 생성의 source 관계에는 하나의 성공 파티만 연결되도록 유니크한 멱등 관계를 두어 같은 party에서 채팅을 중복 생성하지 않는다.
3. PREPARING의 5분은 전원 수락으로 party·접근 관계를 확정한 시각부터 계산한다. 재시작 뒤에도 영속 PREPARING을 다시 조회해, 이미 생성된 MATCH 채팅이 있으면 그것을 재사용해 ACTIVE로 수렴하고 없으면 같은 source 관계로 생성을 재개한다. 재시도는 새 party·새 접근 관계·새 채팅을 만들지 않는다.
4. `matching.contract`는 `MatchChatProvisionPort`와 `MatchChatCleanupPort`를 공개하고 chat이 구현한다. `MatchChatCleanupPort`는 partyId별 MATCH chat message·link를 먼저, room을 마지막에 멱등 정리하며, 호출한 matching Recovery/Cleanup Executor의 DB 트랜잭션에 참여하고 별도 커밋·독립 트랜잭션을 만들지 않는다. matching은 이 port만 호출하고 chat Entity·Repository를 직접 참조하지 않는다.
5. 5분 안에 chat이 ACTIVE로 수렴하지 않으면 matching은 하나의 `REQUIRES_NEW`에서 실패 복구를 시작한다. `MatchChatCleanupPort`가 정리를 완료한 뒤에만 성공 party·참가자 접근 관계를 물리 삭제하고, 연결된 모든 매칭 요청을 기존 queuedAt·prioritySince를 유지한 WAITING으로 자동 복귀시킨다. port 정리와 이후 lifecycle 변경은 함께 커밋하거나 롤백하므로 부분 삭제만 남긴 채 ACTIVE 또는 재대기 완료를 보고해서는 안 된다.
6. 정상적으로 ACTIVE였던 MATCH 채팅이 CLOSED가 되면 조회·전송·실시간 구독을 즉시 차단한다. 실제 CLOSED 시각부터 7일 동안 메시지·외부 링크·성공 party·참가자 접근 관계를 사용자에게 비공개로 보관하고, 7일 뒤 하나의 `REQUIRES_NEW`에서 `MatchChatCleanupPort` 완료 후 party·access를 물리 삭제한다. 이 삭제는 P1 ROOM 채팅의 30일 보관·삭제 결정을 바꾸지 않는다.
7. 신고 사유는 괴롭힘·욕설, 혐오·차별, 성적 부적절, 스팸·사기, 기타 규정 위반의 다섯 고정값만 허용한다. 자유 입력과 첨부는 받지 않는다.
8. 신고자와 피신고자 조합마다 접수 시각부터 7일 동안 raw record는 하나만 보관한다. 같은 조합의 반복 신고는 새 raw record, 반복 횟수, 최신 접수 시각을 추가하지 않으며 기존 접수 상태를 반환한다. 이는 개인정보 최소화와 MVP에 운영 검토·자동 제재가 없다는 결정에 따른 것이다. 신고 사실·사유·기록은 일반 사용자에게 공개하지 않으며, 7일이 지나면 사유를 포함한 raw record를 물리 삭제한다.

## 결과

- 얻는 것:
    - 성공 확정 전 접근 권한을 막고, matching의 party/access 정본과 chat의 메시지·전달 책임을 분리한다.
    - `MatchChatCleanupPort`가 같은 DB 트랜잭션에 참여해 재시작·재시도와 PREPARING 실패·CLOSED purge에서 중복 채팅·고아 chat/access·부분 성공을 남기지 않는 수렴 경계를 둔다.
    - ROOM 채팅의 30일 보관을 건드리지 않고 MATCH 종료 데이터와 신고 원자료를 최소 기간만 보관한다.
- 감수할 비용·위험:
    - matching과 chat 사이에 source 관계, 생성 재시도, cleanup 완료와 호출자 트랜잭션 참여를 위한 멱등 계약이 필요하다.
    - 7일 물리 삭제 뒤에는 메시지·외부 링크·party/access·신고 원자료를 복구할 수 없고, 반복 신고의 운영 통계도 남기지 않는다.
- 후속 작업:
    - #737은 MATCH party/access, MATCH 전용 chat, 신고·차단의 API·ERD·ARCH·ADR 계약 반영까지만 소유한다. 생산 코드·Flyway·배포·테스트·측정은 후속 구현 이슈에서 다룬다.
    - 후속 구현에서 `MatchChatCleanupPort`의 동일 DB 트랜잭션 참여와 PREPARING 재시작·부분 생성·5분 실패 정리·CLOSED 7일 purge·신고 조합 중복의 PostgreSQL 통합 테스트와 운영 측정 증거를 추가한다.

## 적용·호환·rollback

- 적용: #737 범위에서 MATCH chat·party/access·신고의 API·ERD·ARCH·ADR 계약만 반영한다. 후속 구현은 party/access 확정, PREPARING source 관계, `MatchChatCleanupPort`를 통한 chat 생성·삭제, 요청 WAITING 복귀의 상태를 영속화하고, 모든 사용자 조회가 MATCH 전용 접근 관계를 확인하도록 해야 한다. 생산 코드와 Flyway 마이그레이션은 아직 없다.
- 호환: P1 ROOM·참가·ROOM chat의 상태·경로·30일 보관은 변경하지 않는다. 기존 chat의 메시지 저장·실시간 전달 기반을 기술적으로 사용할 수 있어도, ROOM 권한이나 ROOM 채팅방 식별을 MATCH 권한의 근거로 사용하지 않는다.
- runtime rollback: PREPARING 실패 복구는 접근 차단과 부분 chat·party/access 삭제, 요청 WAITING 복귀가 모두 수렴할 때까지 영속 상태에서 재시도한다. CLOSED 뒤 7일 purge는 의도적으로 물리 삭제하므로 완료된 삭제를 되돌리는 복구 경로는 제공하지 않는다.
- 배포 rollback: 아직 구현·마이그레이션·배포 증거가 없으므로 운영 rollback 절차도 미검증이다. 구현 배포 전에는 PREPARING과 ACTIVE를 안전하게 종료·복구하는 절차, purge 실행 전 대상 검증, 이전 버전 복귀 때의 접근 차단을 검증해야 하며, 삭제 기한 전에 ROOM 데이터와 섞어 복구해서는 안 된다.

## 보류 및 재검토

- 지금 하지 않는 것: 기존 ROOM 채팅 재사용, MATCH 종료 채팅의 읽기 전용 제공, 신고 자유 입력·첨부, 관리자 검토·자동 제재·이의제기, 신고 반복 횟수·시각의 축적
- 보류 이유: MATCH의 접근 관계는 ROOM과 다르고, MVP는 접수 이외의 신고 운영을 제공하지 않으며, 더 많은 raw 신고 데이터를 보관할 정당한 업무 목적이 없다.
- 다시 검토할 조건: MATCH chat의 생성·삭제가 재시작 뒤 수렴하지 않을 때, 5분 실패 복구가 고아 party/access 또는 부분 chat을 남길 때, 7일 purge가 기한·비공개 요구를 지키지 못할 때, 또는 운영 검토·제재·이의제기를 도입해 신고 데이터 최소 보관 전제를 바꿀 때

## 참고 자료

- [MATCH-01 성공 파티 채팅 규칙](../../p2/matching.md#성공-파티-채팅)
- [MATCH-01 신고와 차단 규칙](../../p2/matching.md#신고와-차단)
- [ADR-0049: 최종 상태 ROOM 채팅 메시지 보관·삭제와 잠금 구간 실행 경계](../chat/0049-chat-message-retention-lock-section-boundary.md)
- [MATCH-01 기술 계약 이슈 #737](https://github.com/bamsongi-club/albam-mate/issues/737)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - #737은 계약 반영만 수행하며, 이를 실행하는 MATCH party/access, MATCH 전용 chat, 신고 raw record의 생산 코드·Flyway·배포는 후속이다.
    - 전원 수락의 원자 확정, PREPARING 재기동 복구와 중복 채팅 방지, `MatchChatCleanupPort`와 동일 DB 트랜잭션의 5분 실패 정리·WAITING 복귀·CLOSED 7일 purge를 재현하는 PostgreSQL 통합 테스트가 없다.
    - 신고자·피신고자 조합의 7일 단일 record와 사유 삭제, 운영 측정 결과가 없다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
