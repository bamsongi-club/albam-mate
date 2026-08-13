import { createHash } from 'node:crypto';
import { existsSync, copyFileSync, mkdtempSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

export function sha256(value) {
    return createHash('sha256').update(value).digest('hex');
}

export function resolveInputRoot(args = [], env = process.env) {
    let inputRoot = null;
    if (args[0] === '--input-root') {
        inputRoot = args[1];
    } else if (args[0] && !args[0].startsWith('--')) {
        inputRoot = args[0];
    }
    inputRoot ??= env.ALBAM_MATE_170K_DIR;
    if (!inputRoot) {
        throw new Error('input-root가 필요합니다: <path> 또는 --input-root <path> 또는 ALBAM_MATE_170K_DIR');
    }
    return resolve(inputRoot);
}

export function validatePositiveUniqueIds(rows, sourceName) {
    if (!Array.isArray(rows)) {
        throw new Error(`${sourceName} rows must be an array`);
    }
    const ids = [];
    const seen = new Set();
    for (const [index, row] of rows.entries()) {
        const rawId = row?.bgg_id;
        if (!/^[1-9]\d*$/.test(String(rawId ?? '')) || !Number.isSafeInteger(Number(rawId))) {
            throw new Error(`${sourceName} bgg_id at row ${index + 1} must be a positive safe integer: ${rawId}`);
        }
        const bggId = Number(rawId);
        if (seen.has(bggId)) {
            throw new Error(`${sourceName} duplicate bgg_id: ${bggId}`);
        }
        seen.add(bggId);
        ids.push(bggId);
    }
    return ids;
}

export function validateApprovedInputReport({
    report,
    inputBytes,
    inputRows,
    inputKeys,
    datasetKind,
    grain,
}) {
    const errors = [];
    if (report?.status !== 'ready') {
        errors.push(`status must be ready, got ${report?.status ?? 'missing'}`);
    }
    if (report?.datasetKind !== datasetKind) {
        errors.push(`datasetKind must be ${datasetKind}, got ${report?.datasetKind ?? 'missing'}`);
    }
    if (report?.grain !== grain) {
        errors.push(`grain must be ${grain}, got ${report?.grain ?? 'missing'}`);
    }
    const metadata = inputKeys.map((key) => report?.inputs?.[key]).find(Boolean);
    if (!metadata) {
        errors.push(`input metadata is missing for ${inputKeys.join(' or ')}`);
    } else {
        if (metadata.sha256 !== sha256(inputBytes)) {
            errors.push('input checksum does not match the approved report');
        }
        if (metadata.rows !== inputRows) {
            errors.push(`input rows do not match the approved report: ${metadata.rows} !== ${inputRows}`);
        }
    }
    if (errors.length > 0) {
        throw new Error(errors.join('; '));
    }
}

export function escapeCsvField(value) {
    return `"${String(value ?? '').replace(/\r\n|\r|\n/g, ' ').replace(/"/g, '""')}"`;
}

export function parseCsvLine(line) {
    const fields = [];
    let field = '';
    let quoted = false;
    for (let index = 0; index < line.length; index += 1) {
        const character = line[index];
        if (character === '"') {
            if (quoted && line[index + 1] === '"') {
                field += '"';
                index += 1;
            } else {
                quoted = !quoted;
            }
        } else if (character === ',' && !quoted) {
            fields.push(field);
            field = '';
        } else {
            field += character;
        }
    }
    if (quoted) {
        throw new Error('unterminated CSV quote');
    }
    fields.push(field);
    return fields;
}

export function parseNameUpdates(sql) {
    return [...sql.matchAll(/UPDATE games SET name = '((?:''|[^'])*)' WHERE bgg_id = ([1-9]\d*);/gs)]
        .map((match) => ({ bggId: Number(match[2]), value: match[1].replaceAll("''", "'") }));
}

export function parseDescriptionUpdates(sql) {
    return [...sql.matchAll(/UPDATE games SET description = '((?:''|[^'])*)', detail_description = '((?:''|[^'])*)' WHERE bgg_id = ([1-9]\d*);/gs)]
        .map((match) => ({
            bggId: Number(match[3]),
            description: match[1].replaceAll("''", "'"),
            detailDescription: match[2].replaceAll("''", "'"),
        }));
}

export function readZipJsonEntry(zipPath, zipEntry) {
    const workRoot = mkdtempSync(join(tmpdir(), 'albam-zip-entry-'));
    const extractedPath = join(workRoot, 'entry.json');
    try {
        const contents = execFileSync('unzip', ['-p', zipPath, zipEntry]);
        writeFileSync(extractedPath, contents);
        return JSON.parse(readFileSync(extractedPath, 'utf8'));
    } finally {
        rmSync(workRoot, { recursive: true, force: true });
    }
}

export function commitZipArtifacts({ zipPath, zipEntry, zipFileTarget, files }) {
    if (!existsSync(zipPath)) {
        throw new Error(`ZIP input does not exist: ${zipPath}`);
    }
    const zipFileSpec = files.find(({ target }) => target === zipFileTarget);
    if (!zipFileSpec) {
        throw new Error(`ZIP source file is not included in generated files: ${zipFileTarget}`);
    }
    const workRoot = mkdtempSync(join(tmpdir(), 'albam-catalog-'));
    let backups = [];
    let zipBackup = null;
    try {
        const stagedZip = join(workRoot, 'handoff.zip');
        const fileStages = files.map(({ target, contents }, index) => {
            const staged = join(workRoot, 'files', String(index));
            mkdirSync(dirname(staged), { recursive: true });
            writeFileSync(staged, contents, 'utf8');
            return { target, staged };
        });
        const zipSource = join(workRoot, zipEntry);
        mkdirSync(dirname(zipSource), { recursive: true });
        copyFileSync(fileStages.find(({ target }) => target === zipFileSpec.target).staged, zipSource);
        copyFileSync(zipPath, stagedZip);
        try {
            execFileSync('zip', ['-q', '-d', stagedZip, zipEntry], { cwd: workRoot });
        } catch (error) {
            if (error.status !== 12) throw error;
        }
        execFileSync('zip', ['-q', stagedZip, zipEntry], { cwd: workRoot });

        backups = fileStages.map(({ target }, index) => {
            const backup = join(workRoot, 'backups', String(index));
            const existed = existsSync(target);
            if (existed) {
                mkdirSync(dirname(backup), { recursive: true });
                copyFileSync(target, backup);
            }
            return { target, backup, existed };
        });
        zipBackup = join(workRoot, 'handoff.original.zip');
        copyFileSync(zipPath, zipBackup);
        for (const { target, staged } of fileStages) {
            mkdirSync(dirname(target), { recursive: true });
            renameSync(staged, target);
        }
        renameSync(stagedZip, zipPath);
    } catch (error) {
        for (const { target, backup, existed } of backups) {
            if (existed) {
                copyFileSync(backup, target);
            } else if (existsSync(target)) {
                rmSync(target, { force: true });
            }
        }
        if (zipBackup) copyFileSync(zipBackup, zipPath);
        throw error;
    } finally {
        rmSync(workRoot, { recursive: true, force: true });
    }
}
