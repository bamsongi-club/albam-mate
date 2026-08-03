# ADR-0041: 채팅방 스키마 생성과 기존 ROOM backfill 실행 경계 분리

- 상태: 승인됨
- 작성일: 2026-08-03
- 결정일: 2026-08-03
- 관련: [CHAT-01 P1 방 채팅 명세](../../p1/chatting.md), [CHAT_ROOMS 저장 계약](../../ERD.md#chat_rooms), [#279 스키마 생성](https://github.com/bamsongi-club/albam-mate/issues/279), [#281 backfill·경합 검증](https://github.com/bamsongi-club/albam-mate/issues/281), [ADR-0034](0034-chat-message-retention-and-deletion.md)
- 대체 대상: ADR-0034의 기존 ROOM 초기화 실행 경계
- 후속 ADR: 없음

## 맥락

채팅방 스키마 생성과 기존 ROOM 데이터 초기화는 같은 Flyway 실행에 넣기 어렵다. 스키마 마이그레이션은 애플리케이션 기동 때 자동으로 실행되지만, 기존 ROOM을 한 번 조회해 생성하는 데이터 작업은 ROOM 생성·상태 전환과 같은 쓰기 경계, 초기화 기준 시각, 최종 보정과 배포 절체를 함께 결정해야 한다.

기존 ROOM backfill을 `INSERT ... SELECT` 한 문장으로 전진 Flyway에 넣으면 그 문장의 스냅샷 뒤에 커밋된 ROOM 생성이나 상태 전환을 자동으로 보정하지 못한다. 반대로 일반 기동과 데이터 초기화를 분리하면 스키마는 먼저 안전하게 준비하고, 운영자가 정한 경계에서 기존 데이터 작업을 별도로 검증할 수 있다.

이번 결정의 판단 기준은 다음과 같다.

- 일반 애플리케이션 기동의 스키마 변경이 live ROOM 데이터 누락을 만들지 않을 것
- 기존 데이터 작업의 쓰기 경계·초기화 기준 시각·최종 보정·검증을 한 완료 경계로 관찰할 것
- 실패한 backfill을 일반 애플리케이션 재기동으로 암묵적으로 재실행하지 않을 것
- 기존 최종 상태 ROOM의 보관 정책은 [ADR-0034](0034-chat-message-retention-and-deletion.md)의 30일 보관 결정과 일치할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| V6 스키마와 기존 ROOM backfill을 하나의 Flyway에 포함 | 배포 단계가 짧고 별도 명령이 없다. | 기동 스냅샷이 ROOM 쓰기와 경합하고, 누락·최종 보정·운영 절체를 자동으로 보장하지 못한다. | 제외 |
| 스키마와 backfill을 서로 다른 자동 Flyway 버전으로 분리 | SQL 파일과 버전 이력이 분리된다. | 두 번째 버전도 애플리케이션 기동에서 자동 실행되므로 live 데이터 쓰기 경계 문제가 남는다. | 제외 |
| V6 스키마 전용 + 명시적 one-shot/maintenance backfill | 스키마 준비와 데이터 절체를 분리하고, 실행 전후 검증과 운영 승인을 기록할 수 있다. | 별도 실행기·경계·재처리 절차와 운영 증거가 필요하다. | 선택 |
| ROOM 생성 시 누락 채팅방을 지연 생성 | backfill 작업을 줄일 수 있다. | 첫 접근·상태 전환과 데이터 생성이 결합되고, 기존 ROOM 전체 준비 완료를 명확히 증명하기 어렵다. | 제외 |

## 결정

1. `V6__create_p1_chat_room_schema.sql`은 `CHAT_ROOMS` 테이블·FK·유일 제약·보관 완료 CHECK 등 스키마만 생성한다. 기존 `ROOMS`를 조회하거나 `CHAT_ROOMS` 행을 삽입·갱신하지 않는다.
2. 기존 ROOM의 채팅방 생성과 상태별 보관 초기화는 [#281](https://github.com/bamsongi-club/albam-mate/issues/281)이 소유하는 명시적 `chat-room-backfill` one-shot/maintenance 작업으로 수행한다. 일반 애플리케이션 기동이나 Flyway 자동 실행 경로는 이 작업을 호출하지 않는다.
3. #281 작업은 ROOM 생성·상태 전환과 동시에 커밋될 수 없는 통제 경계를 먼저 확보하고, 하나의 초기화 기준 시각으로 누락 행 생성·최종 보정·1:1 검증을 완료해야 한다. 애플리케이션 쓰기 게이트와 PostgreSQL 직렬화 중 구체적인 선택, 실패 단위와 배포 절체 방식은 #281의 승인된 후속 구현 계약으로 확정한다.
4. 서비스 중단·트래픽 차단·rolling 배포 제약처럼 별도 운영 권한이 필요한 절체는 이 ADR이 승인하지 않는다. 필요하면 사용자·OPS 승인을 별도로 기록한다.
5. `CANCELED`·`FINISHED` ROOM의 빈 보관 완료와 이후 최종 상태 보관·삭제 정책은 ADR-0034의 retention 결정을 그대로 따른다. 이 ADR은 보관 기간이나 메시지 삭제 정책을 바꾸지 않는다.

## 결과

- 얻는 것:
    - 애플리케이션 기동 Flyway가 live ROOM 데이터에 의존하지 않고 스키마만 준비한다.
    - 기존 데이터 초기화의 기준 시각·경합·최종 보정·검증을 명시적인 완료 경계로 운영할 수 있다.
    - backfill 실패를 스키마 배포 성공과 분리해 관찰하고 재처리할 수 있다.
- 감수할 비용·위험:
    - 채팅 공개 활성화 전에 #281 one-shot 실행과 절체 증거가 필요하다.
    - 실행 경계와 실패 단위를 운영하고 PostgreSQL에서 검증할 별도 코드·절차가 필요하다.
- 후속 작업:
    - #281에서 쓰기 게이트 또는 PostgreSQL 직렬화, 최종 보정, 실패 단위와 재처리 결과를 확정·구현한다.
    - #280 신규 방 연계와 기존 ROOM backfill 완료를 채팅 공개 활성화 순서에 반영한다.

## 보류 및 재검토

- 지금 하지 않는 것: 이번 스키마 PR의 자동 backfill, 특정 PostgreSQL 잠금 모드의 승인, 서비스 중단·트래픽 차단의 승인
- 보류 이유: 실행 환경·운영 권한·ROOM 쓰기 경계를 확인하지 않은 상태에서 구현 세부를 정본으로 고정하지 않는다.
- 다시 검토할 조건: #281의 PostgreSQL 경합 재현과 운영 절체 선택이 승인되거나, 지연 생성이 기존 ROOM 준비·접근 계약을 더 안전하게 충족한다는 근거가 생길 때

## 참고 자료

- [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [PostgreSQL Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)

## 검증

- 상태: 미검증
- 근거:
    - 계약: #279 승인 코멘트가 V6 스키마 전용 T1~T4를 확정하고, #281 이슈가 기존 ROOM backfill·경합·절체의 별도 소유 경계를 확정한다.
- 미검증:
    - #281의 one-shot 구현·ROOM 쓰기 경계·최종 보정·배포 절체는 구현·PostgreSQL 테스트·운영 증거가 아직 없다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
