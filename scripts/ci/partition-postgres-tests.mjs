import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const DEFAULT_SOURCE_DIRECTORY = fileURLToPath(
  new URL("../../src/postgresTest/java/", import.meta.url),
);
const DEFAULT_DURATION_MANIFEST = fileURLToPath(
  new URL("./postgres-test-durations.json", import.meta.url),
);

function collectTestFiles(directory) {
  return fs
    .readdirSync(directory, { withFileTypes: true })
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap((entry) => {
      const entryPath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        return collectTestFiles(entryPath);
      }
      return entry.isFile() && entry.name.endsWith("Test.java") ? [entryPath] : [];
    });
}

function readDurationManifest(manifestPath) {
  if (!fs.existsSync(manifestPath)) {
    return new Map();
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  if (manifest.schemaVersion !== 1 || typeof manifest.durationsMs !== "object") {
    throw new Error(`PostgreSQL duration manifest 형식이 올바르지 않습니다: ${manifestPath}`);
  }
  return new Map(
    Object.entries(manifest.durationsMs).map(([className, durationMs]) => {
      if (!Number.isFinite(durationMs) || durationMs <= 0) {
        throw new Error(`PostgreSQL duration은 양수여야 합니다: ${className}`);
      }
      return [className, durationMs];
    }),
  );
}

function isMeasurementOnly(source, className) {
  const classIndex = source.indexOf(`class ${className}`);
  if (classIndex < 0) {
    throw new Error(`테스트 class 선언을 찾을 수 없습니다: ${className}`);
  }
  return /@Tag\(\s*"measurement"\s*\)/.test(source.slice(0, classIndex));
}

export function discoverPostgresTests(
  sourceDirectory = DEFAULT_SOURCE_DIRECTORY,
  durationManifestPath = DEFAULT_DURATION_MANIFEST,
) {
  const durations = readDurationManifest(durationManifestPath);
  const discovered = discoverPostgresRegressionTests(sourceDirectory);

  const knownRatios = discovered
    .filter((postgresTest) => durations.has(postgresTest.className))
    .map((postgresTest) => durations.get(postgresTest.className) / postgresTest.sourceBytes)
    .sort((left, right) => left - right);
  const fallbackMsPerByte = knownRatios.length === 0
    ? 1
    : knownRatios[Math.floor(knownRatios.length / 2)];

  return discovered.map((postgresTest) => ({
    ...postgresTest,
    durationMs: durations.get(postgresTest.className),
    weight: durations.get(postgresTest.className)
      ?? Math.max(1, Math.round(postgresTest.sourceBytes * fallbackMsPerByte)),
    weightSource: durations.has(postgresTest.className) ? "junit-median" : "source-size-fallback",
  }));
}

export function discoverPostgresRegressionTests(sourceDirectory = DEFAULT_SOURCE_DIRECTORY) {
  return collectTestFiles(sourceDirectory).flatMap((filePath) => {
    const source = fs.readFileSync(filePath, "utf8");
    const packageMatch = source.match(/^package\s+([\w.]+);/m);
    if (!packageMatch) {
      throw new Error(`package 선언을 찾을 수 없습니다: ${filePath}`);
    }

    const className = path.basename(filePath, ".java");
    if (isMeasurementOnly(source, className)) {
      return [];
    }
    const qualifiedClassName = `${packageMatch[1]}.${className}`;
    return [{
      className: qualifiedClassName,
      relativePath: path.relative(sourceDirectory, filePath).replaceAll("\\", "/"),
      sourceBytes: fs.statSync(filePath).size,
    }];
  });
}

export function partitionPostgresTests(tests, shardCount) {
  if (!Number.isInteger(shardCount) || shardCount < 1) {
    throw new Error("--shard-count는 1 이상의 정수여야 합니다.");
  }
  if (tests.length < shardCount) {
    throw new Error("PostgreSQL 테스트 수보다 shard 수가 많습니다.");
  }

  const shards = Array.from({ length: shardCount }, (_, index) => ({
    index,
    tests: [],
    totalWeight: 0,
  }));
  const orderedTests = [...tests].sort(
    (left, right) => right.weight - left.weight || left.className.localeCompare(right.className),
  );

  for (const postgresTest of orderedTests) {
    const target = [...shards].sort(
      (left, right) =>
        left.totalWeight - right.totalWeight ||
        left.tests.length - right.tests.length ||
        left.index - right.index,
    )[0];
    target.tests.push(postgresTest);
    target.totalWeight += postgresTest.weight;
  }

  for (const shard of shards) {
    shard.tests.sort((left, right) => left.className.localeCompare(right.className));
  }
  return shards;
}

function readIntegerArgument(args, name) {
  const index = args.indexOf(name);
  if (index < 0 || !args[index + 1]) {
    throw new Error(`${name} <정수>가 필요합니다.`);
  }
  const value = Number(args[index + 1]);
  if (!Number.isInteger(value)) {
    throw new Error(`${name}는 정수여야 합니다.`);
  }
  return value;
}

function readOptionalArgument(args, name, fallback) {
  const index = args.indexOf(name);
  return index < 0 ? fallback : args[index + 1];
}

function main() {
  const args = process.argv.slice(2);
  const shardCount = readIntegerArgument(args, "--shard-count");
  const shardIndex = readIntegerArgument(args, "--shard-index");
  const durationManifestPath = readOptionalArgument(
    args,
    "--duration-manifest",
    DEFAULT_DURATION_MANIFEST,
  );
  const shards = partitionPostgresTests(
    discoverPostgresTests(DEFAULT_SOURCE_DIRECTORY, durationManifestPath),
    shardCount,
  );
  if (shardIndex < 0 || shardIndex >= shards.length) {
    throw new Error(`--shard-index는 0 이상 ${shards.length - 1} 이하여야 합니다.`);
  }

  const shard = shards[shardIndex];
  process.stderr.write(
    `PostgreSQL shard ${shard.index + 1}/${shards.length}: ${shard.tests.length} classes, estimated ${shard.totalWeight}ms\n`,
  );
  process.stdout.write(`${shard.tests.map((postgresTest) => postgresTest.className).join("\n")}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
