# GitHub 이슈 → Jira 동기화

`.github/workflows/github-issue-to-jira.yml`은 이 저장소의 GitHub 이슈 이벤트를 Jira `EZIZ` 프로젝트와 동기화한다.

## 동작

- `opened`: Jira 이슈가 없으면 `Task`를 생성한다.
- `reopened`: 기존 Jira 이슈를 `해야 할 일`로 되돌린다.
- `closed`: 기존 Jira 이슈를 `완료`로 전환한다.
- GitHub 이슈 번호를 `github-issue-{번호}` 라벨로 저장해 같은 이슈가 중복 생성되지 않게 한다.
- pull request는 GitHub `issues` 이벤트에도 포함될 수 있으므로 동기화하지 않는다.

## GitHub Actions Secret

저장소의 `Settings → Secrets and variables → Actions`에 다음 Secret을 추가한다.

| 이름 | 값 |
| --- | --- |
| `JIRA_EMAIL` | Jira 로그인 이메일 |
| `JIRA_API_TOKEN` | Atlassian 계정에서 발급한 API token |

API token은 저장소 파일, 로그, 커밋에 기록하지 않는다. Jira 사이트와 프로젝트 키는 워크플로에 공개 설정값으로 고정되어 있다.
