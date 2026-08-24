# How to 팀 프롬프트 기록 환경 설정

이 문서는 `albam-mate`에서 Codex와 Claude Code에 입력한 프롬프트를 Private 브레인의 팀원별 **prompts/** 폴더에 자동 저장하는 방법을 설명한다.

아래 명령의 `jiho`는 예시다. 각 팀원은 자신을 구분할 수 있는 이름으로 바꿔서 실행한다.

반복해서 사용하는 확인 명령만 필요하다면 [Albam Mate Commands](../COMMANDS.md)를 참고한다.

## 준비 사항

- Node.js 20 이상이 설치되어 있어야 한다.
- `albam-mate`와 `bamsongi-brain`을 기본적으로 같은 상위 폴더에 clone한다.

```text
bamsongi-sangdan/
├─ albam-mate/
└─ bamsongi-brain/
```

Node.js 버전을 확인한다.

```sh
node --version
```

출력된 주 버전이 `v20` 이상이어야 한다.

## Windows에서 설정

PowerShell을 열고 다음 두 줄을 실행한다.

```powershell
[Environment]::SetEnvironmentVariable("BAMSONGI_MEMBER", "jiho", "User")
$env:BAMSONGI_MEMBER = "jiho"
```

첫 번째 줄은 Windows 사용자 환경변수로 저장한다. 두 번째 줄은 현재 PowerShell에도 즉시 적용한다.

설정값을 확인한다.

```powershell
[Environment]::GetEnvironmentVariable("BAMSONGI_MEMBER", "User")
$env:BAMSONGI_MEMBER
```

두 명령 모두 설정한 이름을 출력해야 한다.

실행 중인 Codex·Claude Code·IDE·터미널을 완전히 종료한 뒤 다시 실행한다. 새 프로세스가 사용자 환경변수를 상속하지 못하면 Windows에서 로그아웃 후 다시 로그인한다.

## macOS 터미널에서 설정

Codex CLI와 Claude Code를 터미널에서 실행한다면 `~/.zshrc`에 환경변수를 저장한다.

```sh
grep -qxF 'export BAMSONGI_MEMBER="jiho"' ~/.zshrc 2>/dev/null || \
  printf '\nexport BAMSONGI_MEMBER="jiho"\n' >> ~/.zshrc
source ~/.zshrc
```

설정값을 확인한다.

```sh
echo "$BAMSONGI_MEMBER"
```

설정한 이름이 출력되면 같은 터미널에서 실행한 Codex CLI와 Claude Code에 모두 적용된다.

## macOS Codex 앱에도 적용

Dock이나 Finder에서 Codex 데스크톱 앱을 실행한다면 `.zshrc`와 별도로 다음 명령을 실행한다.

```sh
launchctl setenv BAMSONGI_MEMBER "jiho"
```

설정값을 확인한다.

```sh
launchctl getenv BAMSONGI_MEMBER
```

Codex 앱을 `Command + Q`로 완전히 종료한 뒤 다시 실행한다. `launchctl setenv` 값은 로그아웃이나 재부팅 후 사라질 수 있으므로, 값이 보이지 않으면 다시 실행한다.

macOS에서 Codex 앱과 Claude Code를 함께 사용한다면 다음 두 설정이 모두 필요하다.

- `~/.zshrc`: 터미널에서 실행하는 Claude Code와 Codex CLI용
- `launchctl setenv`: Dock이나 Finder에서 실행하는 Codex 앱용

## 훅 활성화 확인

1. 환경변수를 설정한 뒤 Codex와 Claude Code를 다시 시작한다.
2. Codex에서 `/hooks`를 열고 프로젝트의 `UserPromptSubmit` 훅을 검토한 뒤 신뢰한다.
3. Claude Code에서 `/hooks`를 열고 `Project` 출처의 `UserPromptSubmit` 훅이 보이는지 확인한다.
4. 각 도구에서 테스트 프롬프트를 한 번 입력한다.

저장 위치는 다음과 같다.

```text
bamsongi-brain/prompts/{BAMSONGI_MEMBER}/
└─ YYYY-MM-DD.md
```

하루 동안 입력한 프롬프트는 같은 파일에 `001`, `002`, `003` 순서로 추가된다. 화면에는 프롬프트 본문이 Markdown 인용문으로 표시된다.

Windows에서 저장 여부를 확인한다.

```powershell
Get-ChildItem `
  -LiteralPath "..\bamsongi-brain\prompts\$env:BAMSONGI_MEMBER" `
  -Filter "*.md"
```

macOS에서 저장 여부를 확인한다.

```sh
find "../bamsongi-brain/prompts/$BAMSONGI_MEMBER" \
  -type f \
  -name '????-??-??.md'
```

## Brain 저장소가 다른 위치에 있을 때

두 저장소가 형제 폴더라면 이 설정은 필요하지 않다. 다른 위치에 clone했다면 `BAMSONGI_BRAIN_ROOT`에 `bamsongi-brain`의 절대 경로를 설정한다.

Windows PowerShell:

```powershell
[Environment]::SetEnvironmentVariable(
  "BAMSONGI_BRAIN_ROOT",
  "C:\Workspace\bamsongi-brain",
  "User"
)
$env:BAMSONGI_BRAIN_ROOT = "C:\Workspace\bamsongi-brain"
```

macOS 터미널에서는 `~/.zshrc`에 다음 줄을 추가한다.

```sh
export BAMSONGI_BRAIN_ROOT="$HOME/Workspace/bamsongi-brain"
```

Codex 앱을 Dock이나 Finder에서 실행한다면 다음 설정도 추가한다.

```sh
launchctl setenv BAMSONGI_BRAIN_ROOT "$HOME/Workspace/bamsongi-brain"
```

## 문제 해결

### `BAMSONGI_MEMBER 환경변수를 먼저 설정해 주세요` 경고가 표시됨

현재 Codex 또는 Claude Code 프로세스가 환경변수를 상속하지 못한 상태다. 위 확인 명령으로 값을 확인하고 도구를 완전히 종료했다가 다시 실행한다.

### `bamsongi-brain을 찾을 수 없습니다` 경고가 표시됨

두 저장소가 형제 폴더인지 확인한다. 다른 위치에 있다면 `BAMSONGI_BRAIN_ROOT`를 설정한다. git worktree에서 실행 중이라면 기준 경로가 워크트리 폴더로 잡혀 형제 폴더 탐색이 항상 실패하므로, `BAMSONGI_BRAIN_ROOT`에 `bamsongi-brain`의 절대 경로를 설정한다.

### `Node.js 20 이상이 필요합니다` 경고가 표시됨

`node --version`으로 현재 버전을 확인하고 Node.js 20 이상으로 업데이트한다. GUI로 실행한 Codex 앱에서도 `node` 명령을 찾을 수 있어야 한다.

### Codex에서 파일이 저장되지 않음

`/hooks`에서 프로젝트 훅이 활성화되고 신뢰된 상태인지 확인한다. 변경된 훅은 다시 신뢰해야 한다.

### Claude Code에서 파일이 저장되지 않음

`/hooks`에서 `Project` 출처의 훅이 보이는지 확인한다. 환경변수를 설정하기 전에 실행한 Claude Code는 종료하고 다시 실행한다.

## 주의 사항

- `BAMSONGI_MEMBER`는 프로젝트의 `.env`나 `application.properties`가 아니라 팀원 개인의 환경변수로 설정한다.
- 한 번 설정한 `BAMSONGI_MEMBER`는 같은 환경에서 실행되는 Codex와 Claude Code가 함께 사용한다.
- 프롬프트는 비밀정보 필터 없이 Private 브레인의 **prompts/** 일별 Markdown에 저장되므로, commit 전에 키·토큰·개인정보를 사람이 확인한다.
- 훅은 프롬프트 파일만 저장한다. `git add`, commit, push는 자동으로 실행하지 않는다.

훅의 저장 방식과 환경변수 규격은 [팀 프롬프트 자동 기록](../../.bamsongi/README.md)에서 확인할 수 있다.
