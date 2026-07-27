import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, extname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const skillDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(skillDirectory, "..", "..", "..");
const registryPath = resolve(skillDirectory, "templates.json");
const issueFormDirectory = resolve(repositoryRoot, ".github", "ISSUE_TEMPLATE");
const errors = [];

function repositoryPath(path) {
    return relative(repositoryRoot, path).replaceAll("\\", "/");
}

function readJson(path) {
    try {
        return JSON.parse(readFileSync(path, "utf8"));
    } catch (error) {
        errors.push(`${repositoryPath(path)} JSON 파싱 실패: ${error.message}`);
        return null;
    }
}

function readYamlScalar(source, key) {
    const line = source.split(/\r?\n/).find((candidate) => candidate.startsWith(`${key}:`));
    if (!line) {
        return null;
    }

    const value = line.slice(line.indexOf(":") + 1).trim();
    if (
        value.length >= 2 &&
        ((value.startsWith('"') && value.endsWith('"')) ||
            (value.startsWith("'") && value.endsWith("'")))
    ) {
        return value.slice(1, -1);
    }
    return value;
}

if (!existsSync(registryPath)) {
    errors.push(`${repositoryPath(registryPath)} 파일이 없습니다.`);
}
if (!existsSync(issueFormDirectory)) {
    errors.push(`${repositoryPath(issueFormDirectory)} 디렉터리가 없습니다.`);
}

const registry = existsSync(registryPath) ? readJson(registryPath) : null;
if (registry && registry.version !== 1) {
    errors.push("templates.json의 version은 1이어야 합니다.");
}
if (registry && (!Array.isArray(registry.templates) || registry.templates.length === 0)) {
    errors.push("templates.json의 templates는 비어 있지 않은 배열이어야 합니다.");
}

const ids = new Set();
const registeredForms = new Set();

for (const [index, template] of (registry?.templates ?? []).entries()) {
    const location = `templates[${index}]`;
    if (!template || typeof template !== "object" || Array.isArray(template)) {
        errors.push(`${location}은 객체여야 합니다.`);
        continue;
    }

    if (typeof template.id !== "string" || !/^[a-z0-9-]+$/.test(template.id)) {
        errors.push(`${location}.id는 영문 소문자·숫자·하이픈만 사용해야 합니다.`);
    } else if (ids.has(template.id)) {
        errors.push(`중복 id: ${template.id}`);
    } else {
        ids.add(template.id);
    }

    if (typeof template.enabled !== "boolean") {
        errors.push(`${location}.enabled는 boolean이어야 합니다.`);
    }
    for (const key of ["template", "titlePrefix", "intent"]) {
        if (typeof template[key] !== "string" || template[key].trim() === "") {
            errors.push(`${location}.${key}는 비어 있지 않은 문자열이어야 합니다.`);
        }
    }
    if (
        !Array.isArray(template.signals) ||
        template.signals.length === 0 ||
        template.signals.some((signal) => typeof signal !== "string" || signal.trim() === "")
    ) {
        errors.push(`${location}.signals는 비어 있지 않은 문자열 배열이어야 합니다.`);
    }
    if (
        !Array.isArray(template.labels) ||
        template.labels.some((label) => typeof label !== "string" || label.trim() === "")
    ) {
        errors.push(`${location}.labels는 문자열 배열이어야 합니다.`);
    }

    if (typeof template.template !== "string") {
        continue;
    }
    const normalizedTemplate = template.template.replaceAll("\\", "/");
    if (!normalizedTemplate.startsWith(".github/ISSUE_TEMPLATE/")) {
        errors.push(`${location}.template은 .github/ISSUE_TEMPLATE 아래 파일이어야 합니다.`);
        continue;
    }
    if (!new Set([".yml", ".yaml"]).has(extname(normalizedTemplate).toLowerCase())) {
        errors.push(`${location}.template은 yml 또는 yaml 파일이어야 합니다.`);
        continue;
    }
    if (registeredForms.has(normalizedTemplate)) {
        errors.push(`중복 template: ${normalizedTemplate}`);
    }
    registeredForms.add(normalizedTemplate);

    const formPath = resolve(repositoryRoot, normalizedTemplate);
    const allowedPrefix = `${resolve(issueFormDirectory)}${sep}`.toLowerCase();
    if (!formPath.toLowerCase().startsWith(allowedPrefix)) {
        errors.push(`${location}.template 경로가 Issue Form 디렉터리를 벗어납니다.`);
        continue;
    }
    if (!existsSync(formPath) || !statSync(formPath).isFile()) {
        errors.push(`${normalizedTemplate} 파일이 없습니다.`);
        continue;
    }

    const form = readFileSync(formPath, "utf8");
    if (!readYamlScalar(form, "name")) {
        errors.push(`${normalizedTemplate}에 name이 없습니다.`);
    }
    const actualPrefix = readYamlScalar(form, "title");
    if (!actualPrefix) {
        errors.push(`${normalizedTemplate}에 title이 없습니다.`);
    } else if (
        typeof template.titlePrefix === "string" &&
        actualPrefix.trim() !== template.titlePrefix.trim()
    ) {
        errors.push(
            `${normalizedTemplate} title(${actualPrefix.trim()})과 registry titlePrefix(${template.titlePrefix.trim()})가 다릅니다.`,
        );
    }
}

if (existsSync(issueFormDirectory)) {
    const actualForms = readdirSync(issueFormDirectory, { withFileTypes: true })
        .filter(
            (entry) =>
                entry.isFile() &&
                /\.ya?ml$/i.test(entry.name) &&
                entry.name.toLowerCase() !== "config.yml",
        )
        .map((entry) => `.github/ISSUE_TEMPLATE/${entry.name}`)
        .sort();

    for (const form of actualForms) {
        if (!registeredForms.has(form)) {
            errors.push(`레지스트리에 등록되지 않은 Issue Form: ${form}`);
        }
    }
    for (const form of registeredForms) {
        if (!actualForms.includes(form)) {
            errors.push(`Issue Form 목록에 없는 레지스트리 항목: ${form}`);
        }
    }
}

if (errors.length > 0) {
    console.error(`Issue template registry validation failed:\n- ${errors.join("\n- ")}`);
    process.exitCode = 1;
} else {
    const enabled = registry.templates.filter((template) => template.enabled).map((template) => template.id);
    console.log(`Validated ${registry.templates.length} issue templates. Enabled: ${enabled.join(", ")}`);
}
