import { createHash } from "node:crypto";

const FEATURED_OVERRIDES = new Map([
	["2040", { featuredOrder: 1, nameKo: "핸드 관리" }],
	["2072", { featuredOrder: 2, nameKo: "주사위 굴림" }],
	["2004", { featuredOrder: 3, nameKo: "셋 컬렉션" }],
	["2023", { featuredOrder: 4, nameKo: "협력 게임" }],
	["2002", { featuredOrder: 5, nameKo: "타일 놓기" }],
	["2011", { featuredOrder: 6, nameKo: "조립 보드" }],
	["2819", { featuredOrder: 7, nameKo: "솔로/솔로테어 게임" }],
	["2082", { featuredOrder: 8, nameKo: "일꾼 놓기" }],
]);

export function extractMechanismCatalog(games, manifest) {
	if (!manifest?.mechanismCatalog) {
		return null;
	}
	const metadata = manifest.mechanismCatalog;
	const errors = validateMetadata(metadata);
	const approvedCodes = approvedCodeMap(metadata, errors);
	const mechanisms = new Map();
	const relations = [];
	const relationKeys = new Set();
	for (const game of games) {
		if (!Array.isArray(game?.mechanisms)) {
			errors.push({ code: "INVALID_MECHANISMS", message: "각 게임의 mechanisms 배열이 필요합니다." });
			continue;
		}
		for (const mechanism of game.mechanisms) {
			const bggMechanismId = canonicalId(mechanism?.bgg_id);
			if (!bggMechanismId || !completedText(mechanism?.name) || !completedText(mechanism?.name_ko)) {
				errors.push({ code: "INVALID_MECHANISM", message: "BGG 메커니즘 ID와 한영 이름이 필요합니다." });
				continue;
			}
			const code = approvedCodes.get(bggMechanismId);
			if (!code) {
				errors.push({
					code: "MISSING_APPROVED_MECHANISM_CODE",
					message: "BGG 메커니즘의 승인된 공개 code 매핑이 없습니다.",
					bgg_mechanism_id: bggMechanismId,
				});
				continue;
			}
			const override = FEATURED_OVERRIDES.get(bggMechanismId);
			const row = {
				bgg_mechanism_id: Number(bggMechanismId),
				code,
				name_ko: override?.nameKo ?? mechanism.name_ko,
				name_en: mechanism.name,
				featured_order: override?.featuredOrder ?? null,
				is_public: true,
				source_reference: metadata.sourceReference,
				reviewed_by: metadata.reviewedBy,
				reviewed_at: metadata.reviewedAt,
			};
			const existing = mechanisms.get(bggMechanismId);
			if (existing && (existing.name_en !== row.name_en || existing.name_ko !== row.name_ko)) {
				errors.push({ code: "MECHANISM_MAPPING_CONFLICT", message: "같은 BGG 메커니즘의 이름 매핑이 일치하지 않습니다." });
			} else {
				mechanisms.set(bggMechanismId, row);
			}
			const gameId = canonicalId(game.bgg_id);
			const relationKey = `${gameId}:${bggMechanismId}`;
			if (!gameId || relationKeys.has(relationKey)) {
				errors.push({ code: "DUPLICATE_MECHANISM_RELATION", message: "같은 게임과 메커니즘 관계는 한 번만 허용됩니다." });
			} else {
				relationKeys.add(relationKey);
				relations.push({ bgg_id: Number(gameId), bgg_mechanism_id: Number(bggMechanismId) });
			}
		}
	}
	const catalog = [...mechanisms.values()].sort((left, right) => left.bgg_mechanism_id - right.bgg_mechanism_id);
	const codes = new Set();
	for (const mechanism of catalog) {
		if (!mechanism.code || mechanism.code.length > 64 || codes.has(mechanism.code)) {
			errors.push({ code: "INVALID_MECHANISM_CODE", message: "메커니즘 코드는 고유한 ASCII UPPER_SNAKE_CASE여야 합니다." });
		}
		codes.add(mechanism.code);
	}
	if (catalog.length !== metadata.publishedCount || relations.length !== metadata.relationCount) {
		errors.push({
			code: "MECHANISM_COUNT_MISMATCH",
			message: "승인된 메커니즘 또는 관계 건수와 입력 산출값이 다릅니다.",
			mechanisms: catalog.length,
			relations: relations.length,
		});
	}
	return { catalog, relations, errors };
}

export function renderMechanismUpsertSql(catalog, relations) {
	const mechanismValues = catalog.map(renderMechanismValue).join(",\n");
	const relationValues = relations
		.map(({ bgg_id, bgg_mechanism_id }) => `    (${bgg_id}, ${bgg_mechanism_id})`)
		.join(",\n");
	return `BEGIN;\nSET LOCAL standard_conforming_strings = on;\nSET LOCAL TIME ZONE 'UTC';\n\nINSERT INTO game_mechanisms (\n    bgg_mechanism_id, code, name_ko, name_en, featured_order, is_public,\n    source_reference, reviewed_by, reviewed_at, created_at, updated_at\n) VALUES\n${mechanismValues}\nON CONFLICT (bgg_mechanism_id) DO UPDATE SET\n    code = EXCLUDED.code,\n    name_ko = EXCLUDED.name_ko,\n    name_en = EXCLUDED.name_en,\n    featured_order = EXCLUDED.featured_order,\n    is_public = EXCLUDED.is_public,\n    source_reference = EXCLUDED.source_reference,\n    reviewed_by = EXCLUDED.reviewed_by,\n    reviewed_at = EXCLUDED.reviewed_at,\n    updated_at = CURRENT_TIMESTAMP;\n\nINSERT INTO game_mechanism_relations (game_id, mechanism_id)\nSELECT game.id, mechanism.id\nFROM (VALUES\n${relationValues}\n) AS source(bgg_id, bgg_mechanism_id)\nJOIN games game ON game.bgg_id = source.bgg_id\nJOIN game_mechanisms mechanism ON mechanism.bgg_mechanism_id = source.bgg_mechanism_id\nON CONFLICT (game_id, mechanism_id) DO NOTHING;\n\nCOMMIT;\n`;
}

function validateMetadata(metadata) {
	const errors = [];
	if (
		!Number.isSafeInteger(metadata?.publishedCount) ||
		!Number.isSafeInteger(metadata?.relationCount) ||
		!completedText(metadata?.sourceReference) ||
		!completedText(metadata?.reviewedBy) ||
		!/^\d{4}-\d{2}-\d{2}T/.test(metadata?.reviewedAt ?? "") ||
		!metadata?.approvedCodes ||
		!/^[0-9a-f]{64}$/.test(metadata?.approvedCodesSha256 ?? "")
	) {
		errors.push({ code: "INVALID_MECHANISM_MANIFEST", message: "메커니즘 승인 범위와 검수 근거가 필요합니다." });
	}
	return errors;
}

function approvedCodeMap(metadata, errors) {
	const entries = Object.entries(metadata?.approvedCodes ?? {});
	const canonical = JSON.stringify(Object.fromEntries(entries.sort(([left], [right]) => left.localeCompare(right))));
	if (sha256(canonical) !== metadata?.approvedCodesSha256) {
		errors.push({
			code: "APPROVED_CODE_MAPPING_MISMATCH",
			message: "승인된 메커니즘 code 매핑 checksum이 일치하지 않습니다.",
		});
	}
	return new Map(
		entries
			.filter(([, code]) => typeof code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(code))
			.map(([bggMechanismId, code]) => [bggMechanismId, code]),
	);
}

function renderMechanismValue(row) {
	return `    (${row.bgg_mechanism_id}, ${sql(row.code)}, ${sql(row.name_ko)}, ${sql(row.name_en)}, ${row.featured_order ?? "NULL"}, true, ${sql(row.source_reference)}, ${sql(row.reviewed_by)}, ${sql(row.reviewed_at)}::timestamptz, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)`;
}

function canonicalId(value) {
	const number = Number(value);
	return Number.isSafeInteger(number) && number > 0 ? String(number) : null;
}

function sha256(value) {
	return createHash("sha256").update(value).digest("hex");
}

function completedText(value) {
	return typeof value === "string" && value.trim() !== "";
}

function sql(value) {
	return `'${String(value).replaceAll("'", "''")}'`;
}
