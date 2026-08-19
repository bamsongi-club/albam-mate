import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { discoverPostgresRegressionTests } from "./partition-postgres-tests.mjs";

function collectXmlFiles(directory) {
  return fs
    .readdirSync(directory, { withFileTypes: true })
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap((entry) => {
      const entryPath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        return collectXmlFiles(entryPath);
      }
      return entry.isFile() && entry.name.startsWith("TEST-") && entry.name.endsWith(".xml")
        ? [entryPath]
        : [];
    });
}

function readSuite(filePath) {
  const xml = fs.readFileSync(filePath, "utf8");
  const openingTag = xml.match(/<testsuite\b[^>]*>/)?.[0];
  const name = openingTag?.match(/\bname="([^"]+)"/)?.[1];
  const seconds = Number(openingTag?.match(/\btime="([^"]+)"/)?.[1]);
  if (!name || !Number.isFinite(seconds) || seconds < 0) {
    throw new Error(`JUnit testsuite name/time을 읽지 못했습니다: ${filePath}`);
  }
  return { name, durationMs: Math.max(1, Math.round(seconds * 1_000)) };
}

function canonicalPath(directory) {
  const resolved = fs.realpathSync(directory);
  return process.platform === "win32" ? resolved.toLowerCase() : resolved;
}

export function buildDurationManifest(resultDirectories, expectedClassNames) {
  if (resultDirectories.length !== 3) {
    throw new Error("--results <JUnit 결과 디렉터리>가 정확히 3개 필요합니다.");
  }
  const canonicalDirectories = resultDirectories.map(canonicalPath);
  if (new Set(canonicalDirectories).size !== canonicalDirectories.length) {
    throw new Error("--results는 서로 다른 실행 결과 디렉터리여야 합니다.");
  }
  if (!Array.isArray(expectedClassNames) || expectedClassNames.length === 0) {
    throw new Error("PostgreSQL regression testsuite inventory가 필요합니다.");
  }
  const expectedClasses = new Set(expectedClassNames);
  if (expectedClasses.size !== expectedClassNames.length) {
    throw new Error("PostgreSQL regression testsuite inventory에 중복이 있습니다.");
  }
  const samples = new Map();
  for (const directory of resultDirectories) {
    const suites = collectXmlFiles(directory).map(readSuite);
    if (suites.length === 0) {
      throw new Error(`JUnit XML을 찾지 못했습니다: ${directory}`);
    }
    const names = new Set();
    for (const suite of suites) {
      if (names.has(suite.name)) {
        throw new Error(`한 실행에 같은 testsuite가 중복됐습니다: ${suite.name}`);
      }
      names.add(suite.name);
      const durations = samples.get(suite.name) ?? [];
      durations.push(suite.durationMs);
      samples.set(suite.name, durations);
    }
    const missing = [...expectedClasses].filter((className) => !names.has(className));
    if (missing.length > 0) {
      throw new Error(`기존 regression testsuite가 누락됐습니다: ${missing.join(", ")}`);
    }
    const unknown = [...names].filter((className) => !expectedClasses.has(className));
    if (unknown.length > 0) {
      throw new Error(`regression inventory에 없는 testsuite가 있습니다: ${unknown.join(", ")}`);
    }
  }

  const incomplete = [...samples]
    .filter(([, durations]) => durations.length !== resultDirectories.length)
    .map(([className]) => className);
  if (incomplete.length > 0) {
    throw new Error(`모든 실행에 존재하지 않는 testsuite가 있습니다: ${incomplete.join(", ")}`);
  }

  const durationsMs = Object.fromEntries(
    [...samples]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([className, durations]) => {
        const ordered = [...durations].sort((left, right) => left - right);
        return [className, ordered[Math.floor(ordered.length / 2)]];
      }),
  );
  return {
    schemaVersion: 1,
    sourceRuns: resultDirectories.map((directory) => path.basename(path.resolve(directory))),
    durationsMs,
  };
}

function readRepeatedArgument(args, name) {
  return args.flatMap((value, index) => value === name && args[index + 1] ? [args[index + 1]] : []);
}

function readArgument(args, name) {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

function main() {
  const args = process.argv.slice(2);
  const resultDirectories = readRepeatedArgument(args, "--results");
  const outputPath = readArgument(args, "--output");
  if (!outputPath) {
    throw new Error("--output <duration manifest 경로>가 필요합니다.");
  }
  const expectedClassNames = discoverPostgresRegressionTests().map(({ className }) => className);
  const manifest = buildDurationManifest(resultDirectories, expectedClassNames);
  fs.writeFileSync(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
