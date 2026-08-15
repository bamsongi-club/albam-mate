# ADR-0064: MATCH 채팅 URL 텍스트를 메시지 본문에만 저장

- 상태: 승인됨
- 작성일: 2026-08-15
- 결정일: 2026-08-15
- 관련: [MATCH-01 기술 계약 이슈 #737](https://github.com/bamsongi-club/albam-mate/issues/737), [MATCH-01 성공 파티 채팅](../../p2/matching.md#성공-파티-채팅), [API 명세](../../API.md), [ERD](../../ERD.md), [아키텍처](../../ARCHITECTURE.md), [ADR-0062](0062-match-chat-handoff-recovery-retention.md)
- 대체 대상: [ADR-0062](0062-match-chat-handoff-recovery-retention.md)의 별도 MATCH chat link 표현·보관·cleanup 범위
- 후속 ADR: 없음

## 맥락

ADR-0062는 MATCH chat cleanup과 7일 보관을 설명하면서 `message·link`와 `외부 링크`를 별도 대상으로 표현했다. 반면 이미 승인된 활성 API·ERD·아키텍처·MATCH-01 제품 계약은 외부 URL을 메시지 `content`의 일반 텍스트로만 공유하고, 별도 link 식별자·행·API·미리보기·유효성 검증을 두지 않는다고 정한다.

승인된 ADR의 결정 본문은 사후에 고칠 수 없다. 기존 표현을 그대로 두면 구현자가 별도 `MATCH_CHAT_LINKS` 저장소를 추가하거나, 반대로 승인 ADR의 cleanup 범위를 무시해야 한다. 이 ADR은 기존 MATCH chat handoff·재기동 복구·7일 보관 결정은 유지하면서 URL 표현과 cleanup 대상을 현재 계약 하나로 수렴시키기 위한 부분 대체다.

판단 기준은 URL 공유를 MVP의 일반 메시지 흐름 안에 두는 단순성, message·room cleanup의 단일 저장 경계, 별도 link metadata를 위한 API·보안·보존 책임을 만들지 않는 개인정보 최소화다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| `MATCH_CHAT_LINKS`와 별도 link API·식별자를 도입 | 링크별 미리보기·검사·개별 보존을 나중에 붙이기 쉽다. | 현재 MVP 범위를 넓히고, 새 접근·입력 검증·보존·cleanup 경계와 URL metadata 보관을 결정해야 한다. | 제외 |
| ADR-0062의 본문을 URL 텍스트 의미로 직접 정정 | 파일 수가 늘지 않는다. | 승인된 ADR의 결정 이력을 사후 변경해 당시 결정과 현재 계약의 경계를 잃는다. | 제외 |
| URL을 메시지 `content`에만 저장하고, ADR-0062의 해당 범위를 후속 ADR로 부분 대체 | 기존 메시지 저장·전달·7일 보관 경계를 그대로 사용하며 구현자가 하나의 저장 계약만 따른다. | 링크별 기능이 필요해지면 별도 데이터·API·보안 결정을 다시 해야 한다. | 선택 |

## 결정

1. MATCH 외부 URL은 정규화된 `MATCH_CHAT_MESSAGES.content`의 일반 텍스트 일부로만 저장한다. URL은 별도 업무 리소스나 접근 권한의 근거가 아니다.
2. `MATCH_CHAT_LINKS` 테이블, link ID, 별도 등록·조회 API, 미리보기, URL 유효성 검사와 독립 보존 레코드는 만들지 않는다.
3. URL 텍스트의 보관과 삭제는 포함된 메시지의 lifecycle을 따른다. `MATCH_CHAT_MESSAGES`를 정리하고 `MATCH_CHAT_ROOMS`를 마지막에 정리하는 기존 MATCH chat cleanup 순서가 URL 텍스트에도 그대로 적용된다.
4. 이 ADR은 ADR-0062의 `message·link`·`외부 링크` 표현이 암시한 별도 link 저장·보관·cleanup 범위만 대체한다. MATCH party/access 소유권, chat handoff, `PREPARING` 복구, `CLOSED` 뒤 7일 보관과 신고 최소 보관 결정은 ADR-0062를 계속 따른다.
5. 외부 HTTP 표현은 [API 명세](../../API.md), 물리 저장·cascade 순서는 [ERD](../../ERD.md), 모듈 cleanup 순서는 [아키텍처](../../ARCHITECTURE.md)가 각각 정본이다. 이 ADR은 그 표현을 선택한 이유와 부분 대체 관계만 소유한다.

## 결과

- 얻는 것:
    - URL이 포함된 메시지와 일반 메시지가 동일한 저장·전달·7일 보관 경계를 따른다.
    - 구현자는 별도 link 행이나 orphan cleanup을 만들지 않고 message·room 정리만 수행한다.
    - 승인 ADR의 이력은 보존하면서 현재 구현 계약의 충돌을 제거한다.
- 감수할 비용·위험:
    - URL별 검색, 미리보기, 안전성 검사, 개별 삭제와 별도 분석은 MVP에 제공하지 않는다.
    - 메시지가 삭제되면 그 안의 URL 텍스트도 함께 사라져 독립 복구할 수 없다.
- 후속 작업:
    - 후속 MATCH 구현은 `MATCH_CHAT_MESSAGES.content`만 저장하고, URL 텍스트를 metric label·일반 로그에 남기지 않는 계약을 PostgreSQL 통합 검증으로 확인한다.

## 적용·호환·rollback

- 적용: #741에서는 활성 API·ERD·아키텍처·MATCH 명세를 이 ADR에 연결하고, ADR-0062의 부분 대체 관계와 Matching ADR 인덱스를 갱신한다. 생산 코드·Flyway는 아직 없다.
- 호환: P1 ROOM 채팅의 URL 텍스트 처리·메시지 30일 보관·경로는 바꾸지 않는다. MATCH의 URL 텍스트를 ROOM 채팅 link 기능으로 재사용하지 않는다.
- runtime rollback: URL 텍스트는 메시지 lifecycle과 분리된 상태가 없으므로 cleanup 실패는 message·room cleanup 전체를 롤백한다. 완료된 7일 물리 삭제를 되돌리는 복구 경로는 제공하지 않는다.
- 배포 rollback: 아직 배포할 schema나 endpoint가 없다. 후속 구현은 별도 link table을 만들지 않은 상태에서 forward migration과 message cleanup을 검증해야 한다.

## 보류 및 재검토

- 지금 하지 않는 것: URL별 link metadata, 미리보기, 안전성 검사, 검색, 분석, 개별 보존·삭제
- 보류 이유: MVP에는 URL을 메시지 밖에서 다룰 사용자·운영 기능이 없고, 별도 저장은 접근·개인정보·보존 책임만 늘린다.
- 다시 검토할 조건: URL별 기능이나 별도 보존·운영 검토가 필요해지거나, 메시지 본문에 URL을 저장하는 방식이 보안·개인정보·성능 요구를 만족하지 못할 때

## 참고 자료

- [MATCH-01 성공 파티 채팅](../../p2/matching.md#성공-파티-채팅)
- [MATCH 채팅 메시지 전송 API](../../API.md#match-01-매칭-채팅-메시지-전송)
- [P2 MATCH 저장 lifecycle](../../ERD.md#p2-match-저장-lifecycle)
- [P2 MATCH 모듈 계약](../../ARCHITECTURE.md#p2-match-모듈-계약-계획미구현)
- [ADR-0062: MATCH 전용 채팅 handoff·복구와 최소 보관](0062-match-chat-handoff-recovery-retention.md)

## 검증

- 상태: 미검증
- 근거:
    - 계약: API·ERD·아키텍처·MATCH-01 제품 명세가 URL을 메시지 `content`로만 다루는 현재 계약을 각 책임 범위에서 정의한다.
- 미검증:
    - URL 텍스트만 저장하는 MATCH chat 생산 코드·Flyway·PostgreSQL 통합 테스트·보존 삭제 증거가 아직 없다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
