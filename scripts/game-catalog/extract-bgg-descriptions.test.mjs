import assert from "node:assert/strict";
import test from "node:test";

import { decodeEntities } from "./extract-bgg-descriptions.mjs";

test("BGG 명명 엔티티와 숫자 참조를 복원하되 이중 이스케이프는 재디코드하지 않는다", () => {
    assert.equal(
        decodeEntities("It&rsquo;s fun &mdash; really &#x2026; &#38;lt; &amp;mdash;"),
        "It’s fun — really … &lt; &mdash;",
    );
});

test("BGG에서 자주 쓰는 이름 참조를 보존된 기호로 변환한다", () => {
    assert.equal(
        decodeEntities("&ldquo;Title&rdquo; &ndash; 2&nbsp;players &bull; 3&times;"),
        "“Title” – 2\u00a0players • 3×",
    );
});
