# 코드 포맷과 Git hook 설정

Java 코드의 포맷 정본은 Spotless가 실행하는 Google Java Format AOSP 스타일이다. AOSP 스타일은 기본 Google Java Style의 2칸 대신 블록 들여쓰기에 4칸을 사용한다.

pre-commit hook은 다음 순서로 커밋을 검사한다.

1. staged 파일의 후행 공백과 잘못된 공백을 `git diff --cached --check`로 검사한다.
2. 저장소 전체의 Java 포맷을 `spotlessCheck`로 검사한다.
3. 위반이 있으면 커밋을 중단하고 `spotlessApply` 실행 방법을 안내한다.

hook은 파일을 자동 수정하거나 stage하지 않는다. 자동 수정이 기존의 unstaged 변경을 커밋에 섞지 않도록 수정과 검토를 명시적인 단계로 남겨 둔다.

## clone별 최초 설정

Git의 `core.hooksPath`는 저장소에 commit되지 않는 clone별 설정이므로 clone한 뒤 한 번 활성화해야 한다.

Windows PowerShell:

```powershell
.\scripts\install-git-hooks.ps1
```

macOS, Linux, WSL:

```sh
sh ./scripts/install-git-hooks.sh
```

두 설치 스크립트는 현재 저장소를 확인한 뒤 `core.hooksPath=.githooks`를 설정한다. POSIX용 스크립트는 macOS와 Linux에서 `.githooks/pre-commit`과 `gradlew`에 실행 권한도 부여한다.

설정 결과를 확인한다.

```sh
git config --local --get core.hooksPath
git hook run pre-commit
```

첫 번째 명령은 `.githooks`를 출력해야 하고, 두 번째 명령은 staged 공백 검사와 Spotless 검사를 통과해야 한다.

## 수동 실행

반복해서 사용하는 운영체제별 명령은 [Albam Mate Commands](../COMMANDS.md)를 따른다.

포맷 자동 수정 후에는 diff를 확인하고 원하는 변경만 stage한 다음 `spotlessCheck`를 다시 실행한다.
