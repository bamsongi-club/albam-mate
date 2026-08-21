# MATCH-01 응답 완료 baseline 결과

- 측정 실행 SHA: `584949df371254367167934d24ba8cfb665116d7`
- fixture seed: `MATCH-01-RESPONSE-COMPLETION-V2`
- fixture input SHA-256: `aa24273b455d11d05e54547abc00efb32b0e072d3ec2c016c5ec4bf0bfe6231a`
- materialized sidecar SHA-256: `81c6c3d9911c7d83b1f63665e525f9be0e2541380863c6c5716b17da43f1a462`
- private sidecar: `response-completion-584949df371254367167934d24ba8cfb665116d7-private-sidecar.json`
- artifact: `response-completion-584949df371254367167934d24ba8cfb665116d7.json`
- artifact SHA-256: `a422308a04ed673521dd50f3a25b1bc3362f9c576112e4b9111f6c43b11c8a2f`
- 판정: `RESPONSE_BASELINE_ACCEPTED`

각 시나리오(`ACCEPT_NON_TERMINAL`, `ACCEPT_FINAL`, `REQUEUE`, `CANCEL`)는 warm-up 1회와 measured 3회를 실행했다. scheduler·notification relay·chat retention은 모두 `false`로 비활성화했고, artifact environment와 round별 UTC observation window에 이를 기록했다. measured round마다 1,000개의 비식별 raw sample, `operationTime`부터 coordinator의 DTO 조합 완료까지의 완료 시각과 `respondBy` window, PostgreSQL statement 관측, lock-wait 관측 필드와 sample별 final-state observation을 보존한다. fixture는 DB `transaction_timestamp()` 하나를 reference time으로 사용하고, 모든 Proposal의 `created_at`·`respond_by=reference+30초`, request `min/max party size=2/4`, party size 2를 고정한다. fixture input은 사용자 UUID 없이 fixture ordinal만 담은 CSV bytes다. private sidecar에는 `(scenario,warmUp,round,proposalOrdinal,memberOrdinal)` run key와 실제 Proposal·Member composite·request ID, expected/observed proposal·member·request·queue fact를 보존하며, 사용자 ID·nickname·body·idempotency key는 보존하지 않는다. Node reporter는 artifact-relative sidecar bytes를 해시한 뒤 row coverage·중복·expected/observed 불일치와 public final-state scalar 재계산값을 검증한다. `ACCEPT_FINAL`은 member transition 사실로 nonterminal(`responded_at < confirmed_at`) 500건과 terminal(`responded_at = confirmed_at`) 500건을 확인한다. DTO snapshot은 별도 관측값으로 `{PROPOSED, PREPARING, ACTIVE}`만 허용한다. Proposal·Member·request 식별자는 공개 artifact에 포함하지 않는다. 후보 baseline 또는 종합 gate artifact SHA는 이 결과의 입력이 아니다.
