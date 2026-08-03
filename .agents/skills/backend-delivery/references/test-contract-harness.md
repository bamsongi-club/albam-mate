# 결정적 테스트 runner

## 입력과 실행 그룹

- expected JSON은 직접 쓰지 않고 최종 packet과 plan을 `scripts/build-backend-test-plan.mjs`에 전달해 저장소 밖에 만든다. builder가 현재 worktree snapshot을 직접 포함한다.
- `executions`에는 고유 명령을 `E1`부터 한 번씩 선언하고, 각 `tests`는 `T1`부터 실제 `testSources`와 필요한 `executionIds`를 참조한다. T-ID 하나가 H2와 PostgreSQL execution을 함께 참조할 수 있다.
- JUnit을 만드는 Gradle execution은 `junitTasks`에 `test` 또는 `postgresTest`를 선언한다. 정적 검사처럼 JUnit이 없는 execution은 빈 배열을 사용한다.
- 모든 `targetedTests`·`finalCommands`는 execution에 포함하고, 같은 command 중복은 expected validator가 거부한다.
- builder는 packet 순서를 기준으로 `targetedTests`, 그 밖의 T-ID 실행, `finalCommands` 순서로 execution을 재번호화한다. 신규·변경 동작을 직접 검증하는 명령을 전체 회귀·정적 검사보다 먼저 실행한다.

아래 JSON은 plan builder 입력인 schema version 1 예시다. builder 출력 expected는 `.codex/contracts/backend-test-expected.schema.json`의 schema version 2와 최종 snapshot hash들을 포함한다.

~~~json
{
  "schemaVersion": 1,
  "executions": [
    {
      "id": "E1",
      "command": ".\\gradlew.bat test --tests \"example.FirstTest\" --tests \"example.SecondTest\"",
      "timeoutMs": 900000,
      "junitTasks": ["test"]
    },
    {
      "id": "E2",
      "command": ".\\gradlew.bat postgresTest --tests \"example.QueryPostgresTest\"",
      "timeoutMs": 900000,
      "junitTasks": ["postgresTest"]
    }
  ],
  "tests": [
    {
      "id": "T1",
      "executionIds": ["E1"],
      "testSources": ["src/test/java/example/FirstTest.java"]
    },
    {
      "id": "T2",
      "executionIds": ["E1", "E2"],
      "testSources": [
        "src/test/java/example/SecondTest.java",
        "src/postgresTest/java/example/QueryPostgresTest.java"
      ]
    }
  ]
}
~~~

~~~shell
node scripts/build-backend-test-plan.mjs --packet <packet.json> --plan <plan.json> --output <expected.json> --worktree <worktree>
~~~

## runner

~~~shell
node scripts/run-backend-test-contract.mjs --expected <expected.json> --result <result.json> --worktree <worktree>
~~~

- expected·result·로그 경로는 snapshot을 바꾸지 않도록 worktree 밖에 둔다.
- runner는 결과·로그·JNA 임시 경로, Gradle Wrapper와 필요한 Docker daemon을 명령 실행 전에 확인한다.
- stdout와 stderr bytes를 Node SHA-256으로 계산하고 각 명령 종료 뒤 결과 JSON을 원자적으로 교체한다.
- `junitTasks`마다 실행 전후 `build/test-results/<task>/*.xml`을 비교해 새로 생성되거나 갱신된 보고서와 `tests > 0`을 증명한다. exit 0이어도 보고서가 없거나 `UP-TO-DATE`·`FROM-CACHE`·`NO-SOURCE`이면 `unverified`다.
- runner는 고정 snapshot에서 고유 execution을 순서대로 한 번씩 실행하고 첫 `fail`에서 남은 execution을 구체적 사유가 있는 `unverified`로 남긴 뒤 중단한다. 구현자는 실패 명령만 반복해 수정하되, 수정 완료 후에는 새 snapshot에서 전체 runner를 최종 한 번 실행한다. 환경 문제인 `unverified`는 원인을 해결한 뒤 새 result 경로로 전체 runner를 다시 실행한다.
- 종료 코드는 `pass=0`, `fail=1`, `unverified` 또는 입력·환경 오류 `=2`다.
- 결과는 runner 내부 검증에 더해 다음 명령으로 다시 확인한다.

~~~shell
node scripts/validate-backend-test-result.mjs --result <result.json> --expected <expected.json>
~~~

reviewer는 runner와 별도로 `review-code`의 기존 고정 diff 절차를 사용한다. runner result JSON을 리뷰 결과나 PR 승인으로 해석하지 않는다.
