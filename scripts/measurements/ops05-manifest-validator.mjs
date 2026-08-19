#!/usr/bin/env node

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const REQUIRED_SOURCE_PATHS = [
  'load-tests/k6/jiho/notification-delivery-contract.js',
  'load-tests/k6/eungi/websocket-contract.js',
  'load-tests/k6/jiwon/t1-cancel-promotion.js',
];
const REQUIRED_ARTIFACT_FIELDS = [
  'http',
  'database',
  'metrics',
  'logs',
  'dashboard',
];
const REQUIRED_OUTCOME_FIELDS = ['attempt', 'businessSuccess', 'businessRejection', 'technicalFailure'];
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const RELEASE_SHA_PATTERN = /^[a-f0-9]{40}$/;
const UTC_TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/;

export function validateManifest(manifest, releaseRoot, bundleRoot, expectedReleaseSha) {
  const invalidReasons = [];
  const failReasons = [];
  const invalid = (reason) => invalidReasons.push(reason);
  const failed = (reason) => failReasons.push(reason);

  if (!isObject(manifest)) {
    invalid('manifest-object-required');
    return result('INVALID', invalidReasons, undefined);
  }
  if (manifest.schemaVersion !== 1) invalid('schema-version');
  const releaseHead = readReleaseHead(releaseRoot);
  if (typeof manifest.releaseSha !== 'string'
    || !RELEASE_SHA_PATTERN.test(manifest.releaseSha)
    || !RELEASE_SHA_PATTERN.test(expectedReleaseSha ?? '')
    || releaseHead === null
    || manifest.releaseSha !== expectedReleaseSha
    || expectedReleaseSha !== releaseHead) {
    invalid('release-sha');
  }
  if (!requiredSourcesAreClean(releaseRoot)) invalid('release-source-dirty');
  validateExecution(manifest.execution, invalid);
  validateFixture(manifest.fixture, invalid);
  validateSources(manifest.sources, releaseRoot, invalid);
  const artifactResults = loadArtifacts(manifest.artifacts, bundleRoot, invalid);

  const mode = manifest.mode;
  if (mode !== 'normal' && mode !== 'controlled-recovery') {
    invalid('mode');
  }
  if (invalidReasons.length > 0) {
    return result('INVALID', invalidReasons, typeof mode === 'string' ? mode : undefined);
  }

  validateWorkflows(manifest.workflows, mode, artifactResults, failed);
  return result(failReasons.length === 0 ? 'PASS' : 'FAIL', failReasons, mode);
}

function validateExecution(execution, invalid) {
  if (!isObject(execution)
    || !isUtcTimestamp(execution.startedAt)
    || !isUtcTimestamp(execution.finishedAt)
    || Date.parse(execution.finishedAt) <= Date.parse(execution.startedAt)) {
    invalid('execution-window');
  }
}

function validateFixture(fixture, invalid) {
  if (!isObject(fixture)
    || fixture.classification !== 'isolated'
    || typeof fixture.fixtureHash !== 'string'
    || !SHA256_PATTERN.test(fixture.fixtureHash)
    || fixture.hasActualUserData !== false
    || fixture.hasProductionRoomData !== false) {
    invalid('isolated-fixture');
  }
}

function validateSources(sources, repositoryRoot, invalid) {
  if (!Array.isArray(sources) || sources.length !== REQUIRED_SOURCE_PATHS.length) {
    invalid('scenario-sources');
    return;
  }
  const sourceByPath = new Map();
  for (const source of sources) {
    if (!isObject(source) || typeof source.path !== 'string' || typeof source.sha256 !== 'string') {
      invalid('scenario-source-shape');
      continue;
    }
    if (sourceByPath.has(source.path)) {
      invalid('scenario-source-duplicate');
      continue;
    }
    sourceByPath.set(source.path, source.sha256);
  }
  for (const requiredPath of REQUIRED_SOURCE_PATHS) {
    const declaredHash = sourceByPath.get(requiredPath);
    if (!SHA256_PATTERN.test(declaredHash ?? '')) {
      invalid('scenario-source-hash');
      continue;
    }
    const sourcePath = path.resolve(repositoryRoot, requiredPath);
    if (!isInside(repositoryRoot, sourcePath) || !fs.existsSync(sourcePath) || sha256File(sourcePath) !== declaredHash) {
      invalid('scenario-source-mismatch');
    }
  }
  for (const sourcePath of sourceByPath.keys()) {
    if (!REQUIRED_SOURCE_PATHS.includes(sourcePath)) invalid('scenario-source-path');
  }
}

function loadArtifacts(artifacts, bundleRoot, invalid) {
  if (!isObject(artifacts)) {
    invalid('artifacts');
    return null;
  }
  let canonicalBundleRoot;
  try {
    canonicalBundleRoot = fs.realpathSync(bundleRoot);
  } catch {
    invalid('artifact-bundle-root');
    return null;
  }
  const contents = {};
  for (const field of REQUIRED_ARTIFACT_FIELDS) {
    const artifact = artifacts[field];
    if (!isObject(artifact) || typeof artifact.path !== 'string' || typeof artifact.sha256 !== 'string'
      || !SHA256_PATTERN.test(artifact.sha256)) {
      invalid(`artifact-${field}`);
      continue;
    }
    const artifactPath = path.resolve(canonicalBundleRoot, artifact.path);
    try {
      const canonicalArtifactPath = fs.realpathSync(artifactPath);
      if (!isSafeArtifactPath(artifact.path)
        || !isInside(canonicalBundleRoot, canonicalArtifactPath)
        || fs.lstatSync(artifactPath).isSymbolicLink()
        || sha256File(canonicalArtifactPath) !== artifact.sha256) {
        invalid(`artifact-${field}`);
        continue;
      }
      contents[field] = JSON.parse(fs.readFileSync(canonicalArtifactPath, 'utf8'));
    } catch {
      invalid(`artifact-${field}`);
    }
  }
  if (Object.keys(contents).length !== REQUIRED_ARTIFACT_FIELDS.length) return null;
  return normalizeArtifactResults(contents, invalid);
}

function normalizeArtifactResults(artifacts, invalid) {
  if (REQUIRED_ARTIFACT_FIELDS.some((field) => !hasExactKeys(artifacts[field], ['notification', 'chat', 'waitingQueue']))) {
    invalid('artifact-top-level-shape');
    return null;
  }
  const workflows = {};
  for (const name of ['notification', 'chat', 'waitingQueue']) {
    const http = artifacts.http?.[name];
    const database = artifacts.database?.[name];
    const metrics = artifacts.metrics?.[name];
    const logs = artifacts.logs?.[name];
    const dashboard = artifacts.dashboard?.[name];
    const metricFields = name === 'notification'
      ? [...REQUIRED_OUTCOME_FIELDS, 'retryScheduled', 'failed', 'processed']
      : REQUIRED_OUTCOME_FIELDS;
    if (!hasExactKeys(http, ['technicalAccepted'])
      || !isObject(database) || !hasExactKeys(metrics, metricFields)
      || !isObject(logs) || !hasExactKeys(dashboard, ['userVisibleResult'])
      || typeof http.technicalAccepted !== 'boolean'
      || typeof database.businessResult !== 'boolean'
      || typeof dashboard.userVisibleResult !== 'boolean'
      || REQUIRED_OUTCOME_FIELDS.some((field) => !isNonNegativeInteger(metrics[field]))) {
      invalid(`artifact-${name}-shape`);
      continue;
    }
    workflows[name] = {
      technicalAccepted: http.technicalAccepted,
      businessResult: database.businessResult,
      userVisibleResult: dashboard.userVisibleResult,
      outcomes: Object.fromEntries(REQUIRED_OUTCOME_FIELDS.map((field) => [field, metrics[field]])),
      ...(name === 'notification'
        ? { retryScheduled: metrics.retryScheduled, failed: metrics.failed, processed: metrics.processed }
        : {}),
      ...specificArtifactResults(name, database, logs, invalid),
    };
  }
  return Object.keys(workflows).length === 3 ? workflows : null;
}

function specificArtifactResults(name, database, logs, invalid) {
  const fields = name === 'notification'
    ? ['notificationRecorded', 'inboxVisible']
    : name === 'chat'
      ? ['messageStored', 'delivered', 'reconnectRecovered']
      : ['registered', 'canceled', 'fifoPromoted', 'invariantViolations'];
  const databaseFields = name === 'notification'
    ? ['businessResult', 'notificationRecorded']
    : name === 'chat'
      ? ['businessResult', 'messageStored']
      : ['businessResult', 'registered', 'canceled', 'fifoPromoted', 'invariantViolations'];
  const logFields = name === 'notification'
    ? ['inboxVisible', 'followUpSucceeded']
    : name === 'chat'
      ? ['delivered', 'reconnectRecovered', 'followUpSucceeded']
      : ['followUpSucceeded'];
  if (!hasExactKeys(database, databaseFields) || !hasExactKeys(logs, logFields)) {
    invalid(`artifact-${name}-shape`);
  }
  const values = {};
  for (const field of fields) {
    const source = ['inboxVisible', 'delivered', 'reconnectRecovered'].includes(field) ? logs : database;
    const value = source[field];
    if ((field === 'invariantViolations' && !isNonNegativeInteger(value))
      || (field !== 'invariantViolations' && typeof value !== 'boolean')) {
      invalid(`artifact-${name}-shape`);
    }
    values[field] = value;
  }
  if (typeof logs.followUpSucceeded !== 'boolean') invalid(`artifact-${name}-shape`);
  values.followUpSucceeded = logs.followUpSucceeded;
  return values;
}

function validateWorkflows(workflows, mode, artifactResults, failed) {
  if (!isObject(workflows)) {
    failed('workflows');
    return;
  }
  validateNotification(workflows.notification, mode, failed);
  validateChat(workflows.chat, mode, failed);
  validateWaitingQueue(workflows.waitingQueue, mode, failed);
  if (artifactResults === null) {
    failed('artifact-results');
    return;
  }
  for (const name of ['notification', 'chat', 'waitingQueue']) {
    compareArtifactSummary(name, workflows[name], artifactResults[name], failed);
  }
}

function compareArtifactSummary(name, summary, artifact, failed) {
  const fields = ['technicalAccepted', 'businessResult', 'userVisibleResult', ...REQUIRED_OUTCOME_FIELDS];
  if (name === 'notification') fields.push('notificationRecorded', 'inboxVisible', 'retryScheduled', 'failed', 'processed');
  if (name === 'chat') fields.push('messageStored', 'delivered', 'reconnectRecovered');
  if (name === 'waitingQueue') fields.push('registered', 'canceled', 'fifoPromoted', 'invariantViolations');
  if (!isObject(summary) || !isObject(artifact)
    || fields.some((field) => valueAt(summary, field) !== valueAt(artifact, field))) {
    failed(`${name}-artifact-summary`);
  }
  if (summary?.followUpSucceeded !== undefined && summary.followUpSucceeded !== artifact.followUpSucceeded) {
    failed(`${name}-artifact-summary`);
  }
}

function valueAt(workflow, field) {
  return REQUIRED_OUTCOME_FIELDS.includes(field) ? workflow?.outcomes?.[field] : workflow?.[field];
}

function validateNotification(workflow, mode, failed) {
  validateCommonWorkflow('notification', workflow, mode, failed);
  if (!isObject(workflow) || workflow.notificationRecorded !== true || workflow.inboxVisible !== true) {
    failed('notification-final-result');
  }
}

function validateChat(workflow, mode, failed) {
  validateCommonWorkflow('chat', workflow, mode, failed);
  if (!isObject(workflow)
    || workflow.messageStored !== true
    || workflow.delivered !== true
    || workflow.reconnectRecovered !== true) {
    failed('chat-final-result');
  }
}

function validateWaitingQueue(workflow, mode, failed) {
  validateCommonWorkflow('waiting-queue', workflow, mode, failed);
  if (!isObject(workflow)
    || workflow.registered !== true
    || workflow.canceled !== true
    || workflow.fifoPromoted !== true
    || workflow.invariantViolations !== 0) {
    failed('waiting-queue-final-result');
  }
}

function validateCommonWorkflow(name, workflow, mode, failed) {
  if (!isObject(workflow)
    || workflow.technicalAccepted !== true
    || workflow.businessResult !== true
    || workflow.userVisibleResult !== true) {
    failed(`${name}-result-stage`);
    return;
  }
  const outcomes = workflow.outcomes;
  if (!isObject(outcomes) || REQUIRED_OUTCOME_FIELDS.some((field) => !isNonNegativeInteger(outcomes[field]))) {
    failed(`${name}-outcomes`);
    return;
  }
  if (outcomes.attempt < 1 || outcomes.businessSuccess < 1) {
    failed(`${name}-zero-work`);
  }
  if (outcomes.attempt !== outcomes.businessSuccess + outcomes.businessRejection + outcomes.technicalFailure) {
    failed(`${name}-outcome-total`);
  }
  if (name === 'notification'
    && (outcomes.businessRejection !== 0
      || outcomes.businessSuccess !== workflow.processed
      || outcomes.technicalFailure !== workflow.retryScheduled + workflow.failed
      || outcomes.attempt !== workflow.processed + workflow.retryScheduled + workflow.failed)) {
    failed('notification-relay-outcomes');
  }
  if (mode === 'normal' && (outcomes.businessRejection !== 0 || outcomes.technicalFailure !== 0)) {
    failed(`${name}-unexpected-failure`);
  }
  if (mode === 'controlled-recovery' && name === 'notification'
    && (outcomes.businessRejection !== 0 || outcomes.technicalFailure < 1
      || workflow.retryScheduled < 1 || workflow.failed < 1 || workflow.processed < 1
      || workflow.followUpSucceeded !== true)) {
    failed(`${name}-recovery-separation`);
  }
  if (mode === 'controlled-recovery' && name !== 'notification'
    && (outcomes.businessRejection < 1 || outcomes.technicalFailure < 1 || workflow.followUpSucceeded !== true)) {
    failed(`${name}-recovery-separation`);
  }
}

function result(verdict, reasons, mode) {
  return {
    verdict,
    ...(mode === undefined ? {} : { mode }),
    validatedWorkflowCount: verdict === 'INVALID' ? 0 : 3,
    reasons: [...new Set(reasons)].sort(),
  };
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function hasExactKeys(value, keys) {
  return isObject(value)
    && Object.keys(value).length === keys.length
    && keys.every((key) => Object.hasOwn(value, key));
}

function isNonNegativeInteger(value) {
  return Number.isInteger(value) && value >= 0;
}

function isUtcTimestamp(value) {
  return typeof value === 'string' && UTC_TIMESTAMP_PATTERN.test(value) && !Number.isNaN(Date.parse(value));
}

function isInside(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative !== '' && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}

function isSafeArtifactPath(value) {
  return value.split('/').every((segment) => /^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(segment));
}

function sha256File(filePath) {
  return createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

function readReleaseHead(releaseRoot) {
  const result = spawnSync('git', ['-C', releaseRoot, 'rev-parse', 'HEAD'], { encoding: 'utf8' });
  if (result.error || result.status !== 0) return null;
  const head = result.stdout.trim();
  return RELEASE_SHA_PATTERN.test(head) ? head : null;
}

function requiredSourcesAreClean(releaseRoot) {
  const result = spawnSync('git', ['-C', releaseRoot, 'diff', '--quiet', 'HEAD', '--', ...REQUIRED_SOURCE_PATHS], {
    encoding: 'utf8',
  });
  return !result.error && result.status === 0;
}

function parseArguments(argumentsList) {
  let manifestPath;
  let releaseRoot;
  let bundleRoot;
  let expectedReleaseSha;
  for (let index = 0; index < argumentsList.length; index += 2) {
    const option = argumentsList[index];
    const value = argumentsList[index + 1];
    if (value === undefined) throw new Error('argument-value-required');
    if (option === '--manifest') manifestPath = value;
    else if (option === '--release-root') releaseRoot = value;
    else if (option === '--bundle-root') bundleRoot = value;
    else if (option === '--expected-release-sha') expectedReleaseSha = value;
    else throw new Error('unknown-argument');
  }
  if (manifestPath === undefined || releaseRoot === undefined || bundleRoot === undefined || expectedReleaseSha === undefined) {
    throw new Error('required-argument-missing');
  }
  return {
    manifestPath,
    releaseRoot: path.resolve(releaseRoot),
    bundleRoot: path.resolve(bundleRoot),
    expectedReleaseSha,
  };
}

function main() {
  try {
    const { manifestPath, releaseRoot, bundleRoot, expectedReleaseSha } = parseArguments(process.argv.slice(2));
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    process.stdout.write(`${JSON.stringify(validateManifest(manifest, releaseRoot, bundleRoot, expectedReleaseSha))}\n`);
  } catch (error) {
    process.stderr.write(`OPS-05 manifest validation could not start: ${error?.code ?? error?.name ?? 'invalid-input'}.\n`);
    process.exitCode = 2;
  }
}

if (process.argv[1] !== undefined && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	main();
}
