import { createHash, randomUUID } from 'node:crypto';
import {
    closeSync,
    copyFileSync,
    createReadStream,
    createWriteStream,
    existsSync,
    mkdtempSync,
    mkdirSync,
    openSync,
    readFileSync,
    readSync,
    renameSync,
    rmSync,
    statSync,
    utimesSync,
    writeFileSync,
    writeSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { setTimeout as delay } from 'node:timers/promises';
import { Worker } from 'node:worker_threads';
import { createDeflateRaw, createInflateRaw } from 'node:zlib';

const ZIP_LOCAL_FILE_HEADER = 0x04034b50;
const ZIP_CENTRAL_DIRECTORY_HEADER = 0x02014b50;
const ZIP_END_OF_CENTRAL_DIRECTORY = 0x06054b50;
const ZIP_DATA_DESCRIPTOR = 0x08074b50;
const ZIP_UTF8_FLAG = 0x0800;
const ZIP_DATA_DESCRIPTOR_FLAG = 0x0008;
const ZIP_DEFLATE_METHOD = 8;
const ZIP_MAX_UINT16 = 0xffff;
const ZIP_MAX_UINT32 = 0xffffffff;
const ZIP_COPY_BUFFER_SIZE = 1024 * 1024;
const ZIP_LOCK_POLL_MS = 100;
const ZIP_LOCK_TIMEOUT_MS = 10 * 60 * 1000;
const ZIP_LOCK_HEARTBEAT_MS = 1_000;
const ZIP_LOCK_LEASE_MS = 2 * 60 * 1000;
const ZIP_OWNER_WRITE_GRACE_MS = 5_000;
const CRC32_TABLE = buildCrc32Table();

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

export function parseApprovedRelationTuples(sql, kind, allowedRelatedIds) {
    const tupleList = String.raw`values(?:\s*\(\s*[1-9]\d*\s*,\s*[1-9]\d*\s*\)\s*,?)+`;
    const pattern = kind === 'mechanism'
        ? new RegExp(String.raw`insert\s+into\s+game_mechanism_relation_source\s*\(\s*bgg_id\s*,\s*bgg_mechanism_id\s*\)\s*(${tupleList})\s*;`, 'i')
        : kind === 'theme'
            ? new RegExp(String.raw`with\s+desired\s*\(\s*bgg_id\s*,\s*bgg_theme_id\s*\)\s+as\s*\(\s*(${tupleList})\s*\)\s*insert\s+into\s+game_theme_relations\b`, 'i')
            : null;
    if (!pattern) {
        throw new Error(`unsupported relation kind: ${kind}`);
    }
    const matched = pattern.exec(sql);
    if (!matched) {
        throw new Error(`approved ${kind} relation source statement is missing`);
    }
    const pairs = [...matched[1].matchAll(/\(\s*([1-9]\d*)\s*,\s*([1-9]\d*)\s*\)/g)]
        .map((match) => ({ gameBggId: Number(match[1]), relatedBggId: Number(match[2]) }));
    const seen = new Set();
    for (const pair of pairs) {
        if (!Number.isSafeInteger(pair.gameBggId) || !Number.isSafeInteger(pair.relatedBggId)) {
            throw new Error(`invalid ${kind} relation integer`);
        }
        const key = `${pair.gameBggId}:${pair.relatedBggId}`;
        if (seen.has(key)) {
            throw new Error(`duplicate ${kind} relation: ${key}`);
        }
        seen.add(key);
        if (allowedRelatedIds && !allowedRelatedIds.has(pair.relatedBggId)) {
            throw new Error(`${kind} relation ID is not in the approved dictionary: ${pair.relatedBggId}`);
        }
    }
    return pairs;
}

export function validateApprovedLocalizationReport({
    report,
    namesSql,
    descriptionsSql,
    catalogContents,
    catalogRows,
    nameUpdates,
    descriptionUpdates,
}) {
    const errors = [];
    if (report?.schemaVersion !== 1
        || report?.datasetKind !== 'approved-full-localization'
        || report?.grain !== '1 row per bgg_id'
        || report?.status !== 'ready') {
        errors.push('full localization report is not approved and ready');
    }
    for (const [role, contents, updates] of [
        ['namesSql', namesSql, nameUpdates],
        ['descriptionsSql', descriptionsSql, descriptionUpdates],
    ]) {
        const metadata = report?.inputs?.[role];
        if (metadata?.sha256 !== sha256(contents) || metadata?.rows !== updates.length) {
            errors.push(`${role} checksum or rows do not match the approved report`);
        }
    }
    if (report?.inputs?.catalog?.sha256 !== sha256(catalogContents)
        || report?.inputs?.catalog?.rows !== catalogRows.length) {
        errors.push('catalog checksum or rows do not match the approved report');
    }
    let catalogIds = [];
    try {
        catalogIds = validatePositiveUniqueIds(catalogRows, 'catalog');
    } catch (error) {
        errors.push(error.message);
    }
    for (const [role, updates] of [
        ['name', nameUpdates],
        ['description', descriptionUpdates],
    ]) {
        const ids = updates.map(({ bggId }) => bggId);
        const uniqueIds = new Set(ids);
        if (ids.length !== uniqueIds.size) {
            errors.push(`${role} localization has duplicate bgg_id`);
        }
        if (catalogIds.length !== ids.length
            || catalogIds.some((bggId) => !uniqueIds.has(bggId))) {
            errors.push(`${role} localization does not cover the full catalog`);
        }
    }
    if (nameUpdates.some(({ value }) => typeof value !== 'string' || value.trim() === '')) {
        errors.push('name localization contains a blank approved value');
    }
    if (descriptionUpdates.some(({ description, detailDescription }) =>
        typeof description !== 'string'
        || description.trim() === ''
        || typeof detailDescription !== 'string'
        || detailDescription.trim() === '')) {
        errors.push('description localization contains a blank approved value');
    }
    if (errors.length > 0) {
        throw new Error(errors.join('; '));
    }
}

export async function readZipJsonEntry(zipPath, zipEntry) {
    const workRoot = mkdtempSync(join(tmpdir(), 'albam-zip-entry-'));
    const extractedPath = join(workRoot, 'entry.json');
    try {
        await extractZipEntry(zipPath, zipEntry, extractedPath);
        return JSON.parse(readFileSync(extractedPath, 'utf8'));
    } finally {
        rmSync(workRoot, { recursive: true, force: true });
    }
}

export async function readZipTextEntry(zipPath, zipEntry) {
    const workRoot = mkdtempSync(join(tmpdir(), 'albam-zip-entry-'));
    const extractedPath = join(workRoot, 'entry.txt');
    try {
        await extractZipEntry(zipPath, zipEntry, extractedPath);
        return readFileSync(extractedPath, 'utf8');
    } finally {
        rmSync(workRoot, { recursive: true, force: true });
    }
}

export async function commitZipArtifacts({ zipPath, zipEntry, zipFileTarget, files }) {
    if (!existsSync(zipPath)) {
        throw new Error(`ZIP input does not exist: ${zipPath}`);
    }
    const zipFileSpec = files.find(({ target }) => target === zipFileTarget);
    if (!zipFileSpec) {
        throw new Error(`ZIP source file is not included in generated files: ${zipFileTarget}`);
    }
    const zipLock = await acquireZipLock(zipPath);
    try {
        await commitZipArtifactsWithLock({ zipPath, zipEntry, files, zipFileSpec, zipLock });
    } finally {
        zipLock.release();
    }
}

async function commitZipArtifactsWithLock({ zipPath, zipEntry, files, zipFileSpec, zipLock }) {
    const workRoot = mkdtempSync(join(tmpdir(), 'albam-catalog-'));
    let backups = [];
    let zipBackup = null;
    let mutationsStarted = false;
    try {
        const stagedZip = join(workRoot, 'handoff.zip');
        const fileStages = files.map(({ target, contents }, index) => {
            const staged = join(workRoot, 'files', String(index));
            mkdirSync(dirname(staged), { recursive: true });
            writeFileSync(staged, contents, 'utf8');
            return { target, staged };
        });
        const zipSource = fileStages.find(({ target }) => target === zipFileSpec.target).staged;
        await replaceZipEntry(zipPath, zipEntry, zipSource, stagedZip);

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
        zipLock.assertAndRenew();
        for (const { target, staged } of fileStages) {
            zipLock.assertAndRenew();
            mkdirSync(dirname(target), { recursive: true });
            mutationsStarted = true;
            renameSync(staged, target);
        }
        zipLock.assertAndRenew();
        renameSync(stagedZip, zipPath);
    } catch (error) {
        if (mutationsStarted && zipLock.isOwned()) {
            for (const { target, backup, existed } of backups) {
                if (existed) {
                    copyFileSync(backup, target);
                } else if (existsSync(target)) {
                    rmSync(target, { force: true });
                }
            }
            if (zipBackup) copyFileSync(zipBackup, zipPath);
        }
        throw error;
    } finally {
        rmSync(workRoot, { recursive: true, force: true });
    }
}

async function extractZipEntry(zipPath, zipEntry, targetPath) {
    const archive = inspectZip(zipPath);
    const entry = archive.entries.find(({ name }) => name === zipEntry);
    if (!entry) {
        throw new Error(`ZIP entry does not exist: ${zipEntry}`);
    }
    if ((entry.flags & 0x0001) !== 0) {
        throw new Error(`encrypted ZIP entries are not supported: ${zipEntry}`);
    }
    if (![0, ZIP_DEFLATE_METHOD].includes(entry.compressionMethod)) {
        throw new Error(`unsupported ZIP compression method ${entry.compressionMethod}: ${zipEntry}`);
    }
    mkdirSync(dirname(targetPath), { recursive: true });
    if (entry.compressedSize === 0) {
        writeFileSync(targetPath, '');
        if (entry.uncompressedSize !== 0 || entry.crc32 !== 0) {
            throw new Error(`ZIP entry size or checksum is invalid: ${zipEntry}`);
        }
        return;
    }
    const dataStart = localEntryDataStart(zipPath, entry);
    const verifier = new Crc32Counter();
    const input = createReadStream(zipPath, {
        start: dataStart,
        end: dataStart + entry.compressedSize - 1,
    });
    const output = createWriteStream(targetPath, { flags: 'wx' });
    if (entry.compressionMethod === ZIP_DEFLATE_METHOD) {
        await pipeline(input, createInflateRaw(), verifier, output);
    } else {
        await pipeline(input, verifier, output);
    }
    if (verifier.size !== entry.uncompressedSize || verifier.digest() !== entry.crc32) {
        rmSync(targetPath, { force: true });
        throw new Error(`ZIP entry size or checksum is invalid: ${zipEntry}`);
    }
}

async function replaceZipEntry(zipPath, zipEntry, sourcePath, targetZipPath) {
    const archive = inspectZip(zipPath);
    const sourceDescriptor = openSync(zipPath, 'r');
    const targetDescriptor = openSync(targetZipPath, 'wx');
    const copiedEntries = [];
    let writeOffset = 0;
    try {
        const localEntries = [...archive.entries].sort((left, right) => left.localHeaderOffset - right.localHeaderOffset);
        for (const [index, entry] of localEntries.entries()) {
            const entryEnd = localEntries[index + 1]?.localHeaderOffset ?? archive.centralDirectoryOffset;
            if (entry.name === zipEntry) continue;
            const localHeaderOffset = writeOffset;
            writeOffset += copyFileRange(
                sourceDescriptor,
                targetDescriptor,
                entry.localHeaderOffset,
                entryEnd - entry.localHeaderOffset,
                writeOffset,
            );
            copiedEntries.push({ entry, localHeaderOffset });
        }

        const replacement = await writeReplacementEntry({
            targetZipPath,
            targetDescriptor,
            sourcePath,
            zipEntry,
            localHeaderOffset: writeOffset,
        });
        writeOffset = replacement.endOffset;
        const centralDirectoryOffset = writeOffset;
        for (const { entry, localHeaderOffset } of copiedEntries) {
            const header = Buffer.from(entry.centralHeader);
            header.writeUInt32LE(localHeaderOffset, 42);
            writeSync(targetDescriptor, header, 0, header.length, writeOffset);
            writeOffset += header.length;
        }
        writeSync(targetDescriptor, replacement.centralHeader, 0, replacement.centralHeader.length, writeOffset);
        writeOffset += replacement.centralHeader.length;
        const entryCount = copiedEntries.length + 1;
        const centralDirectorySize = writeOffset - centralDirectoryOffset;
        assertClassicZipLimits({ entryCount, centralDirectoryOffset, centralDirectorySize });
        const endRecord = Buffer.alloc(22 + archive.comment.length);
        endRecord.writeUInt32LE(ZIP_END_OF_CENTRAL_DIRECTORY, 0);
        endRecord.writeUInt16LE(entryCount, 8);
        endRecord.writeUInt16LE(entryCount, 10);
        endRecord.writeUInt32LE(centralDirectorySize, 12);
        endRecord.writeUInt32LE(centralDirectoryOffset, 16);
        endRecord.writeUInt16LE(archive.comment.length, 20);
        archive.comment.copy(endRecord, 22);
        writeSync(targetDescriptor, endRecord, 0, endRecord.length, writeOffset);
    } finally {
        closeSync(sourceDescriptor);
        closeSync(targetDescriptor);
    }
}

async function writeReplacementEntry({ targetZipPath, targetDescriptor, sourcePath, zipEntry, localHeaderOffset }) {
    const name = Buffer.from(zipEntry, 'utf8');
    if (name.length > ZIP_MAX_UINT16) {
        throw new Error(`ZIP entry name is too long: ${zipEntry}`);
    }
    const flags = ZIP_UTF8_FLAG | ZIP_DATA_DESCRIPTOR_FLAG;
    const localHeader = Buffer.alloc(30 + name.length);
    localHeader.writeUInt32LE(ZIP_LOCAL_FILE_HEADER, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(flags, 6);
    localHeader.writeUInt16LE(ZIP_DEFLATE_METHOD, 8);
    localHeader.writeUInt16LE(name.length, 26);
    name.copy(localHeader, 30);
    writeSync(targetDescriptor, localHeader, 0, localHeader.length, localHeaderOffset);

    const sourceCounter = new Crc32Counter();
    const compressedCounter = new ByteCounter();
    const dataOffset = localHeaderOffset + localHeader.length;
    await pipeline(
        createReadStream(sourcePath),
        sourceCounter,
        createDeflateRaw(),
        compressedCounter,
        createWriteStream(targetZipPath, {
            fd: targetDescriptor,
            start: dataOffset,
            autoClose: false,
        }),
    );
    assertClassicZipLimits({
        uncompressedSize: sourceCounter.size,
        compressedSize: compressedCounter.size,
        localHeaderOffset,
    });
    const descriptorOffset = dataOffset + compressedCounter.size;
    const descriptor = Buffer.alloc(16);
    descriptor.writeUInt32LE(ZIP_DATA_DESCRIPTOR, 0);
    descriptor.writeUInt32LE(sourceCounter.digest(), 4);
    descriptor.writeUInt32LE(compressedCounter.size, 8);
    descriptor.writeUInt32LE(sourceCounter.size, 12);
    writeSync(targetDescriptor, descriptor, 0, descriptor.length, descriptorOffset);

    const centralHeader = Buffer.alloc(46 + name.length);
    centralHeader.writeUInt32LE(ZIP_CENTRAL_DIRECTORY_HEADER, 0);
    centralHeader.writeUInt16LE(0x0314, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(flags, 8);
    centralHeader.writeUInt16LE(ZIP_DEFLATE_METHOD, 10);
    centralHeader.writeUInt32LE(sourceCounter.digest(), 16);
    centralHeader.writeUInt32LE(compressedCounter.size, 20);
    centralHeader.writeUInt32LE(sourceCounter.size, 24);
    centralHeader.writeUInt16LE(name.length, 28);
    centralHeader.writeUInt32LE(localHeaderOffset, 42);
    name.copy(centralHeader, 46);
    return {
        centralHeader,
        endOffset: descriptorOffset + descriptor.length,
    };
}

function inspectZip(zipPath) {
    const descriptor = openSync(zipPath, 'r');
    try {
        const fileSize = statSync(zipPath).size;
        const tailSize = Math.min(fileSize, ZIP_MAX_UINT16 + 22);
        const tail = Buffer.alloc(tailSize);
        readSync(descriptor, tail, 0, tail.length, fileSize - tailSize);
        const endOffsetInTail = findZipEndRecord(tail);
        if (endOffsetInTail < 0) {
            throw new Error(`invalid ZIP end record: ${zipPath}`);
        }
        const diskNumber = tail.readUInt16LE(endOffsetInTail + 4);
        const centralDirectoryDisk = tail.readUInt16LE(endOffsetInTail + 6);
        const entriesOnDisk = tail.readUInt16LE(endOffsetInTail + 8);
        const entryCount = tail.readUInt16LE(endOffsetInTail + 10);
        const centralDirectorySize = tail.readUInt32LE(endOffsetInTail + 12);
        const centralDirectoryOffset = tail.readUInt32LE(endOffsetInTail + 16);
        const commentLength = tail.readUInt16LE(endOffsetInTail + 20);
        if (diskNumber !== 0 || centralDirectoryDisk !== 0 || entriesOnDisk !== entryCount) {
            throw new Error('multi-disk ZIP archives are not supported');
        }
        if (entryCount === ZIP_MAX_UINT16
            || centralDirectorySize === ZIP_MAX_UINT32
            || centralDirectoryOffset === ZIP_MAX_UINT32) {
            throw new Error('ZIP64 archives are not supported');
        }
        if (centralDirectoryOffset + centralDirectorySize > fileSize) {
            throw new Error(`invalid ZIP central directory bounds: ${zipPath}`);
        }
        const centralDirectory = Buffer.alloc(centralDirectorySize);
        readSync(descriptor, centralDirectory, 0, centralDirectory.length, centralDirectoryOffset);
        const entries = [];
        let offset = 0;
        while (offset < centralDirectory.length) {
            if (offset + 46 > centralDirectory.length
                || centralDirectory.readUInt32LE(offset) !== ZIP_CENTRAL_DIRECTORY_HEADER) {
                throw new Error(`invalid ZIP central directory entry: ${zipPath}`);
            }
            const nameLength = centralDirectory.readUInt16LE(offset + 28);
            const extraLength = centralDirectory.readUInt16LE(offset + 30);
            const commentLength = centralDirectory.readUInt16LE(offset + 32);
            const headerLength = 46 + nameLength + extraLength + commentLength;
            if (offset + headerLength > centralDirectory.length) {
                throw new Error(`truncated ZIP central directory entry: ${zipPath}`);
            }
            const compressedSize = centralDirectory.readUInt32LE(offset + 20);
            const uncompressedSize = centralDirectory.readUInt32LE(offset + 24);
            const localHeaderOffset = centralDirectory.readUInt32LE(offset + 42);
            if ([compressedSize, uncompressedSize, localHeaderOffset].includes(ZIP_MAX_UINT32)) {
                throw new Error('ZIP64 entries are not supported');
            }
            entries.push({
                name: centralDirectory.subarray(offset + 46, offset + 46 + nameLength).toString('utf8'),
                flags: centralDirectory.readUInt16LE(offset + 8),
                compressionMethod: centralDirectory.readUInt16LE(offset + 10),
                crc32: centralDirectory.readUInt32LE(offset + 16),
                compressedSize,
                uncompressedSize,
                localHeaderOffset,
                centralHeader: centralDirectory.subarray(offset, offset + headerLength),
            });
            offset += headerLength;
        }
        if (entries.length !== entryCount) {
            throw new Error(`ZIP entry count mismatch: ${entries.length} !== ${entryCount}`);
        }
        return {
            entries,
            centralDirectoryOffset,
            comment: Buffer.from(tail.subarray(endOffsetInTail + 22, endOffsetInTail + 22 + commentLength)),
        };
    } finally {
        closeSync(descriptor);
    }
}

function localEntryDataStart(zipPath, entry) {
    const descriptor = openSync(zipPath, 'r');
    try {
        const header = Buffer.alloc(30);
        readSync(descriptor, header, 0, header.length, entry.localHeaderOffset);
        if (header.readUInt32LE(0) !== ZIP_LOCAL_FILE_HEADER) {
            throw new Error(`invalid ZIP local header: ${entry.name}`);
        }
        return entry.localHeaderOffset + 30 + header.readUInt16LE(26) + header.readUInt16LE(28);
    } finally {
        closeSync(descriptor);
    }
}

function copyFileRange(sourceDescriptor, targetDescriptor, sourceOffset, length, targetOffset) {
    const buffer = Buffer.alloc(Math.min(ZIP_COPY_BUFFER_SIZE, Math.max(length, 1)));
    let copied = 0;
    while (copied < length) {
        const requested = Math.min(buffer.length, length - copied);
        const bytesRead = readSync(sourceDescriptor, buffer, 0, requested, sourceOffset + copied);
        if (bytesRead === 0) throw new Error('unexpected end of ZIP entry');
        writeSync(targetDescriptor, buffer, 0, bytesRead, targetOffset + copied);
        copied += bytesRead;
    }
    return copied;
}

function assertClassicZipLimits(values) {
    for (const [name, value] of Object.entries(values)) {
        const limit = name === 'entryCount' ? ZIP_MAX_UINT16 : ZIP_MAX_UINT32;
        if (!Number.isSafeInteger(value) || value < 0 || value >= limit) {
            throw new Error(`ZIP64 is required for ${name}: ${value}`);
        }
    }
}

function findZipEndRecord(buffer) {
    for (let offset = buffer.length - 22; offset >= 0; offset -= 1) {
        if (buffer.readUInt32LE(offset) !== ZIP_END_OF_CENTRAL_DIRECTORY) continue;
        const commentLength = buffer.readUInt16LE(offset + 20);
        if (offset + 22 + commentLength === buffer.length) return offset;
    }
    return -1;
}

async function acquireZipLock(zipPath) {
    const lockPath = `${resolve(zipPath)}.lock`;
    const startedAt = Date.now();
    while (true) {
        try {
            mkdirSync(lockPath);
            const token = randomUUID();
            const ownerPath = join(lockPath, 'owner.json');
            const createdAt = new Date().toISOString();
            try {
                writeFileSync(ownerPath, JSON.stringify({ pid: process.pid, token, createdAt }), { flag: 'wx' });
            } catch (error) {
                rmSync(lockPath, { recursive: true, force: true });
                throw error;
            }
            const heartbeat = startZipLockHeartbeat(ownerPath, token);
            let heartbeatFailure = null;
            let released = false;
            heartbeat.on('error', (error) => {
                heartbeatFailure = error;
            });
            heartbeat.on('exit', (code) => {
                if (!released) heartbeatFailure = new Error(`ZIP lock heartbeat exited unexpectedly: ${code}`);
            });
            const isOwned = () => {
                try {
                    return JSON.parse(readFileSync(ownerPath, 'utf8')).token === token;
                } catch {
                    return false;
                }
            };
            return {
                isOwned,
                assertAndRenew() {
                    if (heartbeatFailure) throw heartbeatFailure;
                    if (!isOwned()) throw new Error(`ZIP artifact lock ownership was lost: ${zipPath}`);
                    const now = new Date();
                    try {
                        const owner = JSON.parse(readFileSync(ownerPath, 'utf8'));
                        if (owner.token !== token) throw new Error('owner token changed');
                        utimesSync(ownerPath, now, now);
                        const confirmed = JSON.parse(readFileSync(ownerPath, 'utf8'));
                        if (confirmed.token !== token) throw new Error('owner token changed');
                    } catch (error) {
                        throw new Error(`ZIP artifact lock ownership was lost: ${zipPath}`, { cause: error });
                    }
                },
                release() {
                    released = true;
                    heartbeat.terminate();
                    try {
                        const owner = JSON.parse(readFileSync(ownerPath, 'utf8'));
                        if (owner.token === token) rmSync(lockPath, { recursive: true, force: true });
                    } catch {
                        // 이미 회수된 lock은 해제 완료로 본다.
                    }
                },
            };
        } catch (error) {
            if (error.code !== 'EEXIST') throw error;
            if (removeExpiredZipLock(lockPath)) continue;
            if (Date.now() - startedAt >= ZIP_LOCK_TIMEOUT_MS) {
                throw new Error(`timed out waiting for ZIP artifact lock: ${zipPath}`);
            }
            await delay(ZIP_LOCK_POLL_MS);
        }
    }
}

function startZipLockHeartbeat(ownerPath, token) {
    const heartbeat = new Worker(`
        const { workerData } = require('node:worker_threads');
        const { readFileSync, utimesSync } = require('node:fs');
        let interval;
        const touch = () => {
            try {
                const owner = JSON.parse(readFileSync(workerData.ownerPath, 'utf8'));
                if (owner.token !== workerData.token) {
                    clearInterval(interval);
                    return;
                }
                const now = new Date();
                utimesSync(workerData.ownerPath, now, now);
            } catch {
                clearInterval(interval);
            }
        };
        interval = setInterval(touch, workerData.intervalMs);
        touch();
    `, {
        eval: true,
        workerData: { ownerPath, token, intervalMs: ZIP_LOCK_HEARTBEAT_MS },
    });
    heartbeat.unref();
    return heartbeat;
}

function removeExpiredZipLock(lockPath) {
    const ownerPath = join(lockPath, 'owner.json');
    let ownerMtime;
    try {
        JSON.parse(readFileSync(ownerPath, 'utf8'));
        ownerMtime = statSync(ownerPath).mtimeMs;
    } catch {
        try {
            ownerMtime = existsSync(ownerPath) ? statSync(ownerPath).mtimeMs : statSync(lockPath).mtimeMs;
        } catch {
            return true;
        }
        if (Date.now() - ownerMtime < ZIP_OWNER_WRITE_GRACE_MS) return false;
        return reclaimZipLock(lockPath);
    }
    if (Date.now() - ownerMtime < ZIP_LOCK_LEASE_MS) return false;
    return reclaimZipLock(lockPath);
}

function reclaimZipLock(lockPath) {
    const staleLockPath = `${lockPath}.stale-${randomUUID()}`;
    try {
        renameSync(lockPath, staleLockPath);
        rmSync(staleLockPath, { recursive: true, force: true });
        return true;
    } catch {
        return !existsSync(lockPath);
    }
}

class ByteCounter extends Transform {
    size = 0;

    _transform(chunk, encoding, callback) {
        this.size += chunk.length;
        callback(null, chunk);
    }
}

class Crc32Counter extends ByteCounter {
    #crc = ZIP_MAX_UINT32;

    _transform(chunk, encoding, callback) {
        for (const byte of chunk) {
            this.#crc = CRC32_TABLE[(this.#crc ^ byte) & 0xff] ^ (this.#crc >>> 8);
        }
        super._transform(chunk, encoding, callback);
    }

    digest() {
        return (this.#crc ^ ZIP_MAX_UINT32) >>> 0;
    }
}

function buildCrc32Table() {
    return Uint32Array.from({ length: 256 }, (_, index) => {
        let value = index;
        for (let bit = 0; bit < 8; bit += 1) {
            value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
        }
        return value >>> 0;
    });
}
