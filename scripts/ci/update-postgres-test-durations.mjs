import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

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

export function buildDurationManifest(resultDirectories) {
  if (resultDirectories.length !== 3) {
    throw new Error("--results <JUnit 결과 디렉터리>가 정확히 3개 필요합니다.");
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
  const manifest = buildDurationManifest(resultDirectories);
  fs.writeFileSync(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
