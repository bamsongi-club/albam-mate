import fs from "node:fs";
import { pathToFileURL } from "node:url";

const DOCUMENTATION_ONLY_PATTERNS = [
  /\.md$/,
  /^docs\//,
  /^\.github\/ISSUE_TEMPLATE\//,
  /^scripts\/check-doc-links(?:\.test)?\.mjs$/,
  /^scripts\/validate-(?:packet|backend-test-manifest|coverage-ratchet)(?:\.test)?\.mjs$/,
];

function normalizePath(filePath) {
  return filePath.trim().replaceAll("\\", "/").replace(/^\.\//, "");
}

function isDocumentationOnly(filePath) {
  return DOCUMENTATION_ONLY_PATTERNS.some((pattern) => pattern.test(filePath));
}

export function classifyCiPaths(paths, { forceAll = false } = {}) {
  if (forceAll) {
    return { backend: true, frontend: true };
  }

  const normalizedPaths = paths.map(normalizePath).filter(Boolean);
  if (normalizedPaths.length === 0) {
    return { backend: true, frontend: true };
  }

  return {
    backend: normalizedPaths.some(
      (filePath) => !filePath.startsWith("frontend/") && !isDocumentationOnly(filePath),
    ),
    frontend: normalizedPaths.some((filePath) => filePath.startsWith("frontend/")),
  };
}

function parseArguments(args) {
  const forceAll = args.includes("--all");
  const pathsFileIndex = args.indexOf("--paths-file");
  const pathsFile = pathsFileIndex >= 0 ? args[pathsFileIndex + 1] : undefined;

  if (!forceAll && !pathsFile) {
    throw new Error("--all 또는 --paths-file <path>가 필요합니다.");
  }
  if (pathsFileIndex >= 0 && !pathsFile) {
    throw new Error("--paths-file 뒤에 경로가 필요합니다.");
  }

  return { forceAll, pathsFile };
}

function main() {
  const { forceAll, pathsFile } = parseArguments(process.argv.slice(2));
  const paths = pathsFile ? fs.readFileSync(pathsFile, "utf8").split(/\r?\n/) : [];
  const classification = classifyCiPaths(paths, { forceAll });

  process.stdout.write(
    `backend=${classification.backend}\nfrontend=${classification.frontend}\n`,
  );
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
