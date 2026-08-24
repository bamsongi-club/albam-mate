#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import { changedPathsIn } from './ci/classify-postgres-requirement.mjs';

const PRODUCTION_JAVA_PATH = /^src\/main\/java\/.+\.java$/u;
const PACKAGE_DECLARATION = /^\s*package\s+([\w.]+)\s*;/mu;
const H2_COVERAGE_MAP_START = /^\s*def\s+h2GlobalCoverage\s*=\s*\[\s*$/u;
const COVERAGE_MAP_START = /^\s*def\s+gatedBranchCoverage\s*=\s*\[\s*$/u;
const COVERAGE_MAP_END = /^\s*\]\s*$/u;
const H2_COVERAGE_MAP_ENTRY = /^\s*'(BRANCH|LINE)'\s*:\s*(\d+(?:\.\d+)?)\s*,?\s*$/u;
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
    const h2Start = lines.findIndex((line) => H2_COVERAGE_MAP_START.test(line));
    if (h2Start === -1) {
        throw new Error('build.gradle에서 h2GlobalCoverage 맵을 찾을 수 없습니다.');
    }

    const h2GlobalMinimums = new Map();
    let h2End = -1;
    for (let index = h2Start + 1; index < lines.length; index += 1) {
        if (COVERAGE_MAP_END.test(lines[index])) {
            h2End = index;
            break;
        }
        const trimmed = lines[index].trim();
        if (trimmed === '' || trimmed.startsWith('//')) {
            continue;
        }
        const entry = H2_COVERAGE_MAP_ENTRY.exec(lines[index]);
        if (!entry) {
            throw new Error(`h2GlobalCoverage 항목을 해석할 수 없습니다: ${trimmed}`);
        }
        h2GlobalMinimums.set(entry[1], Number(entry[2]));
    }
    if (h2End === -1 || !h2GlobalMinimums.has('BRANCH') || !h2GlobalMinimums.has('LINE')) {
        throw new Error('h2GlobalCoverage에는 BRANCH와 LINE 최소선이 모두 필요합니다.');
    }

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

    return { h2GlobalMinimums, globalMinimums, packageMinimums };
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
        const sources = [];
        if (fs.existsSync(absolutePath) && fs.statSync(absolutePath).isFile()) {
            sources.push({ location: 'working tree', contents: fs.readFileSync(absolutePath, 'utf8') });
        }

        const baseRef = base ?? 'HEAD';
        const baseBlob = spawnSync('git', ['-C', worktree, 'show', `${baseRef}:${normalizedPath}`], {
            encoding: 'utf8',
            maxBuffer: 32 * 1024 * 1024,
            windowsHide: true,
        });
        if (baseBlob.error) {
            throw baseBlob.error;
        }
        if (baseBlob.status === 0) {
            sources.push({ location: baseRef, contents: baseBlob.stdout });
        }
        if (sources.length === 0) {
            throw new Error(`변경 Java 파일을 working tree와 ${baseRef}에서 찾을 수 없습니다: ${normalizedPath}`);
        }

        for (const source of sources) {
            const declaration = PACKAGE_DECLARATION.exec(source.contents);
            if (!declaration) {
                throw new Error(
                    `변경 Java 파일의 ${source.location} 내용에서 package 선언을 찾을 수 없습니다: ${normalizedPath}`,
                );
            }
            packageNames.add(declaration[1]);
        }
    }
    return [...packageNames].sort();
}

export function verifyChangedH2Coverage({ buildFileText, reportXml, changedPackages }) {
    const rules = coverageRulesFromBuildFile(buildFileText);
    const coverage = coverageFromJacocoXml(reportXml);
    const problems = [];
    const checkedPackages = [];
    const globalChecked = changedPackages.length > 0;

    if (globalChecked) {
        for (const [type, minimum] of rules.h2GlobalMinimums) {
            const counter = coverage.totals[type.toLowerCase()];
            if (!counter) {
                problems.push(`JaCoCo 리포트에 H2 전체 ${type} counter가 없습니다.`);
                continue;
            }
            const actual = ratio(counter);
            if (actual < minimum) {
                problems.push(
                    `H2 전체 ${type} 커버리지가 ${percentage(actual)}로 최소선 ${percentage(minimum)}보다 낮습니다.`,
                );
            }
        }
    }
    // PostgreSQL 전용 테스트가 담당하는 변경하지 않은 패키지의 H2 비율은 변경 게이트와 무관하다.
    // 개별 branch 래칫이 있는 패키지는 branch를, 나머지는 전역 line 최소선을 패키지 단위로 확인한다.
    const lineMinimum = rules.globalMinimums.get('LINE');

    for (const packageName of [...new Set(changedPackages)].sort()) {
        const packageCoverage = coverage.packages.get(packageName);
        if (!packageCoverage) {
            problems.push(`변경 패키지가 JaCoCo 리포트에 없습니다: ${packageName}`);
            continue;
        }

        const branchMinimum = rules.packageMinimums.get(packageName);
        if (branchMinimum !== undefined) {
            if (!packageCoverage.branch) {
                problems.push(`변경 패키지에 BRANCH counter가 없습니다: ${packageName}`);
                continue;
            }
            const actual = ratio(packageCoverage.branch);
            checkedPackages.push({ packageName, counter: 'BRANCH', actual, minimum: branchMinimum });
            if (actual < branchMinimum) {
                problems.push(
                    `${packageName} BRANCH 커버리지가 ${percentage(actual)}로 최소선 ${percentage(branchMinimum)}보다 낮습니다.`,
                );
            }
            continue;
        }

        if (!packageCoverage.line) {
            problems.push(`변경 패키지에 LINE counter가 없습니다: ${packageName}`);
            continue;
        }
        const actual = ratio(packageCoverage.line);
        checkedPackages.push({ packageName, counter: 'LINE', actual, minimum: lineMinimum });
        if (actual < lineMinimum) {
            problems.push(
                `${packageName} LINE 커버리지가 ${percentage(actual)}로 최소선 ${percentage(lineMinimum)}보다 낮습니다.`,
            );
        }
    }

    return { problems, checkedPackages, globalChecked };
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

        const checked = result.checkedPackages.map((entry) => `${entry.packageName} ${entry.counter}`).join(', ');
        if (changedPackages.length === 0) {
            console.log('변경된 프로덕션 Java 패키지가 없어 H2 전체 및 패키지 래칫 검증을 건너뛰었다.');
        } else if (checked === '') {
            console.log('변경 패키지에 적용할 H2 커버리지 규칙이 없어 검증을 건너뛰었다.');
        } else {
            console.log(`변경 패키지 H2 커버리지 최소선을 통과했다: ${checked}`);
        }
    } catch (error) {
        console.error(`변경 패키지 H2 커버리지 검증 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) {
    runCli();
}
