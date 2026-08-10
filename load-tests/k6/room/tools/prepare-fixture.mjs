#!/usr/bin/env node

import { buildFixture, parseArguments, writeFixtureBundle } from './fixture.mjs';

function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = buildFixture(options);
  const result = writeFixtureBundle(options, fixture);
  process.stdout.write(`${JSON.stringify({
    output: result.output,
    fixtureId: result.metadata.fixtureId,
    scenario: result.metadata.scenario,
    fixtureCounts: result.metadata.fixtureCounts,
  }, null, 2)}\n`);
}

try {
  main();
} catch (error) {
  process.stderr.write(`ROOM k6 fixture 생성 실패: ${error.message}\n`);
  process.exitCode = 1;
}
