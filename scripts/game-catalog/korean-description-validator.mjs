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
    if (description && /[\uFFFD]/.test(description)) {
        errors.push(`Encoding artifact in description for bgg_id ${bggId}`);
    }
    if (detailDescription && /[\uFFFD]/.test(detailDescription)) {
        errors.push(`Encoding artifact in detail_description for bgg_id ${bggId}`);
    }
    if (description && /&(amp|lt|gt|quot|#39);/.test(description)) {
        errors.push(`Unescaped HTML entity in description for bgg_id ${bggId}`);
    }
    return {
        valid: errors.length === 0,
        errors
    };
}
