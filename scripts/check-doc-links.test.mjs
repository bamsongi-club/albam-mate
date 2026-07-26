// scripts/check-doc-links.mjs의 회귀 입력을 고정한다.
// 정상 문서 한 벌만 통과하는 검사는 게이트가 아무 링크도 못 뽑아도 초록불이 되므로,
// 깨진 링크·앵커와 문법 경계를 함께 단언하고 검사한 링크 수도 확인한다.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { anchorsIn, linksIn, runCheck } from './check-doc-links.mjs';

// 임시 저장소 루트에 fixture 문서를 쓰고 검사 결과를 돌려준다.
// `sources`를 주면 검사 원본 목록을 직접 지정한다. 인덱스에만 남은 경로를 흉내낼 때 쓴다.
function check(t, documents, sources) {
    const repoRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'check-doc-links-'));
    t.after(() => fs.rmSync(repoRoot, { recursive: true, force: true }));

    for (const [name, content] of Object.entries(documents)) {
        const absolute = path.join(repoRoot, name);
        fs.mkdirSync(path.dirname(absolute), { recursive: true });
        fs.writeFileSync(absolute, content, 'utf8');
    }

    return runCheck({ repoRoot, files: sources ?? Object.keys(documents) });
}

const kinds = (result) => result.problems.map((problem) => problem.kind);

test('정상 경로와 앵커는 문제를 만들지 않는다', (t) => {
    const result = check(t, {
        'a.md': '# 첫 문서\n\n[둘째 제목으로](b.md#둘째-제목)\n',
        'b.md': '# 첫째 제목\n\n## 둘째 제목\n',
    });

    assert.deepEqual(result.problems, []);
    assert.equal(result.checkedLinks, 1);
});

test('없는 파일을 가리키면 없는 파일로 보고한다', (t) => {
    const result = check(t, { 'a.md': '[없음](missing.md)\n' });

    assert.deepEqual(kinds(result), ['없는 파일']);
    assert.equal(result.checkedLinks, 1);
});

test('없는 앵커를 가리키면 없는 앵커로 보고한다', (t) => {
    const result = check(t, {
        'a.md': '[앵커](b.md#없는-제목)\n',
        'b.md': '# 있는 제목\n',
    });

    assert.deepEqual(kinds(result), ['없는 앵커']);
});

test('같은 제목이 반복되면 -1 앵커를 인정하고 없는 번호는 보고한다', (t) => {
    const result = check(t, {
        'a.md': '[첫](b.md#같은-제목)\n[둘](b.md#같은-제목-1)\n[셋](b.md#같은-제목-2)\n',
        'b.md': '# 같은 제목\n\n# 같은 제목\n',
    });

    assert.deepEqual(kinds(result), ['없는 앵커']);
    assert.match(result.problems[0].detail, /같은-제목-2/);
    assert.equal(result.checkedLinks, 3);
});

test('코드 펜스 안의 링크는 검사하지 않는다', (t) => {
    const result = check(t, { 'a.md': '~~~text\n[없음](missing.md)\n~~~\n' });

    assert.deepEqual(result.problems, []);
    assert.equal(result.checkedLinks, 0);
});

test('인라인 코드 안의 링크는 검사하지 않는다', (t) => {
    const result = check(t, { 'a.md': '예시는 `[없음](missing.md)` 형식이다.\n' });

    assert.deepEqual(result.problems, []);
    assert.equal(result.checkedLinks, 0);
});

test('여는 백틱과 길이가 다른 런은 코드 스팬을 닫지 않는다', () => {
    assert.deepEqual(linksIn('``[없음](missing.md)``').targets, []);
    assert.deepEqual(linksIn('`코드` [정상](b.md) `코드`').targets, ['b.md']);
});

test('선택 title이 붙은 링크의 깨진 대상도 탐지한다', (t) => {
    const withSingleQuote = check(t, { 'a.md': "[없음](missing.md '설명')\n" });
    assert.deepEqual(kinds(withSingleQuote), ['없는 파일']);
    assert.equal(withSingleQuote.checkedLinks, 1);

    const withDoubleQuote = check(t, { 'a.md': '[없음](missing.md "설명")\n' });
    assert.deepEqual(kinds(withDoubleQuote), ['없는 파일']);
});

test('균형 잡힌 괄호가 있는 경로를 자르지 않는다', (t) => {
    const result = check(t, {
        'guides/(draft).md': '# 초안\n',
        'a.md': '[초안](guides/(draft).md)\n',
    });

    assert.deepEqual(result.problems, []);
    assert.equal(result.checkedLinks, 1);
});

test('꺾쇠 destination의 공백 포함 경로를 처리한다', (t) => {
    const result = check(t, {
        'a b.md': '# 공백 제목\n',
        'main.md': '[공백](<a b.md>)\n',
    });

    assert.deepEqual(result.problems, []);
    assert.equal(result.checkedLinks, 1);
});

test('destination을 뽑지 못하면 파싱 실패로 보고한다', (t) => {
    const result = check(t, { 'a.md': '[닫히지 않음](unclosed\n' });

    assert.deepEqual(kinds(result), ['파싱 실패']);
    assert.equal(result.checkedLinks, 0);
});

test('스테이징 전 이동·삭제로 사라진 원본은 건너뛰고 남은 참조는 보고한다', (t) => {
    const result = check(t, { 'kept.md': '[사라진 문서](gone.md)\n' }, ['kept.md', 'gone.md']);

    assert.deepEqual(result.sources, ['kept.md']);
    assert.deepEqual(kinds(result), ['없는 파일']);
});

test('참조 정의의 대상도 검사한다', (t) => {
    const result = check(t, {
        'a.md': '[라벨]: missing.md\n',
    });

    assert.deepEqual(kinds(result), ['없는 파일']);
    assert.equal(result.checkedLinks, 1);
});

test('외부 링크와 문서 내부 앵커만 있는 링크는 파일 검사를 건너뛴다', (t) => {
    const result = check(t, {
        'a.md': '# 제목\n\n[외부](https://example.com/a.md)\n[내부](#제목)\n',
    });

    assert.deepEqual(result.problems, []);
    assert.equal(result.checkedLinks, 1);
});

test('앵커 규칙은 인라인 코드와 기호를 제거하고 공백을 하이픈으로 바꾼다', () => {
    const anchors = anchorsIn('# 1. 공통 계약\n\n### `RoomStatus` 값\n');

    assert.ok(anchors.has('1-공통-계약'));
    assert.ok(anchors.has('roomstatus-값'));
});
