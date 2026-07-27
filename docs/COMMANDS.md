# Albam Mate Commands

이 문서는 팀이 반복해서 사용하는 프로젝트 명령의 기준 문서다. 별도 안내가 없다면 모든 명령은 `albam-mate` 저장소 루트에서 실행한다.

일회성 설정과 긴 문제 해결 절차는 이 파일에 복제하지 않고 관련 가이드로 연결한다.

## 개발 환경 확인

프로젝트 애플리케이션에는 Java 21이 필요하고, 문서 링크 검사와 프롬프트 기록 훅에는 Node.js 20 이상이 필요하다.

```sh
java --version
node --version
```

Gradle은 별도 설치본 대신 저장소의 Wrapper를 사용한다.

| 작업 | Windows PowerShell | macOS·Linux |
|---|---|---|
| Gradle 버전 확인 | `.\gradlew.bat --version` | `./gradlew --version` |
| 애플리케이션 실행 | `.\gradlew.bat bootRun` | `./gradlew bootRun` |
| 테스트 | `.\gradlew.bat test` | `./gradlew test` |
| 빌드 | `.\gradlew.bat build` | `./gradlew build` |
| 산출물 제거 후 빌드 | `.\gradlew.bat clean build` | `./gradlew clean build` |
| 코드 포맷 검사 | `.\gradlew.bat spotlessCheck` | `./gradlew spotlessCheck` |
| 코드 포맷 자동 수정 | `.\gradlew.bat spotlessApply` | `./gradlew spotlessApply` |

현재 저장소에는 운영용 데이터소스 연결값이 포함되어 있지 않다. `bootRun`은 PostgreSQL 연결 설정이 없으면 데이터소스 자동 설정 단계에서 실패한다. 테스트는 H2 인메모리 데이터베이스를 사용하므로 별도의 PostgreSQL 없이 `test`와 `build`를 실행할 수 있다.

## PostgreSQL 마이그레이션 검증

`postgresTest`는 Testcontainers가 PostgreSQL 18.4 컨테이너(`postgres:18.4`)를
일회성으로 시작해 빈 데이터베이스에 Flyway 마이그레이션과 Hibernate
`ddl-auto=validate`를 적용한다. H2 기반 `test`와 별도 태스크이므로 일반적인
`test`·`build` 실행에는 Docker가 필요하지 않다.

```sh
./gradlew postgresTest --no-daemon --stacktrace
```

Docker 데몬이 없거나 Testcontainers가 컨테이너를 시작하지 못하면 테스트
결과가 아니라 실행 환경 제약으로 기록한다. CI에서는 `build` 뒤에
`postgresTest`를 명시적으로 실행한다.

## 코드 포맷 확인

Java 포맷은 Google Java Format의 AOSP 스타일을 사용한다. 기본 Google 스타일과 달리 블록 들여쓰기가 4칸이다.

포맷 위반을 수정한 뒤에는 diff를 검토하고 `spotlessCheck`를 다시 실행한다. clone마다 한 번 필요한 pre-commit hook 활성화 절차는 [코드 포맷과 Git hook 설정](guides/CODE_FORMATTING.md)을 따른다.

## 문서 링크 확인

정본 문서는 서로를 상대 링크로 참조한다. 문서를 옮기거나 제목을 바꾼 뒤에는 링크와 앵커가 남아 있는지 확인한다.

```sh
node scripts/check-doc-links.mjs
```

추적 중인 Markdown과 아직 커밋하지 않은 Markdown을 함께 검사하고 외부 링크는 확인하지 않는다. 작업 트리에 없는 경로는 검사 원본에서 빼므로 이동·삭제를 스테이징하기 전에도 실행할 수 있다. destination을 뽑지 못한 링크는 건너뛰지 않고 `파싱 실패`로 보고한다.

검사기 자체를 고치면 회귀 테스트를 함께 실행한다.

```sh
node --test scripts/check-doc-links.test.mjs
```

두 명령을 CI의 `Docs` job이 함께 실행하므로, 문서나 검사기를 변경한 PR은 둘 다 먼저 통과시킨다.

## 게임 카탈로그 검수

BGG 기준 CSV와 팀 검수 JSON의 매핑·중복·필수값·품질 경고를 확인한다.
출처 manifest가 없거나 검수 상태가 승인되지 않으면 보고서만 만들고 적재 SQL은
생성하지 않는다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --out build/game-catalog/draft
```

테스트와 승인 후 적재 절차는 [게임 카탈로그 검수·적재](guides/GAME_CATALOG_IMPORT.md)를 따른다.

## 프롬프트 기록 확인

팀원별 최초 환경 설정은 [How to 팀 프롬프트 기록 환경 설정](guides/PROMPT_LOGGING.md)을 따른다.

현재 프로세스가 팀원 이름을 인식하는지 확인한다.

Windows PowerShell:

```powershell
$env:BAMSONGI_MEMBER
```

macOS·Linux:

```sh
echo "$BAMSONGI_MEMBER"
```

Codex와 Claude Code에서는 각각 `/hooks`를 실행해 프로젝트의 `UserPromptSubmit` 훅이 보이는지 확인한다. Codex는 훅을 처음 적용하거나 변경한 뒤 다시 신뢰해야 한다.

프롬프트 로거의 저장·필터링 동작을 확인한다.

```sh
node --test .bamsongi/hooks/user-prompt.test.mjs
```

이 테스트는 직접 입력한 프롬프트만 저장하고 Codex 내부 추천 및 서브에이전트 프롬프트는 제외하는지 확인한다.

저장된 프롬프트를 확인한다.

Windows PowerShell:

```powershell
Get-ChildItem `
  -LiteralPath "..\bamsongi-brain\prompts\$env:BAMSONGI_MEMBER" `
  -Filter "*.md"
```

macOS·Linux:

```sh
find "../bamsongi-brain/prompts/$BAMSONGI_MEMBER" \
  -type f \
  -name '????-??-??.md'
```

## 명령 관리 원칙

- 저장소에서 실제로 사용할 수 있는 명령만 기록한다.
- 팀이 반복해서 실행하는 명령은 이 파일에 추가한다.
- 최초 설치, 운영체제별 설정, 문제 해결처럼 설명이 긴 절차는 `docs/guides/`에 분리하고 여기에는 링크와 반복 확인 명령만 둔다.
- 스크립트나 Gradle task가 변경되면 같은 변경에서 이 문서도 함께 갱신한다.
