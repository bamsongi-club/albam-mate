import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const workflowPath = path.join(repositoryRoot, '.github/workflows/p2-cd.yml');
const triggerWorkflowPath = path.join(repositoryRoot, '.github/workflows/p2-cd-trigger.yml');

function reusableWorkflow() {
    return fs.readFileSync(workflowPath, 'utf8');
}

function triggerWorkflow() {
    return fs.readFileSync(triggerWorkflowPath, 'utf8');
}

test('T1 workflow_run의 같은 저장소 develop 성공 CI head_sha만 배포 source로 사용한다', () => {
    const contents = triggerWorkflow();

    assert.match(contents, /^name:\s*P2 CD trigger$/m);
    assert.match(contents, /workflow_run:/);
    assert.match(contents, /workflows:\s*\[CI\]/);
    assert.match(contents, /types:\s*\[completed\]/);
    assert.match(contents, /github\.event\.workflow_run\.conclusion == 'success'/);
    assert.match(contents, /github\.event\.workflow_run\.event == 'push'/);
    assert.match(contents, /github\.event\.workflow_run\.head_branch == 'develop'/);
    assert.match(contents, /github\.event\.workflow_run\.head_repository\.full_name == github\.repository/);
    assert.match(contents, /github\.event\.workflow_run\.head_sha/);
    assert.match(contents, /run_number/);
    assert.match(contents, /workflow_id:\s*['"]?ci\.yml/);
    assert.match(contents, /higher successful develop push CI run exists/);
    assert.match(contents, /uses:\s*bamsongi-club\/albam-mate\/\.github\/workflows\/p2-cd\.yml@develop/);
    assert.doesNotMatch(contents, /concurrency:/);
    assert.match(contents, /\/\^\[0-9a-f\]\{40\}\$\//);
    assert.doesNotMatch(contents, /github\.sha|github\.ref|refs\/heads\/develop/);
});

test('T2 workflow는 SHA pin과 OIDC로 같은 revision의 ARM64 immutable digest를 검증한다', () => {
    const contents = reusableWorkflow();
    const publishImagesJob = contents.slice(
        contents.indexOf('  publish-images:'),
        contents.indexOf('  deploy-p2:'),
    );

    assert.match(contents, /^name:\s*P2 CD$/m);
    assert.match(contents, /workflow_call:/);
    assert.match(publishImagesJob, /runs-on:\s*ubuntu-24\.04-arm/);
    assert.match(publishImagesJob, /timeout-minutes:\s*20/);
    assert.doesNotMatch(publishImagesJob, /runs-on:\s*ubuntu-latest/);
    assert.match(contents, /group:\s*p2-deploy/);
    assert.match(contents, /cancel-in-progress:\s*false/);
    assert.match(contents, /id-token:\s*write/);
    assert.match(contents, /vars\.P2_CD_ENABLED == 'true'/);
    assert.match(contents, /vars\.P2_IMAGE_PUBLISH_ROLE_ARN/);
    assert.match(contents, /vars\.P2_DEPLOY_ROLE_ARN/);
    assert.match(contents, /aws-actions\/configure-aws-credentials@[0-9a-f]{40}/);
    assert.match(contents, /actions\/checkout@[0-9a-f]{40}/);
    assert.match(contents, /linux\/arm64/);
    assert.match(contents, /org\.opencontainers\.image\.revision/);
    assert.match(contents, /git rev-parse HEAD/);
    assert.match(contents, /docker buildx build --platform linux\/arm64/);
    assert.match(contents, /--file \"\$dockerfile\"/);
    assert.match(contents, /frontend\/Dockerfile\.production/);
    assert.match(contents, /\/usr\/local\/bin\/albam-mate-entrypoint/);
    assert.match(contents, /immutable tag already exists/);
    assert.match(contents, /imagetools inspect/);
    assert.match(contents, /backend.*digest|digest.*backend/);
    assert.match(contents, /web.*digest|digest.*web/);
    assert.match(contents, /if ! docker buildx build --platform linux\/arm64[\s\S]*return 1/);
    assert.match(contents, /if ! digest="\$\(aws ecr describe-images[\s\S]*return 1/);
    assert.match(contents, /if ! verify_image[\s\S]*return 1/);
    assert.match(contents, /if ! backend_digest="\$\(resolve_or_publish/);
    assert.match(contents, /if ! web_digest="\$\(resolve_or_publish/);
    assert.match(contents, /require_digest backend "\$backend_digest"/);
    assert.match(contents, /require_digest web "\$web_digest"/);
    for (const action of contents.matchAll(/^\s*uses:\s*[^\n]+/gm)) {
        assert.match(action[0], /@[0-9a-f]{40}(?:\s|$)/, `full SHA pin is required: ${action[0]}`);
    }
});

test('T3·T5·T6·T7 workflow는 custom SSM document만 호출하고 LKG 기반 rollback receipt를 남긴다', () => {
    const contents = reusableWorkflow();

    assert.match(contents, /aws ssm get-parameter/);
    assert.match(contents, /aws ssm send-command/);
    assert.match(contents, /--document-name \"\$SSM_DOCUMENT\"/);
    assert.match(contents, /preflight-app2-current/);
    assert.match(contents, /migrate-app2-candidate/);
    assert.match(contents, /deploy-app2-candidate/);
    assert.match(contents, /deploy-app1-candidate/);
    assert.match(contents, /rollback-app2/);
    assert.match(contents, /rollback-app1-app2/);
    assert.match(contents, /aws ssm put-parameter/);
    assert.match(contents, /LKG/);
    assert.match(
        contents,
        /invoke deploy-app2-candidate[\s\S]*invoke verify-app2-candidate[\s\S]*invoke deploy-app1-candidate[\s\S]*invoke verify-app1-candidate[\s\S]*aws ssm put-parameter/,
    );
    assert.match(contents, /receipt allowlist: SHA, CI URL, digest, role\/target\/command identifier, phase, LKG version\/status/);
    assert.doesNotMatch(contents, /secrets\.|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|ssh |scp |AWS-RunShellScript/);
    assert.doesNotMatch(contents, /password|cookie|csrf|smoke|deployment-verification\.env/i);
    assert.doesNotMatch(contents, /terraform apply|docker compose up/);
});
