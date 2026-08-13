# ADR-0057: 게임 카탈로그 운영 적재는 승인 청크 UPSERT로 시작하고 증분 파이프라인은 후속으로 설계

- 상태: 승인됨
- 작성일: 2026-08-13
- 결정일: 2026-08-13
- 관련: [GitHub Issue #643](https://github.com/bamsongi-club/albam-mate/issues/643), [GitHub Issue #621](https://github.com/bamsongi-club/albam-mate/issues/621), [게임 카탈로그 검수·적재 가이드](../../guides/GAME_CATALOG_IMPORT.md), [ADR-0015](0015-bgg-baseline-team-collected-game-list.md), [ADR-0050](0050-game-metadata-catalog-and-filters.md), [ADR-0051](../platform/0051-p1-self-managed-aws-infrastructure.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

Albam Mate는 승인된 게임 카탈로그 산출물을 운영 PostgreSQL에 반영해야 한다. 현재 카탈로그는 게임 본문과 메커니즘·카테고리·테마·인원 선호 관계를 함께 다루므로, 단순 CSV 복사만으로는 기존 내부 ID 보존, 외래 키 해석, 관계 적재 순서와 실패 시 롤백을 보장할 수 없다.

초기 전체 적재는 약 17만 행 규모다. [#621](https://github.com/bamsongi-club/albam-mate/issues/621)에서 전달본의 단일 대형 `INSERT`가 PostgreSQL 인스턴스 크래시를 일으킬 수 있고, 입력 데이터 기준·관계 충돌·일부 산출물 품질도 별도 확인이 필요하다는 사실이 확인됐다. 따라서 운영 적재 전에 데이터 정본과 승인 산출물을 확정하고, 실행 단위도 검증된 크기로 제한해야 한다.

이번 결정의 판단 기준은 다음과 같다.

- 현재 승인 산출물의 `bgg_id` 기반 `UPSERT`, 기존 내부 ID 보존, 관계 적재 순서와 롤백 계약을 유지할 것
- RDS와 self-managed PostgreSQL에서 같은 승인 산출물을 사용할 수 있을 것
- 산출물 버전·manifest·품질 보고서·checksum과 실행 전후 검증을 남길 것
- 운영 직전에 검증되지 않은 새로운 staging 파이프라인을 도입하지 않을 것
- 정기 갱신이 필요해질 때 초기 일회성 적재와 반복 적재의 설계 목표를 분리할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 승인 산출물을 `psql`로 실행하는 청크 `UPSERT` | 현재 생성기와 적재 가이드의 `bgg_id` 충돌 처리, 기존 ID 보존, 관계 순서와 롤백 계약을 그대로 사용할 수 있다. RDS·self-managed 모두 같은 SQL 산출물을 사용할 수 있다. | SQL 생성·전달량과 점검 시간이 필요하고, 행 단위 부분 실패 격리는 제공하지 않는다. | **초기 전체 적재로 선택** |
| S3에서 `COPY` 또는 `\copy`로 staging에 넣고 validation·merge | 대량 전송과 부분 검증·재처리에 유리하며 향후 반복 적재의 공통 구조가 될 수 있다. | staging 스키마, 정규화·검증·merge·cleanup과 복구 계약을 새로 구현하고 검증해야 한다. | **후속 목표로 보류** |
| RDS `aws_s3` 직접 적재 | RDS 안에서 S3 파일을 바로 가져올 수 있다. | RDS 전용 진입점이며 현재 `UPSERT`와 기존 ID·관계 계약을 보존하려면 별도 staging·merge가 필요하다. | 제외 |
| Docker Entrypoint 초기화 | 빈 로컬 DB를 처음 만들 때 단순하다. | Flyway가 스키마를 만드는 현재 실행 순서보다 먼저 동작하고, 운영 중 반복 적재·기존 데이터 보존에 부적합하다. | 로컬·QA 최초 초기화에 한정 |
| `pg_dump` / `pg_restore` | 전체 DB 복제와 재해복구에 적합하다. | 운영 스키마와 데이터 위에 카탈로그만 병합하는 적재 계약이 아니며, 선택적 `bgg_id` 갱신을 표현하지 못한다. | 백업·복구 용도로만 유지 |

## 결정

### 1. 초기 전체 적재는 승인 청크 `UPSERT` SQL을 사용한다

승인된 카탈로그 산출물의 SQL을 5,000행 단위 statement로 분할해 `psql`로 실행한다. 논리 파일의 트랜잭션 경계는 유지하며, `ON_ERROR_STOP=on`으로 실행한다. 게임 본문을 먼저 반영한 뒤 승인된 산출물에 따라 메커니즘·카테고리·테마·인원 선호 관계를 순서대로 반영한다.

운영 실행은 [#621](https://github.com/bamsongi-club/albam-mate/issues/621)의 데이터 정본과 최종 승인 산출물이 확정된 뒤에만 허용한다. `quality-report.json`이 승인 상태이고 `testOnly`가 `false`인 산출물만 사용한다.

### 2. S3는 저장·전달·검증 경계로 사용한다

SQL, manifest, `quality-report.json`과 SHA-256을 비공개 S3에 버전 고정해 보관한다. 운영 DB에 접근하는 승인된 실행 주체가 산출물을 다운로드한 뒤 checksum을 검증하고 `psql`을 실행한다. S3는 이번 초기 적재의 불변 산출물 저장소이자 전달 경계이며, 데이터베이스 적재 엔진으로 확정하지 않는다.

RDS와 self-managed PostgreSQL의 차이는 산출물을 DB에 전달하는 실행 환경에서만 다룬다. 이번 결정은 특정 DB 제품의 전용 `COPY` 확장이나 S3 연동 기능을 필수화하지 않는다.

### 3. 반복 적재의 식별자와 보존 규칙은 `bgg_id`를 기준으로 한다

초기 적재와 향후 반복 적재 모두 `bgg_id`를 안정적인 외부 식별자로 사용한다.

- 운영 DB에 없는 `bgg_id`는 신규 행으로 삽입한다.
- 이미 존재하는 `bgg_id`는 `GAMES.id`와 `created_at`을 보존하면서 승인된 변경 필드만 갱신한다.
- 새 snapshot에 없다는 이유만으로 기존 게임을 물리 삭제하지 않는다.
- 승인 관계의 게임·선택지를 해석할 수 없거나 배치 내부 중복·충돌이 검증을 통과하지 못하면 해당 산출물을 실행하지 않는다.

삭제·비공개 데이터의 판정과 반영은 외부 원천이 이를 신뢰성 있게 식별할 수 있을 때 별도 계약으로 결정한다.

### 4. 실행 전후 운영 게이트를 둔다

실행 전에는 #621 데이터 정본 확정, 승인 manifest·품질 보고서·checksum 확인, 복원 검증된 백업 또는 EBS snapshot 확보와 점검 시간을 확인한다. 실행 후에는 예상 행 수, 기존 `bgg_id`의 내부 ID 보존, PK/FK/UNIQUE 제약, 대표 검색 결과와 영향 테이블 `ANALYZE` 결과를 기록한다. 이 절차의 상세 명령과 파일별 순서는 [게임 카탈로그 검수·적재 가이드](../../guides/GAME_CATALOG_IMPORT.md)가 소유한다.

## 결과

- 얻는 것:
    - 현재 검증된 카탈로그 생성기와 운영 가이드의 계약을 유지하면서 초기 전체 적재를 실행할 수 있다.
    - 5,000행 단위 분할로 단일 대형 statement의 메모리 위험을 줄이고, 승인 산출물 버전과 checksum을 실행 결과에 연결할 수 있다.
    - RDS·self-managed PostgreSQL에서 같은 SQL·manifest·품질 보고서 묶음을 사용할 수 있다.
- 감수할 비용·위험:
    - 대용량 SQL의 생성·전달·실행과 점검 시간이 필요하다.
    - staging이 없으므로 행 단위 오류 격리, 부분 재처리와 자동 증분 동기화를 제공하지 않는다.
    - 운영 실행 전 데이터 정본·백업·checksum·검증 결과를 사람이 확인해야 한다.
- 후속 작업:
    - #621에서 초기 적재 데이터 기준과 충돌값을 확정하고 최종 승인 산출물을 생성한다.
    - 운영 적재 실행 결과와 사용한 산출물 checksum을 기록한다.
    - 반복 갱신 요구가 확정되면 staging → validation → `bgg_id` 기준 merge/upsert → cleanup을 별도 설계·구현 이슈로 진행한다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - 이번 초기 적재를 위한 새로운 `COPY`·staging·merge 파이프라인 구현
    - 자동 주기 import와 외부 원천의 삭제·비공개 자동 반영
    - Docker Entrypoint를 운영 재적재 경로로 사용
    - `pg_dump` / `pg_restore`를 카탈로그 부분 적재 방식으로 사용
- 보류 이유: 현재는 승인된 청크 `UPSERT` 산출물과 적재 가이드가 요구 계약을 충족하며, 새로운 staging 경로를 운영 직전에 도입하면 검증되지 않은 실패·복구 경계가 추가된다.
- 다시 검토할 조건:
    - 카탈로그를 정기적으로 갱신하거나 신규·변경 게임만 반복 반영해야 할 때
    - SQL 생성·전송·실행 시간이 운영상 문제가 될 때
    - 부분 실패 격리·재처리 또는 운영 테이블과 분리된 원본 validation이 필요할 때
    - 외부 원천에서 변경·삭제 대상을 신뢰성 있게 식별하고 자동화된 import pipeline을 운영할 담당·일정이 정해질 때

## 참고 자료

- [GitHub Issue #643: 게임 카탈로그 운영 DB 적재 전략 결정](https://github.com/bamsongi-club/albam-mate/issues/643)
- [GitHub Issue #621: 17만 건 전달본 적재 실패](https://github.com/bamsongi-club/albam-mate/issues/621)
- [게임 카탈로그 검수·적재 가이드](../../guides/GAME_CATALOG_IMPORT.md)
- [ADR-0015: BGG 기준 스냅샷과 팀 수집 자료로 서비스 게임 목록 구성](0015-bgg-baseline-team-collected-game-list.md)
- [ADR-0050: 17만 게임 메타데이터를 관계로 관리하고 상세 필터를 제공](0050-game-metadata-catalog-and-filters.md)
- [ADR-0051: P1 저비용 4 EC2 자체 운영 인프라와 Nginx 진입점](../platform/0051-p1-self-managed-aws-infrastructure.md)

## 검증

- 상태: 미검증
- 근거:
    - 계약: [게임 카탈로그 검수·적재 가이드](../../guides/GAME_CATALOG_IMPORT.md)는 승인 manifest, `testOnly` 게이트, `bgg_id` 기반 `UPSERT`, 기존 ID 보존, 관계 적재 순서와 실패 시 롤백을 정의한다.
    - 테스트: [#643 결정 코멘트](https://github.com/bamsongi-club/albam-mate/issues/643#issuecomment-5277446785)에 5,000행 단위 산출물의 로컬 전량 적재 검증 결과가 기록되어 있다.
- 미검증:
    - #621의 데이터 정본 확정과 최종 운영 산출물 승인
    - 실제 운영 DB에서 백업·checksum·적재 후 검증까지 포함한 실행
    - 반복 적재용 staging·validation·증분 merge 파이프라인의 구현과 운영 검증

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
