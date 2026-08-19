import assert from 'node:assert/strict';
import test from 'node:test';
import { buildKoreanDescriptionUpsertSql } from './export-korean-descriptions.mjs';

test('한국어 설명을 bgg_id 오름차순 UPSERT로 내보낸다', () => {
    const { rows, sql } = buildApprovedDescriptionSql([
        koreanRow(20), koreanRow(3),
    ], approvedManifest());

    assert.equal(rows, 2);
    assert.match(sql, /BEGIN;/u);
    assert.match(sql, /UPDATE games AS g/u);
    assert.match(sql, /COMMIT;\n$/u);
    assert.ok(sql.indexOf('(3, ') < sql.indexOf('(20, '), 'bgg_id 오름차순이어야 한다');
});

test('작은따옴표를 이스케이프해 SQL이 깨지지 않는다', () => {
    const { sql } = buildApprovedDescriptionSql([
        { bgg_id: 7, description: "'재미'있는 게임입니다", detail_description: '설명이 길게 이어집니다' },
    ], approvedManifest());

    assert.match(sql, /'''재미''있는 게임입니다'/u);
});

test('번역되지 않은 설명은 내보내지 않는다', () => {
    assert.throws(
        () => buildApprovedDescriptionSql([
            { bgg_id: 1, description: 'Wingspan is a competitive board game.', detail_description: '한국어 상세 설명입니다' },
        ], approvedManifest()),
        /정상 한국어 설명이 아니다/u,
    );
    assert.throws(
        () => buildApprovedDescriptionSql([
            { bgg_id: 1, description: '한국어 간단 설명입니다', detail_description: 'Players draft cards and score points.' },
        ], approvedManifest()),
        /정상 한국어 설명이 아니다/u,
    );
});

test('영문 단어만 섞인 한국어 설명은 그대로 내보낸다', () => {
    assert.doesNotThrow(() => buildApprovedDescriptionSql([
        { bgg_id: 1, description: 'Paths of Glory의 전투를 다루는 게임입니다', detail_description: '3M이 만든 고전입니다' },
    ], approvedManifest()));
});

test('빈 필드와 중복 bgg_id, 빈 입력을 막는다', () => {
    assert.throws(
        () => buildApprovedDescriptionSql([{ bgg_id: 1, description: '  ', detail_description: '한국어 설명입니다' }]),
        /description가 비어 있다/u,
    );
    assert.throws(
        () => buildApprovedDescriptionSql([koreanRow(5), koreanRow(5)]),
        /bgg_id가 중복됐다/u,
    );
    assert.throws(() => buildApprovedDescriptionSql([]), /비어 있지 않은 JSON 배열/u);
    assert.throws(
        () => buildApprovedDescriptionSql([{ bgg_id: 0, description: '한국어', detail_description: '한국어' }]),
        /bgg_id가 양의 정수가 아니다/u,
    );
});

test('승인 provenance가 없으면 보정 SQL을 만들지 않는다', () => {
    assert.throws(
        () => buildKoreanDescriptionUpsertSql([koreanRow(1)], { approved: true, testOnly: false }),
        /schemaVersion|releaseId|설명 provenance가 승인되지 않았다/u,
    );
});

test('구체 승인 release가 없으면 보정 SQL을 만들지 않는다', () => {
    assert.throws(
        () => buildKoreanDescriptionUpsertSql([koreanRow(1)], sparseApprovedManifest()),
        /schemaVersion|releaseId/u,
    );
});

test('입력 description artifact의 checksum과 행 수가 release와 다르면 차단한다', () => {
    assert.throws(
        () => buildKoreanDescriptionUpsertSql(
            [koreanRow(1)],
            approvedManifest(),
            { actualDescriptionInput: {
                fileName: 'descriptions.json',
                sha256: '0'.repeat(64),
                rows: 2,
            } },
        ),
        /inputs\.descriptions\.(sha256|rows)/u,
    );
});

test('description-correction scope가 없으면 보정 SQL을 만들지 않는다', () => {
    const manifest = approvedManifest();
    manifest.approvedProcessingScopes = [
        'service-load',
        'search-text-assembly',
        'embedding-generation',
    ];

    assert.throws(
        () => buildKoreanDescriptionUpsertSql([koreanRow(1)], manifest, {
            actualDescriptionInput: approvedDescriptionInput(),
        }),
        /description-correction/u,
    );
});

function koreanRow(bggId) {
    return {
        bgg_id: bggId,
        description: '타일을 놓아 도시를 넓히는 게임입니다',
        detail_description: '자기 차례에 타일을 한 장 놓고 말을 올립니다',
    };
}

function buildApprovedDescriptionSql(rows, manifest = approvedManifest()) {
    return buildKoreanDescriptionUpsertSql(rows, manifest, {
        actualDescriptionInput: approvedDescriptionInput(),
    });
}

function approvedDescriptionInput() {
    return {
        fileName: 'descriptions.json',
        sha256: 'a'.repeat(64),
        rows: 1,
    };
}

function approvedManifest() {
    const provenance = {
        source: '검수된 테스트 입력',
        sourceVersion: 'description-v1',
        processing: 'human-reviewed',
        status: 'approved',
        reviewedBy: 'test-reviewer',
        reviewedAt: '2026-08-13T00:00:00Z',
    };
    return {
        schemaVersion: 1,
        releaseId: 'catalog-2026-08-13-001',
        datasetId: 'bgg-catalog-2026-08-13',
        approved: true,
        testOnly: false,
        approval: {
            reviewedBy: 'test-reviewer',
            reviewedAt: '2026-08-13T00:00:00Z',
            references: ['https://github.com/bamsongi-club/albam-mate/issues/747'],
        },
        approvedFields: [
            'name',
            'english_name',
            'alias',
            'tag',
            'description',
            'detail_description',
        ],
        approvedProcessingScopes: [
            'service-load',
            'search-text-assembly',
            'embedding-generation',
            'description-correction',
        ],
        search_text: {
            fields: ['name', 'english_name', 'description', 'detail_description'],
            sourceFieldVersion: 'catalog-fields-v1',
            assemblyRuleVersion: 'search-text-v1',
        },
        embedding: {
            provider: 'test-provider',
            model: 'test-model',
            modelVersion: 'test-model-v1',
            dimensions: 3,
            indexVersion: 'search-04-test-v1',
            output: {
                path: 'output/catalog-embeddings.json',
                sha256: '7'.repeat(64),
                rows: 1,
            },
        },
        inputs: {
            catalog: artifact('catalog.json'),
            names: artifact('names.json'),
            descriptions: artifact('descriptions.json'),
            mechanismDictionary: artifact('mechanisms.json'),
            themeDictionary: artifact('themes.json'),
            relations: artifact('relations.json'),
        },
        coverage: {
            catalogIds: coverage(1),
            relationGameIds: coverage(1),
            mechanismIds: coverage(1),
            themeIds: coverage(1),
        },
        sources: {
            games: source('games.json'),
            ranks: source('ranks.csv'),
        },
        provenance: {
            descriptionFields: {
                description: provenance,
                detail_description: provenance,
            },
        },
        outputs: {
            serviceCatalog: output('service-catalog.json'),
            upsertSql: output('upsert-games.sql'),
        },
    };
}

function sparseApprovedManifest() {
    const provenance = {
        source: '검수된 테스트 입력',
        sourceVersion: 'description-v1',
        processing: 'human-reviewed',
        status: 'approved',
        reviewedBy: 'test-reviewer',
        reviewedAt: '2026-08-13T00:00:00Z',
    };
    return {
        approved: true,
        testOnly: false,
        provenance: {
            descriptionFields: {
                description: provenance,
                detail_description: provenance,
            },
        },
    };
}

function artifact(fileName) {
    return { status: 'approved', path: `input/${fileName}`, sha256: 'a'.repeat(64), rows: 1 };
}

function coverage(rows) {
    return { rows, sha256: 'b'.repeat(64) };
}

function source(fileName) {
    return { fileName, sha256: 'c'.repeat(64), rows: 1 };
}

function output(fileName) {
    return { path: `output/${fileName}`, sha256: 'd'.repeat(64), rows: 1 };
}
