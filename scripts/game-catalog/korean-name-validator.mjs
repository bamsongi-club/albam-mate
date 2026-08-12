export function validateKoreanName(bggId, nameKo, englishName) {
    const errors = [];
    if (!bggId || Number.isNaN(Number(bggId))) {
        errors.push(`유효하지 않은 bgg_id: ${bggId}`);
    }
    if (!nameKo || typeof nameKo !== 'string' || nameKo.trim().length === 0) {
        errors.push(`Empty nameKo for bgg_id ${bggId}`);
    }
    if (nameKo && nameKo.length > 255) {
        errors.push(`Length exceeds 255 chars for bgg_id ${bggId}: ${nameKo.length}`);
    }
    if (nameKo && /[\uFFFD]/.test(nameKo)) {
        errors.push(`Encoding artifact found in bgg_id ${bggId}`);
    }
    if (nameKo && /&(amp|lt|gt|quot|#39);/.test(nameKo)) {
        errors.push(`Unescaped HTML entity in bgg_id ${bggId}: ${nameKo}`);
    }
    return {
        valid: errors.length === 0,
        errors
    };
}
