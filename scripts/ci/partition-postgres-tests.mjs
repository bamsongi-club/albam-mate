import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const DEFAULT_SOURCE_DIRECTORY = fileURLToPath(
  new URL("../../src/postgresTest/java/", import.meta.url),
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

export function discoverPostgresTests(sourceDirectory = DEFAULT_SOURCE_DIRECTORY) {
  return collectTestFiles(sourceDirectory).map((filePath) => {
    const source = fs.readFileSync(filePath, "utf8");
    const packageMatch = source.match(/^package\s+([\w.]+);/m);
    if (!packageMatch) {
      throw new Error(`package 선언을 찾을 수 없습니다: ${filePath}`);
    }

    const className = path.basename(filePath, ".java");
    return {
      className: `${packageMatch[1]}.${className}`,
      relativePath: path.relative(sourceDirectory, filePath).replaceAll("\\", "/"),
      weight: fs.statSync(filePath).size,
    };
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

function main() {
  const args = process.argv.slice(2);
  const shardCount = readIntegerArgument(args, "--shard-count");
  const shardIndex = readIntegerArgument(args, "--shard-index");
  const shards = partitionPostgresTests(discoverPostgresTests(), shardCount);
  if (shardIndex < 0 || shardIndex >= shards.length) {
    throw new Error(`--shard-index는 0 이상 ${shards.length - 1} 이하여야 합니다.`);
  }

  const shard = shards[shardIndex];
  process.stderr.write(
    `PostgreSQL shard ${shard.index + 1}/${shards.length}: ${shard.tests.length} classes, weight ${shard.totalWeight}\n`,
  );
  process.stdout.write(`${shard.tests.map((postgresTest) => postgresTest.className).join("\n")}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
