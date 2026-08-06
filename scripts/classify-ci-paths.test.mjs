import assert from "node:assert/strict";
import test from "node:test";

import { classifyCiPaths } from "./classify-ci-paths.mjs";

test("문서 변경은 backend와 frontend를 실행하지 않는다", () => {
  assert.deepEqual(
    classifyCiPaths([
      "README.md",
      "docs/guides/TESTING.md",
      "src/test/AGENTS.md",
      ".github/ISSUE_TEMPLATE/docs.yml",
      "scripts/check-doc-links.test.mjs",
    ]),
    { backend: false, frontend: false },
  );
});

test("frontend 변경은 frontend만 실행한다", () => {
  assert.deepEqual(
    classifyCiPaths(["frontend/src/App.tsx", "frontend/README.md"]),
    { backend: false, frontend: true },
  );
});

test("backend와 빌드 변경은 backend를 실행한다", () => {
  assert.deepEqual(classifyCiPaths(["src/main/java/example/App.java"]), {
    backend: true,
    frontend: false,
  });
  assert.deepEqual(classifyCiPaths(["build.gradle"]), {
    backend: true,
    frontend: false,
  });
  assert.deepEqual(classifyCiPaths([".github/workflows/ci.yml"]), {
    backend: true,
    frontend: false,
  });
});

test("혼합 변경은 필요한 검증을 모두 실행한다", () => {
  assert.deepEqual(
    classifyCiPaths(["frontend/src/App.tsx", "src/main/java/example/App.java"]),
    { backend: true, frontend: true },
  );
});

test("빈 목록과 수동 실행은 전체 검증으로 안전하게 폴백한다", () => {
  assert.deepEqual(classifyCiPaths([]), { backend: true, frontend: true });
  assert.deepEqual(classifyCiPaths(["docs/README.md"], { forceAll: true }), {
    backend: true,
    frontend: true,
  });
});

test("Windows 경로 구분자를 정규화한다", () => {
  assert.deepEqual(classifyCiPaths(["frontend\\src\\App.tsx"]), {
    backend: false,
    frontend: true,
  });
});
