import { createHash } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const [command, manifestPath, ...args] = process.argv.slice(2);

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exit(2);
}

function readManifest() {
  try {
    return JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (error) {
    fail(`manifest read failed: ${error.message}`);
  }
}

function writeManifest(manifest) {
  const temporaryPath = `${manifestPath}.tmp`;
  writeFileSync(temporaryPath, `${JSON.stringify(manifest, null, 2)}\n`);
  renameSync(temporaryPath, manifestPath);
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

if (command === 'prepare') {
  const [
    benchmarkId,
    releaseSha,
    fixtureId,
    fixtureSha256,
    scenarioSha256,
    keyword,
    baseUrl,
    profile,
    soakDuration,
    authMode,
    k6Version,
  ] = args;
  const invariants = {
    releaseSha,
    fixtureId,
    fixtureSha256,
    scenarioSha256,
    keyword,
    baseUrl,
    profile,
    soakDuration,
    authMode,
    k6Version,
  };

  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') fail(`manifest read failed: ${error.message}`);
    manifest = { version: 1, benchmarkId, invariants, runs: {} };
    writeManifest(manifest);
    process.exit(0);
  }

  if (manifest.version !== 1) fail('manifest version does not match: expected 1');
  if (manifest.benchmarkId !== benchmarkId) {
    fail('benchmarkId does not match manifest');
  }
  for (const [key, value] of Object.entries(invariants)) {
    if (manifest.invariants?.[key] !== value) {
      fail(`${key} does not match manifest`);
    }
  }
  process.exit(0);
}

if (command === 'record') {
  const [indexState, ...files] = args;
  if (files.length !== 4) fail('record requires two summary/log file pairs');
  const manifest = readManifest();
  const directory = path.dirname(manifestPath);
  const scenarios = [];

  for (let index = 0; index < files.length; index += 2) {
    const summary = files[index];
    const log = files[index + 1];
    scenarios.push({
      name: path.basename(summary).replace(/\.summary\.json$/, ''),
      summary: path.relative(directory, summary),
      summarySha256: sha256(summary),
      log: path.relative(directory, log),
      logSha256: sha256(log),
    });
  }

  manifest.runs[indexState] = { scenarios };
  writeManifest(manifest);
  process.exit(0);
}

fail('command must be prepare or record');
