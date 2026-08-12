#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import { changedPathsIn } from './classify-postgres-requirement.mjs';

const PRODUCTION_JAVA_PATH = /^src\/main\/java\/.+\.java$/u;
const PACKAGE_DECLARATION = /^\s*package\s+([\w.]+)\s*;/mu;
const COVERAGE_MAP_START = /^\s*def\s+gatedBranchCoverage\s*=\s*\[\s*$/u;
const COVERAGE_MAP_END = /^\s*\]\s*$/u;
const COVERAGE_MAP_ENTRY = /^\s*'([\w.]+)'\s*:\s*(\d+(?:\.\d+)?)\s*,?\s*$/u;

function ratio(counter) {
    const total = counter.missed + counter.covered;
    return total === 0 ? 1 : counter.covered / total;
}

function percentage(value) {
    return `${(value * 100).toFixed(2)}%`;
}

function findCounter(fragment, type) {
    const pattern = new RegExp(
        `<counter type="${type}" missed="(\\d+)" covered="(\\d+)"\\s*\\/>`,
        'gu',
    );
    const matches = [...fragment.matchAll(pattern)];
    if (matches.length === 0) {
        return null;
    }
    const last = matches.at(-1);
    return { missed: Number(last[1]), covered: Number(last[2]) };
}

export function coverageRulesFromBuildFile(buildFileText) {
    const lines = buildFileText.split(/\r?\n/u);
    const start = lines.findIndex((line) => COVERAGE_MAP_START.test(line));
    if (start === -1) {
        throw new Error('build.gradle에서 gatedBranchCoverage 맵을 찾을 수 없습니다.');
    }

    const packageMinimums = new Map();
    let end = -1;
    for (let index = start + 1; index < lines.length; index += 1) {
        if (COVERAGE_MAP_END.test(lines[index])) {
            end = index;
            break;
        }
        const trimmed = lines[index].trim();
        if (trimmed === '' || trimmed.startsWith('//')) {
            continue;
        }
        const entry = COVERAGE_MAP_ENTRY.exec(lines[index]);
        if (!entry) {
            throw new Error(`gatedBranchCoverage 항목을 해석할 수 없습니다: ${trimmed}`);
        }
        if (packageMinimums.has(entry[1])) {
            throw new Error(`gatedBranchCoverage에 중복 패키지가 있습니다: ${entry[1]}`);
        }
        packageMinimums.set(entry[1], Number(entry[2]));
    }
    if (end === -1) {
        throw new Error('gatedBranchCoverage 맵의 끝을 찾을 수 없습니다.');
    }

    const rulesStart = buildFileText.indexOf('def applyCoverageRules');
    const packageRulesStart = buildFileText.indexOf('gatedBranchCoverage.each', rulesStart);
    if (rulesStart === -1 || packageRulesStart === -1) {
        throw new Error('build.gradle에서 전체 커버리지 규칙을 찾을 수 없습니다.');
    }
    const globalRuleBlock = buildFileText.slice(rulesStart, packageRulesStart);
    const globalMinimums = new Map();
    for (const type of ['BRANCH', 'LINE']) {
        const pattern = new RegExp(
            `counter\\s*=\\s*'${type}'[\\s\\S]*?minimum\\s*=\\s*(\\d+(?:\\.\\d+)?)`,
            'u',
        );
        const match = pattern.exec(globalRuleBlock);
        if (!match) {
            throw new Error(`build.gradle에서 전체 ${type} 최소선을 찾을 수 없습니다.`);
        }
        globalMinimums.set(type, Number(match[1]));
    }

    return { globalMinimums, packageMinimums };
}

export function coverageFromJacocoXml(xmlText) {
    const packages = new Map();
    const packagePattern = /<package name="([^"]+)">([\s\S]*?)<\/package>/gu;
    for (const match of xmlText.matchAll(packagePattern)) {
        packages.set(match[1].replaceAll('/', '.'), {
            branch: findCounter(match[2], 'BRANCH'),
            line: findCounter(match[2], 'LINE'),
        });
    }

    return {
        packages,
        totals: {
            branch: findCounter(xmlText, 'BRANCH'),
            line: findCounter(xmlText, 'LINE'),
        },
    };
}

export function changedProductionPackagesIn(worktreePath, base = null) {
    const worktree = fs.realpathSync(path.resolve(worktreePath));
    const packageNames = new Set();
    for (const filePath of changedPathsIn(worktree, base)) {
        const normalizedPath = filePath.replaceAll('\\', '/');
        if (!PRODUCTION_JAVA_PATH.test(normalizedPath)) {
            continue;
        }
        const absolutePath = path.resolve(worktree, normalizedPath);
        if (!fs.existsSync(absolutePath) || !fs.statSync(absolutePath).isFile()) {
            continue;
        }
        const contents = fs.readFileSync(absolutePath, 'utf8');
        const declaration = PACKAGE_DECLARATION.exec(contents);
        if (!declaration) {
            throw new Error(`변경 Java 파일에서 package 선언을 찾을 수 없습니다: ${normalizedPath}`);
        }
        packageNames.add(declaration[1]);
    }
    return [...packageNames].sort();
}

export function verifyChangedH2Coverage({ buildFileText, reportXml, changedPackages }) {
    const rules = coverageRulesFromBuildFile(buildFileText);
    const coverage = coverageFromJacocoXml(reportXml);
    const problems = [];
    const checkedPackages = [];

    for (const [type, minimum] of rules.globalMinimums) {
        const counter = coverage.totals[type.toLowerCase()];
        if (!counter) {
            problems.push(`JaCoCo 리포트에 전체 ${type} counter가 없습니다.`);
            continue;
        }
        const actual = ratio(counter);
        if (actual < minimum) {
            problems.push(
                `전체 ${type} 커버리지가 ${percentage(actual)}로 최소선 ${percentage(minimum)}보다 낮습니다.`,
            );
        }
    }

    for (const packageName of [...new Set(changedPackages)].sort()) {
        const minimum = rules.packageMinimums.get(packageName);
        if (minimum === undefined) {
            continue;
        }
        const packageCoverage = coverage.packages.get(packageName);
        if (!packageCoverage?.branch) {
            problems.push(`변경 패키지가 JaCoCo 리포트에 없습니다: ${packageName}`);
            continue;
        }
        const actual = ratio(packageCoverage.branch);
        checkedPackages.push({ packageName, actual, minimum });
        if (actual < minimum) {
            problems.push(
                `${packageName} BRANCH 커버리지가 ${percentage(actual)}로 최소선 ${percentage(minimum)}보다 낮습니다.`,
            );
        }
    }

    return { problems, checkedPackages };
}

function parseArguments(argv) {
    const values = { base: null };
    const allowed = new Set(['--report', '--worktree', '--base']);
    for (let index = 0; index < argv.length; index += 2) {
        const option = argv[index];
        const value = argv[index + 1];
        if (!allowed.has(option) || value === undefined || value.startsWith('--')) {
            return null;
        }
        values[option.slice(2)] = value;
    }
    return values.report && values.worktree ? values : null;
}

function runCli() {
    const args = parseArguments(process.argv.slice(2));
    if (!args) {
        console.error(
            '사용법: node scripts/verify-changed-h2-coverage.mjs --report <jacoco.xml> --worktree <worktree> [--base <ref>]',
        );
        process.exitCode = 2;
        return;
    }

    try {
        const worktree = fs.realpathSync(path.resolve(args.worktree));
        const changedPackages = changedProductionPackagesIn(worktree, args.base);
        const result = verifyChangedH2Coverage({
            buildFileText: fs.readFileSync(path.join(worktree, 'build.gradle'), 'utf8'),
            reportXml: fs.readFileSync(path.resolve(args.report), 'utf8'),
            changedPackages,
        });

        if (result.problems.length > 0) {
            console.error('변경 패키지 H2 커버리지 검증 실패');
            for (const problem of result.problems) {
                console.error(`- ${problem}`);
            }
            process.exitCode = 1;
            return;
        }

        const checked = result.checkedPackages.map((entry) => entry.packageName).join(', ');
        console.log(
            checked === ''
                ? 'H2 전체 최소선을 통과했고 변경 패키지에 개별 BRANCH 규칙이 없다.'
                : `H2 전체 최소선과 변경 패키지 BRANCH 최소선을 통과했다: ${checked}`,
        );
    } catch (error) {
        console.error(`변경 패키지 H2 커버리지 검증 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) {
    runCli();
}
