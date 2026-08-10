# Load Tests

이 폴더는 [k6](https://k6.io/) 기반 부하테스트 스크립트를 담는다. `./gradlew test`가 검증하는 CI 게이트가 아니라, 필요할 때 수동 또는 별도 인프라에서 실행하는 성능 측정 도구다.

## 구조

```
load-tests/
  k6/
    chat.js         채팅 API·WebSocket 부하 시나리오 (mode 기반)
    fixtures/
      chat-users.example.json   fixture 형식 예시 (실행 불가, 실제 비밀번호 없음)
```

## 실행 방법

전체 실행 절차의 정본은 [Chat K6 검증 실행안](../docs/measurements/chat-k6.md)이다. Terraform 검증 스택 준비, mode별 사전 조건, 명령과 지표 판정 기준을 그 문서를 따른다.

애플리케이션이나 인프라에 요청 없이 mode별 옵션·문법만 로컬에서 확인하려면 다음을 실행한다.

```sh
k6 inspect -e K6_CHAT_MODE=send load-tests/k6/chat.js
```

k6 명령 설치는 [k6 설치 문서](https://grafana.com/docs/k6/latest/set-up/install-k6/)를 따른다.

## fixture

실제 fixture(계정 이메일·비밀번호·`roomId`)는 Git에 커밋하지 않는다. `.gitignore`가 `load-tests/k6/fixtures/*.json`을 무시하고 `chat-users.example.json` 예시 파일만 추적한다.

로컬에서 fixture가 필요하면 예시 파일을 복사해 실제 값으로 채운 뒤 저장소 밖 안전한 경로에 둔다. fixture가 만족해야 하는 조건(로그인 가능한 테스트 계정, 방 참가자 요건, 전송 한도 등)은 [Chat K6 검증 실행안의 비밀 fixture 절](../docs/measurements/chat-k6.md#비밀-fixture)을 따른다.
