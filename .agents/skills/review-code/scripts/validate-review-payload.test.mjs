import assert from 'node:assert/strict';
import test from 'node:test';

import {
    parseDiffAnchors,
    parsePayloadJson,
    ReviewPayloadValidationError,
    validateReviewPayload,
} from './validate-review-payload.mjs';

const HEAD_SHA = '0123456789abcdef0123456789abcdef01234567';
const DIFF = `diff --git a/src/example.js b/src/example.js
index 3367afd..53bcb01 100644
--- a/src/example.js
+++ b/src/example.js
@@ -1,2 +1,2 @@
-const answer = 41;
+const answer = 42;
 export { answer };
`;

function createPayload() {
    return {
        commit_id: HEAD_SHA,
        body: `## 판정: Approve

심각도: 🔴 0  🟠 0  🟡 1  ⚪ 0

변경 요약: 예제 값을 수정했습니다.

### 잘된 점

- 변경 범위가 작습니다.

### 주요 지적 (critical/major만)

없습니다.

### 다음 액션

- minor 지적을 확인합니다.`,
        event: 'COMMENT',
        comments: [
            {
                path: 'src/example.js',
                line: 1,
                side: 'RIGHT',
                body: `🟡 minor | 경계 값 검증을 추가하세요

**🔍 문제점**
입력값이 예상 범위를 벗어나도 수정된 값이 사용됩니다.

**🔧 수정 방향**
경계 값 단언을 추가하세요.`,
            },
        ],
    };
}

function validationErrors(payload, overrides = {}) {
    assert.throws(
        () =>
            validateReviewPayload({
                payload,
                expectedHeadSha: overrides.expectedHeadSha ?? HEAD_SHA,
                diffText: overrides.diffText ?? DIFF,
            }),
        (error) => {
            assert.ok(error instanceof ReviewPayloadValidationError);
            overrides.verify?.(error.errors);
            return true;
        },
    );
}

test('accepts a correctly formatted payload anchored to the current diff', () => {
    const result = validateReviewPayload({
        payload: createPayload(),
        expectedHeadSha: HEAD_SHA,
        diffText: DIFF,
    });

    assert.deepEqual(result, {
        commentCount: 1,
        severityCounts: { critical: 0, major: 0, minor: 1, nit: 0 },
        verdict: 'Approve',
    });
});

test('does not confuse changed content with diff file markers', () => {
    const diff = `diff --git a/docs/example.md b/docs/example.md
--- a/docs/example.md
+++ b/docs/example.md
@@ -1 +1 @@
--- stale option
+++ current option
`;

    const anchors = parseDiffAnchors(diff);

    assert.ok(anchors.has('LEFT\u0000docs/example.md\u00001'));
    assert.ok(anchors.has('RIGHT\u0000docs/example.md\u00001'));
});

test('accepts UTF-8 BOM and CRLF files produced by Windows PowerShell', () => {
    const payload = createPayload();
    const windowsDiff = DIFF.replaceAll('\n', '\r\n');

    assert.deepEqual(parsePayloadJson(`\uFEFF${JSON.stringify(payload)}`), payload);
    assert.ok(parseDiffAnchors(`\uFEFF${windowsDiff}`).has('RIGHT\u0000src/example.js\u00001'));
});

test('decodes Git quoted UTF-8 paths before validating anchors', () => {
    const quotedPathDiff = `diff --git "a/docs/\\355\\225\\234\\352\\270\\200.md" "b/docs/\\355\\225\\234\\352\\270\\200.md"
--- "a/docs/\\355\\225\\234\\352\\270\\200.md"
+++ "b/docs/\\355\\225\\234\\352\\270\\200.md"
@@ -1 +1 @@
-이전
+현재
`;

    const anchors = parseDiffAnchors(quotedPathDiff);

    assert.ok(anchors.has('LEFT\u0000docs/한글.md\u00001'));
    assert.ok(anchors.has('RIGHT\u0000docs/한글.md\u00001'));
});

test('rejects an inline comment that omits required formatting', () => {
    const payload = createPayload();
    payload.comments[0].body = '🟡 minor | 제목만 있습니다';

    validationErrors(payload, {
        verify: (errors) => assert.ok(errors.some((error) => error.includes('problem section'))),
    });
});

test('rejects an inline title with a mismatched severity emoji', () => {
    const payload = createPayload();
    payload.comments[0].body = payload.comments[0].body.replace('🟡 minor |', '🟠 minor |');

    validationErrors(payload, {
        verify: (errors) => assert.ok(errors.some((error) => error.includes('does not match minor'))),
    });
});

test('rejects review events that change GitHub approval state', () => {
    const payload = createPayload();
    payload.event = 'REQUEST_CHANGES';

    validationErrors(payload, {
        verify: (errors) => assert.ok(errors.includes('event must be COMMENT.')),
    });
});

test('rejects a payload built for a stale PR head', () => {
    validationErrors(createPayload(), {
        expectedHeadSha: 'fedcba9876543210fedcba9876543210fedcba98',
        verify: (errors) => assert.ok(errors.some((error) => error.includes('latest PR head SHA'))),
    });
});

test('rejects a file and line that are not in the supplied diff', () => {
    const payload = createPayload();
    payload.comments[0].line = 10;

    validationErrors(payload, {
        verify: (errors) => assert.ok(errors.some((error) => error.includes('not anchored'))),
    });
});

test('rejects a severity count that does not match the findings', () => {
    const payload = createPayload();
    payload.body = payload.body.replace('🟡 1', '🟡 0');

    validationErrors(payload, {
        verify: (errors) => assert.ok(errors.some((error) => error.includes('severity count for minor'))),
    });
});

test('counts critical and major findings from the required summary list', () => {
    const payload = createPayload();
    payload.body = payload.body
        .replace('## 판정: Approve', '## 판정: Changes Requested')
        .replace('🟠 0  🟡 1', '🟠 1  🟡 0')
        .replace('없습니다.', '- 🟠 src/example.js:1 — 정답을 상수로 바꾸지 마세요')
        .replace('- minor 지적을 확인합니다.', '- major 지적을 확인합니다.');
    payload.comments[0].body = `🟠 major | 정답을 상수로 바꾸지 마세요

**🔍 문제점**
외부 입력이 무시되어 잘못된 결과를 반환합니다.

**🔧 수정 방향**
입력값을 검증하고 그 값으로 계산하세요.`;

    const result = validateReviewPayload({ payload, expectedHeadSha: HEAD_SHA, diffText: DIFF });

    assert.deepEqual(result.severityCounts, { critical: 0, major: 1, minor: 0, nit: 0 });
    assert.equal(result.verdict, 'Changes Requested');
});

test('rejects a verdict that does not match validated severity counts', () => {
    const payload = createPayload();
    payload.body = payload.body.replace('## 판정: Approve', '## 판정: Changes Requested');

    validationErrors(payload, {
        verify: (errors) => assert.ok(errors.some((error) => error.includes('verdict must be Approve'))),
    });
});

test('rejects unsupported summary headings', () => {
    const payload = createPayload();
    payload.body = payload.body.replace(
        '### 다음 액션',
        `### 그 외 지적

- 별도 형식입니다.

### 다음 액션`,
    );

    validationErrors(payload, {
        verify: (errors) => assert.ok(errors.some((error) => error.includes('unsupported summary section'))),
    });
});

test('accepts the fixed re-review metadata and resolved section positions', () => {
    const payload = createPayload();
    payload.body = payload.body
        .replace(
            '## 판정: Approve\n\n',
            `## 판정: Approve

재리뷰 기준 커밋: \`${HEAD_SHA}\`

`,
        )
        .replace(
            '### 잘된 점',
            `### 이전 지적 해소 확인

- 이전 지적이 해소됐습니다.

### 잘된 점`,
        );

    assert.doesNotThrow(() =>
        validateReviewPayload({ payload, expectedHeadSha: HEAD_SHA, diffText: DIFF }),
    );
});

test('counts minor and nit findings moved to the unanchored summary section', () => {
    const payload = createPayload();
    payload.comments = [];
    payload.body = payload.body.replace(
        '### 다음 액션',
        `### 앵커할 수 없는 지적

- 🟡 src/example.js:1 — 경계 값 검증을 추가하세요

### 다음 액션`,
    );

    const result = validateReviewPayload({ payload, expectedHeadSha: HEAD_SHA, diffText: DIFF });

    assert.deepEqual(result.severityCounts, { critical: 0, major: 0, minor: 1, nit: 0 });
    assert.equal(result.commentCount, 0);
});

test('requires an uncovered scope section for Incomplete', () => {
    const payload = createPayload();
    payload.body = payload.body.replace('## 판정: Approve', '## 판정: Incomplete');

    validationErrors(payload, {
        verify: (errors) => assert.ok(errors.some((error) => error.includes('requires a non-empty uncovered'))),
    });
});
