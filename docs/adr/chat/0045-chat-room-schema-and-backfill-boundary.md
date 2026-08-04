# ADR-0045: 채팅방 스키마 생성과 기존 ROOM backfill 실행 경계 분리

- 상태: 승인됨
- 작성일: 2026-08-03
- 결정일: 2026-08-04
- 관련: [CHAT-01 P1 방 채팅 명세](../../p1/chatting.md), [CHAT_ROOMS 저장 계약](../../ERD.md#chat_rooms), [#279 스키마 생성](https://github.com/bamsongi-club/albam-mate/issues/279), [#281 backfill·경합 검증](https://github.com/bamsongi-club/albam-mate/issues/281), [ADR-0034](0034-chat-message-retention-and-deletion.md)
- 대체 대상: [ADR-0034](0034-chat-message-retention-and-deletion.md)의 기존 ROOM 초기화 실행 경계
- 후속 ADR: 없음

## 맥락

채팅방 스키마 생성과 기존 ROOM 데이터 초기화는 운영 프로필과 local 개발·검증 프로필의 실행 경계를 분리해야 한다. 공통 스키마 마이그레이션은 애플리케이션 기동 때 자동으로 실행하지만, 기존 ROOM을 한 번 조회해 생성하는 데이터 작업은 local profile의 Flyway callback에서만 수행한다. live 운영 backfill은 ROOM 생성·상태 전환과 같은 쓰기 경계, 초기화 기준 시각, 최종 보정과 배포 절체를 함께 결정하는 #281 범위로 남긴다.

기존 ROOM backfill을 공통 전진 Flyway에 넣으면 production 기동 스냅샷 뒤에 커밋된 ROOM 생성이나 상태 전환을 자동으로 보정하지 못한다. production은 스키마만 준비하고 local profile에서만 callback으로 초기화하면, local 검증 데이터는 복구하면서 live 운영 데이터 절체는 별도 경계에 남길 수 있다.

이번 결정의 판단 기준은 다음과 같다.

- 일반 애플리케이션 기동의 스키마 변경이 live ROOM 데이터 누락을 만들지 않을 것
- 기존 데이터 작업의 쓰기 경계·초기화 기준 시각·최종 보정·검증을 한 완료 경계로 관찰할 것
- 실패한 backfill을 일반 애플리케이션 재기동으로 암묵적으로 재실행하지 않을 것
- 기존 최종 상태 ROOM의 보관 정책은 [ADR-0034](0034-chat-message-retention-and-deletion.md)의 30일 보관 결정과 일치할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| V14 스키마와 기존 ROOM backfill을 하나의 공통 Flyway에 포함 | 배포 단계가 짧고 별도 명령이 없다. | production 기동 스냅샷이 ROOM 쓰기와 경합하고, 누락·최종 보정을 자동으로 보장하지 못한다. | 제외 |
| V14 스키마 전용 + local profile `afterMigrate.sql` callback backfill | local 검증 데이터는 자동으로 복구하면서 production 기동은 스키마만 준비한다. | local callback은 live 운영 backfill·절체를 대체하지 않으며, #281 운영 작업이 별도로 필요하다. | 선택 |
| V14 스키마 전용 + 명시적 one-shot/maintenance backfill만 사용 | live 절체 경계를 가장 명확하게 운영할 수 있다. | local 개발·검증 데이터 복구에도 별도 실행이 필요하다. | 보류 |
| ROOM 생성 시 누락 채팅방을 지연 생성 | backfill 작업을 줄일 수 있다. | 첫 접근·상태 전환과 데이터 생성이 결합되고, 기존 ROOM 전체 준비 완료를 명확히 증명하기 어렵다. | 제외 |

## 결정

1. `V14__create_p1_chat_retention_schema.sql`은 `SHEDLOCK` 테이블만 생성한다. 공통 Flyway는 기존 `ROOMS`를 조회하거나 `CHAT_ROOMS` 행을 삽입·갱신하지 않는다.
2. local profile은 `classpath:db/local`을 Flyway 위치로 포함하고, `afterMigrate.sql` callback이 기존 `CHAT_ROOMS`가 없는 ROOM만 상태별 보관 값으로 멱등 초기화한다. 이미 있는 `CHAT_ROOMS` 행은 보존한다.
3. production profile은 `db/local`을 로드하지 않는다. live 운영 ROOM의 채팅방 생성·상태별 초기화·ROOM 생성·상태 전환 경합·최종 보정·배포 절체는 [#281](https://github.com/bamsongi-club/albam-mate/issues/281)이 소유하는 별도 작업으로 수행한다.
4. 서비스 중단·트래픽 차단·rolling 배포 제약처럼 별도 운영 권한이 필요한 절체는 이 ADR이 승인하지 않는다. 필요하면 사용자·OPS 승인을 별도로 기록한다.
5. `CANCELED`·`FINISHED` ROOM의 빈 보관 완료와 이후 최종 상태 보관·삭제 정책은 ADR-0034의 retention 결정을 그대로 따른다. 이 ADR은 보관 기간이나 메시지 삭제 정책을 바꾸지 않는다.

## 결과

- 얻는 것:
    - production 애플리케이션 기동 Flyway가 live ROOM 데이터에 의존하지 않고 스키마만 준비한다.
    - local profile은 기존 ROOM과 local seed를 같은 callback 경계에서 복구할 수 있다.
    - live 운영 backfill 실패를 스키마 배포 성공과 분리해 관찰하고 재처리할 수 있다.
- 감수할 비용·위험:
    - 채팅 공개 활성화 전에 #281 live one-shot 실행과 절체 증거가 필요하다.
    - local callback은 production 데이터 준비 완료를 증명하지 않으며, PostgreSQL 운영 경계는 별도 검증이 필요하다.
- 후속 작업:
    - #281에서 쓰기 게이트 또는 PostgreSQL 직렬화, 최종 보정, 실패 단위와 재처리 결과를 확정·구현한다.
    - #280 신규 방 연계와 기존 ROOM backfill 완료를 채팅 공개 활성화 순서에 반영한다.

## 보류 및 재검토

- 지금 하지 않는 것: production 자동 backfill, #281 live one-shot의 잠금·최종 보정·절체, 서비스 중단·트래픽 차단의 승인
- 보류 이유: local callback은 개발·검증 데이터 복구 경계이며 live 운영 절체 계약을 대신하지 않는다.
- 다시 검토할 조건: #281의 PostgreSQL 경합 재현과 운영 절체 선택이 승인될 때

## 참고 자료

- [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [PostgreSQL Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)

## 검증

- 상태: 검증됨
- 근거:
    - 결정: PR #366의 수정 방향으로 사용자가 local callback backfill 대안을 선택했고, #289 구현 범위를 V14 schema-only와 local callback으로 확정했다.
    - 구현·테스트: V14 공통 migration의 ShedLock schema-only, local callback의 멱등 초기화·기존 행 보존·상태별 값과 lockAtLeastFor 동작을 Testcontainers PostgreSQL 11개 테스트로 확인했다.
    - 정적 검사: 문서 링크, Spotless, Convention 검사가 통과했다.
    - 범위: #281의 live 운영 backfill·ROOM 쓰기 경계·최종 보정·배포 절체는 이 ADR이 승인하지 않은 별도 운영 범위로 남긴다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
