# MATCH-01 T-ID 실행 대조

- 대상 코드 SHA: `c017d2f52f6548dc85ab86fed0f0d668397a3fe3`
- 실행 위치: 별도 detached measurement worktree
- 실행 원칙: Gradle worker 1개로 테스트를 병렬 실행하지 않음
- 전체 판정: `PARTIAL_COVERAGE`

이 문서는 `matching.md`의 검증 증거 매핑과 실제 실행 범위를 대조한 기록이다. 테스트 결과를 문서의 완료 상태에 맞추지 않으며, `PARTIAL_COVERAGE`를 MATCH-01 전체 완료나 운영 실측 완료로 해석하지 않는다.

| T-ID | 실제 실행 증거 | 대조 결과 | 남은 범위 |
| --- | --- | --- | --- |
| `T1` | `MatchRequestTest`, `MatchRequestHttpIntegrationTest`, `MatchRequestInvariantPostgresTest` | 통과 | 없음 |
| `T2` | `MatchProposalClaimPostgresTest` 전체 | 통과 | 없음 |
| `T3` | `MatchProposalTerminalPostgresTest`, `MatchPartyLifecyclePostgresTest`, `MatchRequestInvariantPostgresTest` | 부분 통과 | 시간 경과만으로 오래된 `WAITING`이 만료되지 않는 전용 회귀 테스트가 없음 |
| `T4` | `MatchRequestHttpIntegrationTest`, `MatchProposalClaimPostgresTest`, `MatchProposalTerminalPostgresTest` | 통과 | 없음 |
| `T5` | `MatchLifecycleConcurrencyPostgresTest`, `MatchProposalTerminalPostgresTest`, `MatchRequestInvariantPostgresTest` | 통과 | 없음 |
| `T6` | `MatchLifecycleRecoveryPostgresTest`, `MatchPartyLifecyclePostgresTest`, `MatchPartyAccessQueryPostgresTest`, `MatchPartyChatWriteGuardPostgresTest`, `MatchChatAdapterTransactionPostgresTest`, `MatchChatMessageRateLimitPostgresTest`, `RedisMatchChatMessageRateLimiterTest` | 부분 통과 | 제한 저장소 자체 장애가 실제 MATCH 메시지 HTTP 경계에서 저장 전 `503`으로 닫히는 전용 통합 증거가 없음 |
| `T7` | lifecycle recovery, PostgreSQL WebSocket restart/cross-instance, H2 reconnect/realtime handler 테스트 | 통과 | 없음 |
| `T8` | `MatchPartyLifecyclePostgresTest`, `MatchPartyAccessQueryPostgresTest`, `MatchPartyParticipantRepositoryPostgresTest`, `MatchChatAdapterTransactionPostgresTest`, `MatchChatPersistencePostgresTest` | 부분 통과 | CLOSED 이후 URL 텍스트 메시지와 개인정보·로그 금지 항목을 함께 검증하는 전용 증거가 없음 |
| `T9` | block HTTP/PostgreSQL, report HTTP/PostgreSQL, report cleanup H2/PostgreSQL | 통과 | 없음 |
| `T10` | #775 candidate claim baseline 결과 | 미완료 | 개선 전후 동일 fixture의 부하·쿼리·인덱스·트랜잭션 비교가 없음 |
| `T11` | #776 response completion baseline consumer | baseline 통과 | 개선 전후 비교 쌍이 없어 `AC9` 전체 완료는 아님 |
| `T12` | `MatchCurrentStateCorrectionPostgresTest`, `MatchCurrentStateSnapshotPostgresTest` | 통과 | 없음 |

## 판정 경계

- #746의 종합 gate는 승인된 ADR-0065 범위대로 candidate claim과 `T1`, `T5`~`T7`, `T12`, 그리고 선행 #775·#776 consumer만 gate 입력으로 사용한다.
- `T2`~`T4`, `T8`~`T9`의 실행 결과는 이 대조 기록에 남기되 #746 gate의 필수 입력으로 소급하지 않는다.
- `T3`, `T6`, `T8`의 남은 범위를 통과로 표시하지 않는다.
- `T10`과 `T11`의 baseline은 존재하지만, `matching.md`의 `AC9`가 요구하는 개선 전후 비교를 대신하지 않는다.

## 실행 이상 기록

H2의 여러 클래스를 한 JVM 명령으로 묶은 최초 실행은 cleanup coordinator의 context 간섭으로 실패했다. 해당 묶음 결과는 통과 증거로 채택하지 않았고, cleanup 메서드와 각 H2 클래스를 단독으로 재실행해 `BUILD SUCCESSFUL`을 확인했다.
