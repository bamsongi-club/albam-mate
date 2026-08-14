# Scripts

저장소 자동화 스크립트의 소유 영역을 찾기 위한 내부 인덱스다. 사용자 관점의 실행 명령과 절차는 [Albam Mate Commands](../docs/COMMANDS.md)를 따른다.

| 작업 | 명령 또는 스크립트 | 실행 주체 |
| --- | --- | --- |
| 문서 링크 검증 | `scripts/docs/check-doc-links.mjs` | CI, 로컬 |
| 운영 관측 계약 검증 | `scripts/docs/check-monitoring-contract.mjs` | CI, 로컬 |
| CI 변경 경로 분류 | `scripts/ci/classify-ci-paths.mjs` | GitHub Actions |
| PostgreSQL 테스트 분류 | `scripts/ci/classify-postgres-requirement.mjs` | GitHub Actions |
| PostgreSQL 테스트 파티셔닝 | `scripts/ci/partition-postgres-tests.mjs` | GitHub Actions |
| ROOM-09 실측 보고서 생성 | `scripts/measurements/room09-measurement-report.mjs` | 수동 |
| Git hook 설치 | `scripts/hooks/install-git-hooks.ps1`, `scripts/hooks/install-git-hooks.sh` | 로컬 |
| 게임 카탈로그 준비·검증 | `scripts/game-catalog/` | 수동 |
| 백엔드 전달 검증 | `scripts/validate-packet.mjs`, `scripts/validate-backend-test-manifest.mjs`, `scripts/validate-coverage-ratchet.mjs`, `scripts/verify-changed-h2-coverage.mjs` | 에이전트, CI |
| Docker 배포 검증 | `scripts/verify-docker-deployment.mjs` | 수동 |
