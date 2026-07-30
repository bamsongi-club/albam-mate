import fs from 'node:fs';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const DEFAULT_SCHEMA_PATH = fileURLToPath(
    new URL('../.codex/contracts/backend-implementation-packet.schema.json', import.meta.url),
);

const ISSUE_COMMENT_URL =
    /^https:\/\/github\.com\/[^/?#]+\/[^/?#]+\/issues\/([1-9][0-9]*)#issuecomment-[1-9][0-9]*$/;

function instanceChild(parent, property) {
    if (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(property)) {
        return `${parent}.${property}`;
    }
    return `${parent}[${JSON.stringify(property)}]`;
}

function schemaChild(parent, property) {
    return `${parent}/${String(property).replaceAll('~', '~0').replaceAll('/', '~1')}`;
}

function jsonType(value) {
    if (value === null) return 'null';
    if (Array.isArray(value)) return 'array';
    if (Number.isInteger(value)) return 'integer';
    return typeof value === 'number' ? 'number' : typeof value;
}

function matchesType(value, expected) {
    switch (expected) {
        case 'object':
            return value !== null && typeof value === 'object' && !Array.isArray(value);
        case 'array':
            return Array.isArray(value);
        case 'integer':
            return Number.isInteger(value);
        case 'number':
            return typeof value === 'number' && Number.isFinite(value);
        case 'null':
            return value === null;
        default:
            return typeof value === expected;
    }
}

function resolveLocalRef(rootSchema, ref) {
    if (!ref.startsWith('#/')) {
        throw new Error(`지원하지 않는 외부 JSON Schema 참조입니다: ${ref}`);
    }

    return ref
        .slice(2)
        .split('/')
        .map((token) => token.replaceAll('~1', '/').replaceAll('~0', '~'))
        .reduce((current, token) => {
            if (current === null || typeof current !== 'object' || !(token in current)) {
                throw new Error(`JSON Schema 참조를 찾을 수 없습니다: ${ref}`);
            }
            return current[token];
        }, rootSchema);
}

function addError(errors, instancePath, schemaPath, keyword, message) {
    errors.push({ instancePath, schemaPath, keyword, message });
}

function validateSchemaNode(rootSchema, schema, value, instancePath, schemaPath, errors) {
    if (typeof schema === 'boolean') {
        if (!schema) {
            addError(errors, instancePath, schemaPath, 'falseSchema', '허용되지 않는 값입니다.');
        }
        return;
    }

    if (schema.$ref !== undefined) {
        const referenced = resolveLocalRef(rootSchema, schema.$ref);
        validateSchemaNode(rootSchema, referenced, value, instancePath, schema.$ref, errors);
    }

    if (schema.const !== undefined && !isDeepStrictEqual(value, schema.const)) {
        addError(errors, instancePath, schemaPath, 'const', `${JSON.stringify(schema.const)}이어야 합니다.`);
    }

    if (schema.enum !== undefined && !schema.enum.some((candidate) => isDeepStrictEqual(value, candidate))) {
        addError(errors, instancePath, schemaPath, 'enum', `허용값 ${JSON.stringify(schema.enum)} 중 하나여야 합니다.`);
    }

    if (schema.type !== undefined && !matchesType(value, schema.type)) {
        addError(errors, instancePath, schemaPath, 'type', `${schema.type}이어야 하지만 ${jsonType(value)}입니다.`);
        return;
    }

    if (typeof value === 'string') {
        if (schema.minLength !== undefined && [...value].length < schema.minLength) {
            addError(errors, instancePath, schemaPath, 'minLength', `길이가 ${schema.minLength} 이상이어야 합니다.`);
        }
        if (schema.pattern !== undefined) {
            const pattern = new RegExp(schema.pattern, 'u');
            if (!pattern.test(value)) {
                addError(errors, instancePath, schemaPath, 'pattern', `패턴 ${schema.pattern}에 맞아야 합니다.`);
            }
        }
    }

    if (typeof value === 'number' && schema.minimum !== undefined && value < schema.minimum) {
        addError(errors, instancePath, schemaPath, 'minimum', `${schema.minimum} 이상이어야 합니다.`);
    }

    if (Array.isArray(value)) {
        if (schema.minItems !== undefined && value.length < schema.minItems) {
            addError(errors, instancePath, schemaPath, 'minItems', `항목이 ${schema.minItems}개 이상이어야 합니다.`);
        }
        if (schema.uniqueItems) {
            for (let left = 0; left < value.length; left += 1) {
                for (let right = left + 1; right < value.length; right += 1) {
                    if (isDeepStrictEqual(value[left], value[right])) {
                        addError(errors, `${instancePath}[${right}]`, schemaPath, 'uniqueItems', '중복 항목입니다.');
                    }
                }
            }
        }
        if (schema.items !== undefined) {
            value.forEach((item, index) =>
                validateSchemaNode(
                    rootSchema,
                    schema.items,
                    item,
                    `${instancePath}[${index}]`,
                    schemaChild(schemaPath, 'items'),
                    errors,
                ),
            );
        }
    }

    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
        const properties = schema.properties ?? {};
        for (const required of schema.required ?? []) {
            if (!Object.hasOwn(value, required)) {
                addError(
                    errors,
                    instanceChild(instancePath, required),
                    schemaPath,
                    'required',
                    '필수 속성이 없습니다.',
                );
            }
        }

        for (const [property, propertyValue] of Object.entries(value)) {
            if (Object.hasOwn(properties, property)) {
                validateSchemaNode(
                    rootSchema,
                    properties[property],
                    propertyValue,
                    instanceChild(instancePath, property),
                    schemaChild(schemaChild(schemaPath, 'properties'), property),
                    errors,
                );
            } else if (schema.additionalProperties === false) {
                addError(
                    errors,
                    instanceChild(instancePath, property),
                    schemaPath,
                    'additionalProperties',
                    '선언되지 않은 속성입니다.',
                );
            }
        }
    }
}

export function validateAgainstSchema(schema, value) {
    const errors = [];
    validateSchemaNode(schema, schema, value, '$', '#', errors);
    return errors;
}

function validatePacketRelations(packet) {
    const errors = [];
    const requiredTests = Array.isArray(packet?.requiredTests) ? packet.requiredTests : [];
    const seenIds = new Set();

    requiredTests.forEach((requiredTest, index) => {
        const id = requiredTest?.id;
        if (typeof id === 'string') {
            if (seenIds.has(id)) {
                addError(
                    errors,
                    `$.requiredTests[${index}].id`,
                    '#/relations',
                    'uniqueTId',
                    'T-ID가 중복되었습니다.',
                );
            }
            seenIds.add(id);

            const expected = `T${index + 1}`;
            if (id !== expected) {
                addError(
                    errors,
                    `$.requiredTests[${index}].id`,
                    '#/relations',
                    'continuousTId',
                    `T-ID는 T1부터 배열 순서대로 연속해야 합니다. 기대값: ${expected}`,
                );
            }
        }
    });

    const approval = packet?.testContractApproval;
    const commentMatch = typeof approval?.commentUrl === 'string' ? approval.commentUrl.match(ISSUE_COMMENT_URL) : null;
    if (commentMatch && Number.isInteger(approval.issueNumber)) {
        const urlIssueNumber = Number(commentMatch[1]);
        if (urlIssueNumber !== approval.issueNumber) {
            addError(
                errors,
                '$.testContractApproval.commentUrl',
                '#/relations',
                'approvalIssueNumber',
                `코멘트 URL의 이슈 #${urlIssueNumber}가 승인 이슈 #${approval.issueNumber}와 다릅니다.`,
            );
        }
    }

    if (typeof approval?.commentUrl === 'string') {
        requiredTests.forEach((requiredTest, index) => {
            if (typeof requiredTest?.sourceRef === 'string' && requiredTest.sourceRef !== approval.commentUrl) {
                addError(
                    errors,
                    `$.requiredTests[${index}].sourceRef`,
                    '#/relations',
                    'testSourceApproval',
                    'sourceRef는 사람이 승인한 정본 코멘트 URL과 같아야 합니다.',
                );
            }
        });
    }

    if (typeof packet?.workItem?.id === 'string' && Number.isInteger(approval?.issueNumber)) {
        const issueIdMatch = packet.workItem.id.match(/^#?([1-9][0-9]*)$/);
        if (!issueIdMatch) {
            addError(
                errors,
                '$.workItem.id',
                '#/relations',
                'workItemIssueNumber',
                '위임 작업의 id는 이슈 번호 또는 #이슈번호 형식이어야 합니다.',
            );
        } else if (Number(issueIdMatch[1]) !== approval.issueNumber) {
            addError(
                errors,
                '$.workItem.id',
                '#/relations',
                'workItemIssueNumber',
                `작업 이슈 ${packet.workItem.id}가 승인 이슈 #${approval.issueNumber}와 다릅니다.`,
            );
        }
    }

    return errors;
}

export function validatePacket(packet, schema) {
    return [...validateAgainstSchema(schema, packet), ...validatePacketRelations(packet)];
}

function readJson(filePath, label) {
    let text;
    try {
        text = fs.readFileSync(filePath, 'utf8');
    } catch (error) {
        throw new Error(`${label} 파일을 읽을 수 없습니다 (${filePath}): ${error.message}`);
    }

    try {
        return JSON.parse(text);
    } catch (error) {
        throw new Error(`${label} JSON이 올바르지 않습니다 (${filePath}): ${error.message}`);
    }
}

export function validatePacketFile(packetPath, schemaPath = DEFAULT_SCHEMA_PATH) {
    const resolvedPacketPath = path.resolve(packetPath);
    const resolvedSchemaPath = path.resolve(schemaPath);
    const schema = readJson(resolvedSchemaPath, '스키마');
    const packet = readJson(resolvedPacketPath, '패킷');
    return {
        packet,
        packetPath: resolvedPacketPath,
        schemaPath: resolvedSchemaPath,
        errors: validatePacket(packet, schema),
    };
}

function runCli() {
    if (process.argv.length !== 3) {
        console.error('사용법: node scripts/validate-packet.mjs <packet.json>');
        process.exitCode = 2;
        return;
    }

    try {
        const result = validatePacketFile(process.argv[2]);
        if (result.errors.length > 0) {
            console.error(`패킷 검증 실패: ${result.packetPath}`);
            for (const error of result.errors) {
                console.error(`- ${error.instancePath}: ${error.message} [${error.keyword}]`);
            }
            process.exitCode = 1;
            return;
        }

        console.log(
            `패킷 검증 통과: ${result.packetPath} (schemaVersion ${result.packet.schemaVersion}, 필수 테스트 ${result.packet.requiredTests.length}개)`,
        );
    } catch (error) {
        console.error(`패킷 검증 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) {
    runCli();
}
