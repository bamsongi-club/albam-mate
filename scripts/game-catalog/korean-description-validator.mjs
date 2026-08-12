export function validateDescription(bggId, description, detailDescription) {
    const errors = [];
    if (!bggId || Number.isNaN(Number(bggId))) {
        errors.push(`유효하지 않은 bgg_id: ${bggId}`);
    }
    if (!description || typeof description !== 'string' || description.trim().length === 0) {
        errors.push(`Empty description for bgg_id ${bggId}`);
    }
    if (!detailDescription || typeof detailDescription !== 'string' || detailDescription.trim().length === 0) {
        errors.push(`Empty detail_description for bgg_id ${bggId}`);
    }
    validateKoreanText(description, `description for bgg_id ${bggId}`, errors);
    validateKoreanText(detailDescription, `detail_description for bgg_id ${bggId}`, errors);
    return {
        valid: errors.length === 0,
        errors
    };
}

const ALLOWED_LATIN_TOKENS = new Set(['bgg', 'sf', 'vr', 'hd', '3d', '2d']);
const ENGLISH_PROSE_TOKENS = new Set([
    'a', 'about', 'and', 'cards', 'draw', 'each', 'for', 'game', 'in', 'is', 'of', 'on', 'play', 'player',
    'players', 'rules', 'the', 'this', 'to', 'use', 'with', 'you',
]);

function validateKoreanText(value, label, errors) {
    if (!value || typeof value !== 'string' || value.trim().length === 0) return;
    if (/[�]/.test(value)) errors.push(`Encoding artifact in ${label}`);
    if (/&(amp|lt|gt|quot|#39);/.test(value)) errors.push(`Unescaped HTML entity in ${label}`);

    const hangulChars = (value.match(/[가-힣]/g) ?? []).length;
    const latinTokens = value.match(/[A-Za-z]{2,}/g) ?? [];
    const residualTokens = latinTokens.filter((token) => !ALLOWED_LATIN_TOKENS.has(token.toLowerCase()));
    const latinChars = residualTokens.reduce((total, token) => total + token.length, 0);
    const latinRatio = latinChars / Math.max(1, hangulChars + latinChars);
    const containsEnglishProse = residualTokens.some((token) => ENGLISH_PROSE_TOKENS.has(token.toLowerCase()));

    if (hangulChars === 0 || containsEnglishProse || (residualTokens.length > 0 && latinRatio >= 0.25)) {
        errors.push(`Untranslated or excessively English ${label}`);
    }
}
