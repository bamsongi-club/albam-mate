import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

import {
  POSTGRES_DECISIONS,
  classifyPostgresRequirementIn,
} from "./classify-postgres-requirement.mjs";

const NON_BACKEND_ONLY_PATTERNS = [
  /\.md$/,
  /^docs\//,
  /^\.github\/ISSUE_TEMPLATE\//,
  /^load-tests\//,
  /^scripts\/ci\/classify-ci-paths(?:\.test)?\.mjs$/,
  /^scripts\/docs\/check-(?:doc-links|monitoring-contract)(?:\.test)?\.mjs$/,
  /^scripts\/(?:ci\/classify-postgres-requirement|verify-changed-h2-coverage)\.test\.mjs$/,
  /^scripts\/validate-(?:packet|backend-test-manifest|coverage-ratchet)(?:\.test)?\.mjs$/,
];

function normalizePath(filePath) {
  return filePath.replaceAll("\\", "/").replace(/^\.\//, "");
}

export function readNulDelimitedPaths(pathsFile) {
  const contents = fs.readFileSync(pathsFile, "utf8");
  if (contents !== "" && !contents.endsWith("\0")) {
    throw new Error("--paths-file은 NUL로 끝나는 git diff -z 출력이어야 합니다.");
  }
  return contents.split("\0").filter(Boolean);
}

function isNonBackendOnly(filePath) {
  return NON_BACKEND_ONLY_PATTERNS.some((pattern) => pattern.test(filePath));
}

function fallbackClassification(code, message) {
  return {
    decision: POSTGRES_DECISIONS.NEEDS_REVIEW,
    reasons: [{ code, path: "(change-set)", message }],
  };
}

export function classifyCiPaths(
  paths,
  { forceAll = false, postgresClassification = null } = {},
) {
  if (forceAll) {
    return {
      backend: true,
      frontend: true,
      postgresDecision: POSTGRES_DECISIONS.NEEDS_REVIEW,
      postgresRequired: true,
      dockerRequired: true,
    };
  }

  const normalizedPaths = paths.map(normalizePath).filter(Boolean);
  if (normalizedPaths.length === 0) {
    return {
      backend: true,
      frontend: true,
      postgresDecision: POSTGRES_DECISIONS.NEEDS_REVIEW,
      postgresRequired: true,
      dockerRequired: true,
    };
  }

  const backend = normalizedPaths.some(
    (filePath) => !filePath.startsWith("frontend/") && !isNonBackendOnly(filePath),
  );
  const frontend = normalizedPaths.some((filePath) => filePath.startsWith("frontend/"));
  const postgresDecision = backend
    ? (postgresClassification?.decision ?? POSTGRES_DECISIONS.NEEDS_REVIEW)
    : POSTGRES_DECISIONS.NOT_REQUIRED;
  const postgresRequired = backend && postgresDecision !== POSTGRES_DECISIONS.NOT_REQUIRED;

  return {
    backend,
    frontend,
    postgresDecision,
    postgresRequired,
    // PostgreSQL 생략을 확신할 수 있는 변경만 Docker 기반 local runtime도 생략한다.
    // Redis·session·workflow·build 경로는 분류기에서 needs-review가 되어 true로 폴백한다.
    dockerRequired: postgresRequired,
  };
}

function parseArguments(args) {
  const forceAll = args.includes("--all");
  const pathsFileIndex = args.indexOf("--paths-file");
  const pathsFile = pathsFileIndex >= 0 ? args[pathsFileIndex + 1] : undefined;
  const baseIndex = args.indexOf("--base");
  const base = baseIndex >= 0 ? args[baseIndex + 1] : undefined;
  const worktreeIndex = args.indexOf("--worktree");
  const worktree = worktreeIndex >= 0 ? args[worktreeIndex + 1] : process.cwd();

  if (!forceAll && !pathsFile) {
    throw new Error("--all 또는 --paths-file <path>가 필요합니다.");
  }
  if (pathsFileIndex >= 0 && !pathsFile) {
    throw new Error("--paths-file 뒤에 경로가 필요합니다.");
  }
  if (baseIndex >= 0 && !base) {
    throw new Error("--base 뒤에 ref가 필요합니다.");
  }
  if (worktreeIndex >= 0 && !worktree) {
    throw new Error("--worktree 뒤에 경로가 필요합니다.");
  }

  return { forceAll, pathsFile, base, worktree };
}

function main() {
  const { forceAll, pathsFile, base, worktree } = parseArguments(process.argv.slice(2));
  const paths = pathsFile ? readNulDelimitedPaths(pathsFile) : [];
  const preliminary = classifyCiPaths(paths, { forceAll });
  let postgresClassification = fallbackClassification(
    "classifier-not-run",
    "PostgreSQL 분류 근거가 없어 전체 검증으로 폴백합니다.",
  );

  if (!forceAll && preliminary.backend && base) {
    try {
      postgresClassification = classifyPostgresRequirementIn(path.resolve(worktree), {
        base,
        changedPaths: paths,
      });
    } catch (error) {
      postgresClassification = fallbackClassification(
        "classifier-error",
        `분류기가 실패해 전체 검증으로 폴백합니다: ${error.message}`,
      );
    }
  } else if (!preliminary.backend) {
    postgresClassification = {
      decision: POSTGRES_DECISIONS.NOT_REQUIRED,
      reasons: [
        {
          code: "no-backend-change",
          path: "(change-set)",
          message: "백엔드 검증 대상 변경이 없습니다.",
        },
      ],
    };
  }

  const classification = classifyCiPaths(paths, { forceAll, postgresClassification });
  const reasonCodes = postgresClassification.reasons.map((reason) => reason.code).join(",");

  process.stdout.write(
    [
      `backend=${classification.backend}`,
      `frontend=${classification.frontend}`,
      `postgres_decision=${classification.postgresDecision}`,
      `postgres_required=${classification.postgresRequired}`,
      `docker_required=${classification.dockerRequired}`,
      `postgres_reasons=${reasonCodes}`,
      "",
    ].join("\n"),
  );
  process.stderr.write(
    `PostgreSQL classification: ${classification.postgresDecision} (${reasonCodes})\n`,
  );
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
