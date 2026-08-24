# 팀 프롬프트 자동 기록

이 폴더의 Node.js 훅은 `albam-mate`에서 Codex 또는 Claude Code에 직접 입력한 프롬프트를 형제 저장소 `bamsongi-brain`의 팀원별 일일 Markdown 파일에 저장한다.

팀원별 최초 설정은 [How to 팀 프롬프트 기록 환경 설정](../docs/guides/PROMPT_LOGGING.md)을 따른다. 반복 확인 명령은 [Albam Mate Commands](../docs/COMMANDS.md)에 정리한다.

## 환경변수

| 이름 | 필수 여부 | 설명 |
|---|---|---|
| `BAMSONGI_MEMBER` | 필수 | 저장할 팀원 폴더 이름. 1~64자의 문자, 숫자, 점, 밑줄, 하이픈을 사용할 수 있다. |
| `BAMSONGI_BRAIN_ROOT` | 선택 | `bamsongi-brain` 경로. 없으면 프로젝트의 형제 폴더 `../bamsongi-brain`을 사용한다. |

Node.js 20 이상이 필요하다.

## 저장 규칙

```text
bamsongi-brain/prompts/{BAMSONGI_MEMBER}/
└─ YYYY-MM-DD.md
```

- 파일 안에는 날짜 제목을 한 번 쓰고 프롬프트를 `001`, `002` 순서로 누적한다.
- 프롬프트 본문의 글자와 줄 순서를 유지하면서 각 줄을 Markdown 인용문으로 표시한다.
- 도구, 세션, turn과 본문이 모두 같은 이벤트는 숨은 HTML 주석으로 식별해 중복 저장하지 않는다.
- Codex와 Claude Code가 동시에 기록해도 순번이 충돌하지 않도록 일별 잠금 파일을 사용한다.
- 최종 저장 경로가 `bamsongi-brain/prompts/` 안인지 검사한다.
- 오류는 도구에 경고로 반환하고 사용자 프롬프트 제출은 막지 않는다.

## 자동화 범위

훅은 프롬프트 파일 저장까지만 수행한다. 비밀정보 필터링과 `git add`, commit, push는 실행하지 않는다. 사람이 키, 토큰, 개인정보 포함 여부를 확인한 뒤 Git 작업을 결정한다.
