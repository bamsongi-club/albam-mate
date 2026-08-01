# ADR-0039: 알림 표시 투영과 PostgreSQL 조회·읽음 시각을 확정

- 상태: 승인됨
- 작성일: 2026-07-31
- 결정일: 2026-08-01
- 관련: [P1 알림 구현 명세](../../p1/notification.md), [API 알림 목록](../../API.md#noti-02-내-알림-목록), [API 알림 일괄 읽음](../../API.md#noti-03-내-알림-일괄-읽음), [ERD 사용자별 알림](../../ERD.md#notifications), [ADR-0002](../platform/0002-postgresql-primary-database.md), [ADR-0010](../platform/0010-h2-postgresql-test-boundary.md), [ADR-0029](0029-room-integration-event-transactional-outbox.md), [ADR-0030](0030-postgresql-notification-relay-processing-recovery.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

알림 목록은 세 가지 유형과 관련 방을 사람이 구분할 최소 표시값을 제공해야 한다. 서버가 일반 문구를 Notification에 저장하면서 클라이언트도 `type`으로 표시 방식을 정하면 같은 의미를 두 계약이 소유하고, 문구 변경·다국어 지원 때 저장값과 클라이언트 표현이 달라질 수 있다. 방 제목까지 이벤트 시점 스냅샷으로 저장하면 Outbox와 Notification에 사용자 입력 문자열을 중복 보존한다.

일괄 읽음 응답은 대상 집합의 최대 ID, 실제 갱신 건수와 같은 읽음 시각을 일관된 스냅샷에서 계산해야 한다. 기본 `READ COMMITTED`에서 경계 조회와 갱신을 별도 SQL 문장으로 실행하면 그 사이 커밋된 알림을 뒤 문장이 볼 수 있다. 트랜잭션 전체를 `REPEATABLE_READ`로 올릴 수도 있지만 이 유스케이스에 넓은 격리 경계와 동시 갱신 시 재시도 책임을 추가한다.

애플리케이션이 SQL 전에 `requestTime`을 고정하면 그 시각과 실제 PostgreSQL 문장 스냅샷 획득 사이에 새 Notification이 커밋될 수 있다. 이 행은 일괄 읽음 SQL에는 보이지만 `readAt=requestTime`은 더 늦은 `recordedAt`보다 앞설 수 있어 `read_at >= recorded_at` 저장 제약을 위반한다. PostgreSQL `statement_timestamp()`도 클라이언트의 최신 명령 수신 시각이어서 문장 스냅샷보다 앞설 수 있다. 일괄 읽음 대상은 하나의 문장 스냅샷으로, 읽음 시각은 그 SQL 실행 안에서 실제 DB 시각을 한 번 평가한 `operationTime`으로 고정해야 한다.

알림 목록은 기존 공통 `page`·`size` 응답을 사용한다. relay가 실패 이벤트를 나중에 복구하면 원인 시각을 기준으로 과거 페이지 위치에 새 행이 삽입될 수 있으므로, 서로 다른 요청 사이까지 offset 위치가 고정된다고 보장할 수 없다.

목록·페이지 count와 미확인 개수가 애플리케이션 `Clock`의 `requestTime`으로 만료를 판정하면서 단건·일괄 읽음이 PostgreSQL `operationTime`으로 판정하면, 두 시계의 상대 순서를 보장하지 않는 현재 계약에서 같은 알림의 노출 여부와 읽음 가능 여부가 지속해서 달라질 수 있다. PostgreSQL 시각이 앞선 경우 읽음 요청은 만료로 실패하지만 후속 목록 조회가 같은 행을 다시 반환할 수 있다. 반대로 목록 본문과 `totalElements` count가 서로 다른 현재 시각을 사용하면 한 GET 안에서도 만료 경계의 전체 건수와 항목 집합이 달라질 수 있다.

이번 결정은 다음 기준을 만족해야 한다.

- 표시 문구의 소유자를 하나로 두고 방을 사람이 구분할 값을 제공할 것
- 알림 저장값과 Outbox에 불필요한 사용자 표시 문자열을 중복하지 않을 것
- 일괄 읽음 경계·갱신·응답을 하나의 PostgreSQL 문장 스냅샷으로 묶을 것
- 업무 시각(`occurredAt`·`createdAt`)과 저장·처리 시각(`recordedAt`·`readAt`)의 출처를 구분하고, 같은 PostgreSQL 시계의 Notification `recordedAt`·`readAt` 순서만 저장 제약으로 검증할 것
- 목록·페이지 count·미확인 개수와 단건·일괄 읽음의 만료 판정을 PostgreSQL 시계로 통일하고, 한 조회 트랜잭션의 만료 기준을 고정할 것
- SQL 문장 스냅샷 획득 뒤 커밋된 알림을 읽음 처리하지 않을 것
- PostgreSQL 고유 동작과 동시 알림 생성을 실제 PostgreSQL에서 검증할 것
- P1의 페이지 응답 범위를 누락 없는 증분 소비 API로 확대하지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 유형별 서버 문구와 방 제목 스냅샷을 Notification에 저장 | 이벤트 당시 문구와 제목을 그대로 보존하고 클라이언트가 바로 표시할 수 있다. | 표시 정책이 서버 저장값과 클라이언트에 중복되고 사용자 입력 제목의 저장 범위가 늘어난다. | 제외 |
| 서버 문구를 제거하고 `type`으로 렌더링하며 현재 방 제목을 조회 시 결합 | 표시 정책을 클라이언트가 소유하고 기존 `ROOMS.title`로 방을 구분하며 저장 중복이 없다. | 방 제목이 바뀌면 과거 알림의 제목도 바뀌고 목록 조회에 방 결합이 필요하다. | 선택 |
| 조회 QueryService가 애플리케이션 `Clock`으로 `requestTime`을 고정 | 기존 Query 시간 주입 방식과 `Clock.fixed` 단위 테스트를 그대로 사용할 수 있다. | PostgreSQL 시각과 어긋나면 목록·미확인 개수에는 보이지만 읽음은 계속 실패하는 상태가 가능하다. | 제외 |
| GET 시작에 PostgreSQL `clock_timestamp()`를 별도 조회해 목록 본문·count에 파라미터로 전달 | DB 시각을 명시적으로 한 번 고정하고 애플리케이션 테스트에서 파라미터를 제어하기 쉽다. | 10초 polling 경로마다 시각 조회 SQL 왕복이 하나씩 추가된다. | 제외 |
| 짧은 읽기 트랜잭션의 `transaction_timestamp()`를 목록 본문·count와 미확인 개수에 사용 | PostgreSQL 시각으로 판정을 통일하고 한 트랜잭션에서 같은 값을 유지하면서 별도 시각 조회 왕복이 없다. | QueryService가 트랜잭션 경계를 소유해야 하고 PostgreSQL 전용 회귀 테스트가 필요하다. | 선택 |
| `REPEATABLE_READ`에서 경계 조회와 갱신을 여러 문장으로 실행 | 기존 Repository 조회·갱신을 분리해 작성하기 쉽고 트랜잭션 전체에서 같은 스냅샷을 본다. | 한 유스케이스의 격리 수준을 올리고 동시 갱신 실패·재시도 경계가 넓어진다. | 제외 |
| 읽음 SQL 전에 애플리케이션 `Clock`으로 `requestTime`을 고정 | 기존 시간 주입 방식과 같고 애플리케이션 테스트에서 시각을 제어하기 쉽다. | 시각 고정 뒤 읽음 문장 스냅샷 획득 전에 커밋된 Notification을 과거 `readAt`으로 갱신해 저장 CHECK를 위반할 수 있다. | 제외 |
| 단일 SQL에서 PostgreSQL `statement_timestamp()`를 고정 | 별도 애플리케이션 시각 없이 문장마다 한 값이 유지된다. | 값이 문장 실행·스냅샷 획득 시각이 아니라 클라이언트 명령 수신 시각이므로, 그 뒤 커밋됐지만 스냅샷에는 보이는 Notification보다 앞설 수 있다. | 제외 |
| 기본 `READ COMMITTED`의 단일 data-modifying CTE/`UPDATE`에서 PostgreSQL `clock_timestamp()`를 한 번 평가 | 경계·갱신·응답이 같은 문장 스냅샷과 SQL 실행 중 고정한 실제 DB 시각을 사용하고 트랜잭션 격리 수준을 올리지 않는다. | PostgreSQL 전용 SQL adapter와 DB 시각을 사용하는 전용 회귀 테스트가 필요하다. | 선택 |
| 알림 목록을 즉시 keyset cursor로 변경 | offset 이동에 따른 페이지 중복·누락을 줄일 수 있다. | 공통 페이지 응답과 다른 계약이 추가되고, 지연 복구된 과거 이벤트의 증분 발견 정책도 별도로 설계해야 한다. | 제외 |

## 결정

Notification에는 `type`과 `roomId`를 저장하고 일반 표시 문구와 방 제목 스냅샷은 저장하지 않는다. 클라이언트는 `type`으로 표시 문구·방식과 동작을 렌더링한다. 목록 API는 `roomId`로 현재 `ROOMS.title`을 결합해 필수 `roomTitle`로 반환하며 기존 방 제목 계약과 같은 최대 100자를 적용한다. 방 제목이 바뀌면 과거 알림에도 현재 제목을 반환한다. `roomTitle`과 `roomId`는 알림 식별·이동 정보일 뿐 방 상세 접근 권한이 아니다.

이 표시·시각 결정은 ADR-0029의 Outbox 트랜잭션·수신자·relay 소유 경계와 ADR-0030의 선점·재시도·복구 정책을 바꾸거나 두 ADR 전체를 대체하지 않는다. 다만 ADR-0029 결정 본문에 포함된 relay의 일반 문구 생성과 ADR-0030 결정 본문에 포함된 애플리케이션 `Clock` 기반 Notification `recordedAt`이라는 하위 가정에는 더 구체적인 이 ADR과 API·ERD의 표시·시각 계약을 적용한다.

Outbox `occurredAt`과 Notification `createdAt`은 Command Coordinator가 애플리케이션 `Clock`으로 고정한 원인 업무 시각이다. Outbox·Notification `recordedAt`과 Notification `readAt`은 저장·처리 트랜잭션이 PostgreSQL에서 생성한 작업 시각이다. 두 시각 도메인 사이에는 상대 순서를 보장하지 않는다.

Notification의 저장 만료 시각은 기존대로 `expiresAt = createdAt + NOTIFICATION_RETENTION`으로 계산한다. 다만 현재 만료 여부를 판정하는 시각은 사용자 조회와 읽음 처리 모두 PostgreSQL이 소유한다. 목록 QueryService는 목록 본문과 페이지 count를 포함하는 하나의 짧은 읽기 트랜잭션을 소유하고 두 SQL 모두 `expires_at > transaction_timestamp()`를 사용한다. 미확인 개수 QueryService도 같은 방식으로 자기 읽기 트랜잭션의 `transaction_timestamp()`를 사용한다. PostgreSQL의 `transaction_timestamp()`는 트랜잭션 시작 시각으로 고정되므로 한 GET의 여러 SQL이 같은 만료 경계를 사용하며 별도 시각 조회 SQL은 실행하지 않는다.

목록과 미확인 개수는 서로 다른 HTTP 요청이므로 같은 트랜잭션이나 시각을 공유하지 않는다. GET이 만료 직전에 시작하고 PATCH가 만료 직후 시작해 GET 응답의 알림이 읽음 처리에서 not-found가 되는 자연스러운 경계 경쟁은 허용한다. 이 경우 PATCH 뒤 새로 시작한 GET은 같은 PostgreSQL 시계에서 만료 행을 제외해 수렴해야 하며, 애플리케이션 `Clock` 차이로 만료 행이 반복 노출되어서는 안 된다.

원인 업무의 최종 성공 트랜잭션은 PostgreSQL `clock_timestamp()`를 한 번 조회해 Outbox `recordedAt`으로 사용한다. relay도 이벤트 처리 트랜잭션에서 같은 함수를 한 번 조회해 같은 이벤트에서 새로 만드는 모든 Notification의 `recordedAt`에 사용한다. 단건 읽음은 하나의 `UPDATE` SQL 내부 `operation` CTE에서 `clock_timestamp()`를 한 번 평가해 최초 `readAt`과 만료 판정에 사용한다. 따라서 `Outbox.recordedAt >= occurredAt`이나 `Notification.recordedAt >= createdAt` 같은 교차 시계 CHECK는 두지 않고, 애플리케이션 시계가 PostgreSQL보다 앞서도 Outbox와 Notification 저장을 허용한다. 같은 PostgreSQL 시계인 Notification의 `read_at IS NULL OR read_at >= recorded_at`만 저장 제약으로 유지한다. 수신자별 전달 지연은 PostgreSQL이 생성한 `Notification.recordedAt - Outbox.recordedAt`으로 측정한다.

일괄 읽음은 기본 `READ COMMITTED`의 하나의 쓰기 트랜잭션 안에서 다음 형태의 단일 PostgreSQL SQL 문장으로 실행한다. `operation` CTE는 SQL 실행 중 `clock_timestamp()`를 한 번 평가하고, 만료 판정·새 `readAt`·응답에 같은 `operationTime`을 사용한다.

~~~sql
WITH operation AS MATERIALIZED (
    SELECT clock_timestamp() AS operation_time
),
boundary AS MATERIALIZED (
    SELECT MAX(id) AS notification_id
    FROM notifications
    CROSS JOIN operation
    WHERE recipient_user_id = :recipientUserId
      AND expires_at > operation.operation_time
),
updated AS (
    UPDATE notifications AS notification
       SET read_at = operation.operation_time
      FROM boundary
      CROSS JOIN operation
     WHERE notification.recipient_user_id = :recipientUserId
       AND notification.expires_at > operation.operation_time
       AND notification.read_at IS NULL
       AND notification.id <= boundary.notification_id
    RETURNING notification.id
)
SELECT COUNT(updated.id) AS updated_count,
       boundary.notification_id AS boundary_notification_id,
       operation.operation_time AS read_at
FROM boundary
CROSS JOIN operation
LEFT JOIN updated ON TRUE
GROUP BY boundary.notification_id, operation.operation_time;
~~~

`operation`은 문장 실행 안에서 실제 PostgreSQL 시각을 한 번만 평가하고 `MATERIALIZED` CTE 결과로 재사용한다. `boundary`는 그 시각에 만료되지 않고 같은 문장 스냅샷에 보이는 본인 알림 전체에서 최대 ID를 계산한다. 대상이 없으면 집계 행의 경계가 `NULL`이고 `updatedCount = 0`이며 `readAt`은 그래도 반환한다. `updated`는 같은 사용자·만료 조건에서 경계 이하의 미확인 행만 같은 `operationTime`으로 변경한다. 마지막 조회는 `RETURNING` 결과의 수, 경계와 읽음 시각을 함께 반환한다. SQL 문장 스냅샷에 보이지 않은 알림은 시퀀스 ID 할당 순서나 원인 이벤트 시각과 관계없이 변경하지 않는다.

목록은 `page`·`size` offset pagination을 유지한다. `createdAt DESC, id DESC` 정렬의 안정성은 같은 DB 상태에만 보장하며, 서로 다른 요청 사이에 지연 복구된 과거 알림이 추가되면 항목이 이동·중복·누락될 수 있음을 수용한다.

## 결과

- 얻는 것:
    - 표시 문구의 소유자가 클라이언트 `type` 렌더링으로 하나가 되고 Notification 저장 모델이 작아진다.
    - 현재 방 제목으로 알림을 구분하면서 Outbox와 Notification에 제목 스냅샷을 추가하지 않는다.
    - 원인 업무 시각과 Outbox·Notification 기록·읽음 시각의 소유권이 분리되고, 일괄 읽음의 경계·갱신 건수·응답이 하나의 문장 스냅샷에 고정된다.
    - 목록·페이지 count·미확인 개수와 읽음 처리가 같은 PostgreSQL 시계로 만료를 판정하고, 한 조회 트랜잭션은 별도 시각 조회 없이 같은 경계를 사용한다.
- 감수할 비용·위험:
    - 방 제목 변경이 과거 알림에 반영되고 알림 목록 조회가 `ROOMS`를 결합한다.
    - Outbox·relay 기록 시각과 단건·일괄 읽음 Repository가 PostgreSQL 시각 조회와 전용 SQL에 의존한다.
    - 목록과 미확인 개수 Repository가 PostgreSQL `transaction_timestamp()`와 QueryService 읽기 트랜잭션 경계에 의존한다.
    - 서로 다른 HTTP 요청은 만료 경계를 공유하지 않으므로 만료 직전 GET과 직후 PATCH 사이의 일시적인 not-found 경쟁은 남는다.
    - offset 페이지 요청 사이 DB 상태가 바뀌면 항목 이동·중복·누락이 가능하다.
- 후속 작업:
    - 전진 마이그레이션과 Entity에서 Notification 표시 문구 컬럼을 만들지 않고, 목록 조회에 현재 방 제목 투영을 구현한다.
    - Outbox 기록과 relay·단건 읽음은 PostgreSQL 시각을 한 번 고정해 Outbox·Notification `recordedAt`과 Notification `readAt`에 사용한다.
    - 목록 QueryService의 한 읽기 트랜잭션에서 본문·페이지 count가 같은 `transaction_timestamp()` 만료 조건을 사용하고, 미확인 개수도 자기 읽기 트랜잭션의 같은 DB 시각 조건을 사용하게 구현한다.
    - 일괄 읽음 전용 PostgreSQL SQL adapter와 응답 매핑을 구현한다.
    - 실제 PostgreSQL에서 애플리케이션 시계가 DB보다 앞서거나 뒤져도 목록·미확인 개수와 읽음 처리가 DB 만료 경계로 수렴하고 Outbox와 Notification 저장이 성공하는 테스트를 추가한다.
    - 실제 PostgreSQL에서 읽음 문장 스냅샷 획득 전후에 새 알림을 각각 커밋하는 동시성 테스트와 경계 없음·모두 읽음·일부 읽음 회귀 테스트를 추가한다.

## 보류 및 재검토

- 지금 하지 않는 것: 서버 저장 표시 문구, 방 제목 스냅샷, 알림 cursor API, `REPEATABLE_READ` 일괄 읽음, 애플리케이션 `Clock` 기반 알림 조회 만료·Outbox·Notification 기록·읽음 시각
- 보류 이유: 현재 세 유형과 화면은 `type`·현재 방 제목·단일 SQL로 충족되며 누락 없는 증분 알림 소비 요구가 없다.
- 다시 검토할 조건: 서버가 채널별 문구·다국어를 소유해야 하거나 이벤트 당시 방 제목 보존이 제품 요구가 되거나, offset 이동으로 실제 사용자 누락 문제가 측정되거나, 목록 본문·count를 같은 읽기 트랜잭션에 둘 수 없거나, 단일 SQL로 표현할 수 없는 일괄 읽음 규칙이 추가되거나 Outbox·Notification 시각을 PostgreSQL에서 생성할 수 없는 저장 경계가 생길 때

## 참고 자료

- [PostgreSQL `WITH` Queries](https://www.postgresql.org/docs/current/queries-with.html#QUERIES-WITH-MODIFYING)
- [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [PostgreSQL Date/Time Functions](https://www.postgresql.org/docs/current/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT)

## 검증

- 상태: 미검증
- 근거:
    - 계약:
        - API·ERD와 P1 알림 구현 명세가 `type` 기반 클라이언트 렌더링, 현재 방 제목 투영, 업무 시각과 PostgreSQL 조회·저장·처리 시각의 분리, 조회 `transaction_timestamp()`와 읽음 `operationTime` 기반 SQL 스냅샷 및 offset 한계를 정의한다.
- 미검증:
    - Notification 마이그레이션·Entity·현재 방 제목 목록 투영이 구현되지 않았다.
    - 조회 QueryService의 PostgreSQL `transaction_timestamp()` 만료 판정, Outbox·relay·단건 읽음의 PostgreSQL 시각 고정, 애플리케이션 시계가 DB보다 앞서거나 뒤진 조회·저장 테스트, 단일 CTE/`UPDATE` adapter와 문장 스냅샷 획득 전후 동시 알림 생성 PostgreSQL 테스트가 구현·실행되지 않았다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
