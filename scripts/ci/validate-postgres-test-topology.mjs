import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const DEFAULT_SOURCE_DIRECTORY = fileURLToPath(
  new URL("../../src/postgresTest/java/", import.meta.url),
);
const DEFAULT_MANIFEST = fileURLToPath(new URL("./postgres-test-topology.json", import.meta.url));

function collectTestFiles(directory) {
  return fs
    .readdirSync(directory, { withFileTypes: true })
    .flatMap((entry) => {
      const entryPath = path.join(directory, entry.name);
      return entry.isDirectory() ? collectTestFiles(entryPath) : [entryPath];
    })
    .filter((filePath) => filePath.endsWith("Test.java"))
    .sort();
}

function discoverTests(sourceDirectory) {
  return new Map(collectTestFiles(sourceDirectory).map((filePath) => {
    const source = fs.readFileSync(filePath, "utf8");
    const packageName = source.match(/^package\s+([\w.]+);/m)?.[1];
    if (!packageName) {
      throw new Error(`package 선언을 찾을 수 없습니다: ${filePath}`);
    }
    const className = path.basename(filePath, ".java");
    return [`${packageName}.${className}`, { filePath, source }];
  }));
}

export function validatePostgresTestTopology(
  sourceDirectory = DEFAULT_SOURCE_DIRECTORY,
  manifestPath = DEFAULT_MANIFEST,
) {
  const tests = discoverTests(sourceDirectory);
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.shared)
    || typeof manifest.dedicated !== "object" || manifest.dedicated === null) {
    throw new Error("PostgreSQL topology manifest 형식이 올바르지 않습니다.");
  }

  const shared = new Set(manifest.shared);
  if (shared.size !== manifest.shared.length) {
    throw new Error("shared manifest에 중복 테스트가 있습니다.");
  }
  const dedicated = new Map(Object.entries(manifest.dedicated));
  const overlap = [...shared].filter((className) => dedicated.has(className));
  if (overlap.length > 0) {
    throw new Error(`shared와 dedicated에 중복 등록된 테스트가 있습니다: ${overlap.join(", ")}`);
  }

  const registered = new Set([...shared, ...dedicated.keys()]);
  const missing = [...tests.keys()].filter((className) => !registered.has(className));
  const unknown = [...registered].filter((className) => !tests.has(className));
  if (missing.length > 0 || unknown.length > 0) {
    throw new Error(
      `PostgreSQL topology 등록이 일치하지 않습니다. missing=[${missing.join(", ")}], unknown=[${unknown.join(", ")}]`,
    );
  }

  for (const className of shared) {
    const source = tests.get(className).source;
    if (!/extends\s+SharedPostgresIntegrationSupport\b/.test(source)) {
      throw new Error(`shared 테스트가 공통 지원 클래스를 상속하지 않습니다: ${className}`);
    }
    if (/new\s+PostgreSQLContainer\b/.test(source)) {
      throw new Error(`shared 테스트가 전용 PostgreSQLContainer를 선언합니다: ${className}`);
    }
  }

  for (const [className, reason] of dedicated) {
    if (typeof reason !== "string" || reason.trim() === "") {
      throw new Error(`dedicated 테스트의 사유가 비어 있습니다: ${className}`);
    }
    if (/extends\s+SharedPostgresIntegrationSupport\b/.test(tests.get(className).source)) {
      throw new Error(`dedicated 테스트가 공통 지원 클래스를 상속합니다: ${className}`);
    }
  }

  return { testCount: tests.size, sharedCount: shared.size, dedicatedCount: dedicated.size };
}

function main() {
  const result = validatePostgresTestTopology();
  process.stdout.write(
    `PostgreSQL topology: ${result.testCount} tests = ${result.sharedCount} shared + ${result.dedicatedCount} dedicated\n`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main();
}
