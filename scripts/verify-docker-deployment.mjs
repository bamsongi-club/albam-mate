import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const nginxImage =
    'nginx:stable-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46';
const httpEchoImage =
    'hashicorp/http-echo:1.0.0@sha256:fcb75f691c8b0414d670ae570240cbf95502cc18a9ba57e982ecac589760a186';
const opensslImage =
    'alpine/openssl:latest@sha256:045a40a53b8e283cff95052e0c39f256b7467d48c7445260d4f180fc0e767999';
const releaseSha = '0123456789abcdef0123456789abcdef01234567';
const contractRunId = `${process.pid}-${randomUUID().replaceAll('-', '').slice(0, 8)}`;
const dockerConfigDirectory = createTempDirectory('albam-mate-contract-docker-config');
configureDockerCliPlugins(dockerConfigDirectory);

function contractImage(repository, testId) {
    return `${repository}:contract-${testId.toLowerCase()}-${contractRunId}`;
}

function contractResource(base) {
    return `${base}-${contractRunId}`;
}

function contractProject(testId) {
    return `albammatecontract${testId.toLowerCase()}${contractRunId.replaceAll('-', '')}`;
}

function fail(message) {
    throw new Error(message);
}

function assert(condition, message) {
    if (!condition) fail(message);
}

function tail(value, length = 6000) {
    return value.length <= length ? value : value.slice(-length);
}

function run(command, args, options = {}) {
    const result = spawnSync(command, args, {
        cwd: options.cwd ?? repositoryRoot,
        env: options.env ?? process.env,
        encoding: 'utf8',
        maxBuffer: 64 * 1024 * 1024,
        timeout: options.timeout ?? 600_000,
        windowsHide: true,
    });
    if (result.error) throw result.error;
    if (!options.allowFailure && result.status !== 0) {
        fail(
            `${command} ${args.join(' ')} failed with ${result.status}\n${tail(result.stdout ?? '')}${tail(result.stderr ?? '')}`,
        );
    }
    return {
        status: result.status,
        stdout: result.stdout ?? '',
        stderr: result.stderr ?? '',
    };
}

function docker(args, options = {}) {
    return run('docker', args, {
        ...options,
        env: {
            ...(options.env ?? process.env),
            DOCKER_CONFIG: dockerConfigDirectory,
        },
    });
}

function sleep(milliseconds) {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function waitFor(description, callback, attempts = 30) {
    let lastError;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
        try {
            const value = callback();
            if (value !== false && value !== null && value !== undefined) return value;
        } catch (error) {
            lastError = error;
        }
        sleep(1000);
    }
    fail(`${description} timed out${lastError ? `: ${lastError.message}` : ''}`);
}

function inspectExists(kind, name) {
    return docker([kind, 'inspect', name], { allowFailure: true }).status === 0;
}

function removeContainer(name) {
    if (inspectExists('container', name)) docker(['rm', '-f', name]);
}

function removeNetwork(name) {
    if (inspectExists('network', name)) docker(['network', 'rm', name]);
}

function imageExists(name) {
    return docker(['image', 'inspect', name], { allowFailure: true }).status === 0;
}

function removeImage(name) {
    if (imageExists(name)) docker(['image', 'rm', name]);
}

function assertUnusedImages(names) {
    for (const name of names) {
        assert(!imageExists(name), `refusing to reuse existing image: ${name}`);
    }
}

function buildOwnedImage(ownedImages, image, buildArguments) {
    assertUnusedImages([image]);
    docker(['build', '--tag', image, ...buildArguments]);
    ownedImages.add(image);
}

function removeOwnedImages(ownedImages) {
    for (const image of [...ownedImages].reverse()) removeImage(image);
}

function assertUnusedResources(containerNames, networkName) {
    for (const name of containerNames) {
        assert(!inspectExists('container', name), `refusing to reuse existing container: ${name}`);
    }
    if (networkName) assert(!inspectExists('network', networkName), `refusing to reuse existing network: ${networkName}`);
}

function dockerInspectJson(name) {
    return JSON.parse(docker(['inspect', name]).stdout)[0];
}

function createTempDirectory(prefix) {
    return fs.mkdtempSync(path.join(os.tmpdir(), `${prefix}-`));
}

function configureDockerCliPlugins(directory) {
    const configuredDockerDirectory = process.env.DOCKER_CONFIG ?? path.join(os.homedir(), '.docker');
    const cliPluginsDirectory = path.join(configuredDockerDirectory, 'cli-plugins');
    fs.writeFileSync(path.join(directory, 'config.json'), JSON.stringify({ cliPluginsExtraDirs: [cliPluginsDirectory] }));
}

function removeOwnedTempDirectory(directory, prefix) {
    const resolved = path.resolve(directory);
    const tempRoot = `${path.resolve(os.tmpdir())}${path.sep}`;
    assert(resolved.startsWith(tempRoot), `refusing to remove path outside temp: ${resolved}`);
    assert(path.basename(resolved).startsWith(`${prefix}-`), `unexpected temp directory: ${resolved}`);
    fs.rmSync(resolved, { recursive: true, force: true });
}

function createCertificate(directory) {
    docker([
        'run',
        '--rm',
        '--mount',
        `type=bind,source=${directory},target=/certs`,
        opensslImage,
        'req',
        '-x509',
        '-nodes',
        '-newkey',
        'rsa:2048',
        '-keyout',
        '/certs/privkey.pem',
        '-out',
        '/certs/fullchain.pem',
        '-days',
        '1',
        '-subj',
        '/CN=localhost',
        '-addext',
        'subjectAltName=DNS:localhost,IP:127.0.0.1',
    ]);
}

function productionEnvironment(certificateDirectory) {
    return {
        ...process.env,
        ALBAM_MATE_IMAGE_NAMESPACE: 'registry.example.com/albam-mate',
        ALBAM_MATE_RELEASE: releaseSha,
        ALBAM_MATE_TLS_PATH: certificateDirectory,
        ALBAM_MATE_RDS_CA_PATH: path.join(certificateDirectory, 'rds-ca-bundle.pem'),
        ALBAM_MATE_DB_HOST: 'db.example.internal',
        ALBAM_MATE_DB_NAME: 'albam_mate',
        ALBAM_MATE_DB_USER: 'verify_user',
        ALBAM_MATE_DB_PASSWORD: 'verify_password',
    };
}

function compose(files, project, args, options = {}) {
    const fileArgs = files.flatMap((file) => ['-f', file]);
    return docker(['compose', '-p', project, ...fileArgs, ...args], options);
}

function projectResourceIds(project) {
    return {
        containers: docker(['ps', '-aq', '--filter', `label=com.docker.compose.project=${project}`]).stdout.trim(),
        volumes: docker(['volume', 'ls', '-q', '--filter', `label=com.docker.compose.project=${project}`]).stdout.trim(),
        networks: docker(['network', 'ls', '-q', '--filter', `label=com.docker.compose.project=${project}`]).stdout.trim(),
    };
}

function assertProjectUnused(project) {
    const resources = projectResourceIds(project);
    assert(!resources.containers && !resources.volumes && !resources.networks, `refusing to reuse Compose project: ${project}`);
}

function cleanupProject(files, project, env) {
    const resources = projectResourceIds(project);
    if (resources.containers || resources.volumes || resources.networks) {
        compose(files, project, ['down', '--volumes'], { env, allowFailure: true });
    }
}

function serviceContainer(files, project, service, env) {
    const id = compose(files, project, ['ps', '-q', service], { env }).stdout.trim();
    assert(id, `missing Compose service container: ${service}`);
    return id;
}

function assertHealthy(containerId, service) {
    const health = docker(['inspect', '--format', '{{.State.Health.Status}}', containerId]).stdout.trim();
    assert(health === 'healthy', `${service} is not healthy: ${health}`);
}

function loadProductionConfig(env) {
    return JSON.parse(
        docker(['compose', '-f', 'compose.production.yml', 'config', '--format', 'json'], { env }).stdout,
    );
}

function assertProductionConfig(config) {
    const serviceNames = Object.keys(config.services).sort();
    assert(JSON.stringify(serviceNames) === JSON.stringify(['spring', 'web']), `unexpected services: ${serviceNames}`);
    for (const serviceName of serviceNames) {
        const service = config.services[serviceName];
        assert(service.platform === 'linux/arm64', `${serviceName} platform is ${service.platform}`);
        assert(service.pull_policy === 'always', `${serviceName} pull_policy is ${service.pull_policy}`);
        assert(service.build === undefined, `${serviceName} has a source build fallback`);
        assert(service.restart === 'unless-stopped', `${serviceName} restart is ${service.restart}`);
        assert(service.healthcheck, `${serviceName} healthcheck is missing`);
    }
    assert(
        config.services.spring.image === `registry.example.com/albam-mate/backend:${releaseSha}`,
        'Spring does not use namespace/backend with the shared release',
    );
    assert(
        config.services.web.image === `registry.example.com/albam-mate/web:${releaseSha}`,
        'web does not use namespace/web with the shared release',
    );
    for (const serviceName of serviceNames) {
        assert(
            config.services[serviceName].environment.ALBAM_MATE_RELEASE === releaseSha,
            `${serviceName} does not receive the release gate value`,
        );
    }
    assert(
        config.services.spring.healthcheck.test[1].includes('/app/backend-entrypoint.sh --validate-release-only'),
        'Spring healthcheck does not enforce the release gate',
    );
    assert(
        config.services.web.healthcheck.test[1].includes('/usr/local/bin/albam-mate-entrypoint --validate-release-only'),
        'web healthcheck does not enforce the release gate',
    );
    assert(config.services.spring.ports === undefined, 'Spring publishes a host port');
    const webPort = config.services.web.ports[0];
    assert(String(webPort.published) === '443' && String(webPort.target) === '8443', 'web is not 443 -> 8443');
    const springCa = config.services.spring.volumes.find(
        (volume) => volume.target === '/etc/albam-mate/rds-ca-bundle.pem',
    );
    const webTls = config.services.web.volumes.find((volume) => volume.target === '/etc/albam-mate/tls');
    assert(springCa?.read_only === true, 'RDS CA mount is not read-only');
    assert(webTls?.read_only === true, 'TLS mount is not read-only');
    assert(config.services.web.depends_on.spring.condition === 'service_healthy', 'web does not wait for Spring health');
    assert(config.services.web.depends_on.spring.restart === true, 'web dependency restart is not enabled');
}

function assertReleaseGate(image, additionalEnvironment = {}) {
    const runGate = (release) => {
        const environment = { ...additionalEnvironment, ALBAM_MATE_RELEASE: release };
        const environmentArguments = Object.entries(environment).flatMap(([key, value]) => ['--env', `${key}=${value}`]);
        return docker(['run', '--rm', ...environmentArguments, image, '--validate-release-only'], {
            allowFailure: true,
        });
    };
    assert(runGate(releaseSha).status === 0, `${image} rejected a full Git SHA`);
    for (const invalidRelease of ['latest', releaseSha.slice(0, 7), `${releaseSha}0`]) {
        assert(runGate(invalidRelease).status !== 0, `${image} accepted invalid release: ${invalidRelease}`);
    }
}

function verifyT1() {
    const image = contractImage('albam-mate-spring', 'T1');
    const network = contractResource('albam-mate-contract-t1');
    const postgres = contractResource('albam-mate-contract-t1-postgres');
    const spring = contractResource('albam-mate-contract-t1-spring');
    const ownedImages = new Set();
    const ownedContainers = new Set();
    let networkCreated = false;
    try {
        assertUnusedResources([postgres, spring], network);
        buildOwnedImage(ownedImages, image, ['.']);
        const inspect = JSON.parse(docker(['image', 'inspect', image]).stdout)[0];
        assert(inspect.Config.User === '10001:10001', `unexpected configured user: ${inspect.Config.User}`);
        assert(docker(['run', '--rm', '--entrypoint', 'id', image, '-u']).stdout.trim() === '10001', 'runtime UID is not 10001');
        docker(['run', '--rm', '--entrypoint', 'test', image, '-s', '/app/app.jar']);
        docker(['run', '--rm', '--entrypoint', 'sh', image, '-c', 'command -v wget >/dev/null']);
        const java = docker(['run', '--rm', '--entrypoint', 'java', image, '-version']);
        assert(`${java.stdout}${java.stderr}`.includes('21.'), 'runtime Java is not 21');
        docker(['network', 'create', network]);
        networkCreated = true;
        docker([
            'run',
            '-d',
            '--name',
            postgres,
            '--network',
            network,
            '--network-alias',
            'postgres',
            '--env',
            'POSTGRES_DB=albam_mate_verify',
            '--env',
            'POSTGRES_USER=verify_user',
            '--env',
            'POSTGRES_PASSWORD=verify_password',
            '--env',
            'TZ=UTC',
            '--env',
            'PGTZ=UTC',
            '--health-cmd',
            'pg_isready -U verify_user -d albam_mate_verify',
            '--health-interval',
            '2s',
            '--health-timeout',
            '5s',
            '--health-retries',
            '20',
            'postgres:18.4',
            'postgres',
            '-c',
            'timezone=UTC',
        ]);
        ownedContainers.add(postgres);
        waitFor('PostgreSQL health', () => {
            const health = docker(['inspect', '--format', '{{.State.Health.Status}}', postgres], {
                allowFailure: true,
            });
            return health.status === 0 && health.stdout.trim() === 'healthy';
        });
        docker([
            'run',
            '-d',
            '--name',
            spring,
            '--network',
            network,
            '--env',
            'SPRING_PROFILES_ACTIVE=local',
            '--env',
            'ALBAM_MATE_LOCAL_DB_HOST=postgres',
            '--env',
            'ALBAM_MATE_LOCAL_DB_PORT=5432',
            '--env',
            'ALBAM_MATE_LOCAL_DB_NAME=albam_mate_verify',
            '--env',
            'ALBAM_MATE_LOCAL_DB_USER=verify_user',
            '--env',
            'ALBAM_MATE_LOCAL_DB_PASSWORD=verify_password',
            '--env',
            'TZ=UTC',
            image,
        ]);
        ownedContainers.add(spring);
        const api = waitFor(
            'Spring default ENTRYPOINT/CMD application startup',
            () => {
                const response = docker(['exec', spring, 'wget', '-qO-', 'http://127.0.0.1:8080/api/games?size=1'], {
                    allowFailure: true,
                });
                return response.status === 0 && response.stdout.includes('"status"') ? response.stdout : false;
            },
            150,
        );
        assert(api.includes('"status"'), 'Spring application did not serve GET /api/games?size=1');
        const pidOneUid = docker([
            'exec',
            spring,
            'sh',
            '-c',
            "awk '/^Uid:/ { print $2; exit }' /proc/1/status",
        ]).stdout.trim();
        assert(pidOneUid === '10001', `Spring PID 1 UID is ${pidOneUid}`);
        console.log('T1 PASS: Java 21 executable JAR starts through the default ENTRYPOINT/CMD as PID 1 UID 10001 and serves GET /api/games?size=1.');
    } finally {
        for (const name of [...ownedContainers].reverse()) removeContainer(name);
        if (networkCreated) removeNetwork(network);
        removeOwnedImages(ownedImages);
    }
}

function verifyT2() {
    const image = contractImage('albam-mate-web-local', 'T2');
    const network = contractResource('albam-mate-contract-t2');
    const web = contractResource('albam-mate-contract-t2-web');
    const springOne = contractResource('albam-mate-contract-t2-spring-one');
    const springTwo = contractResource('albam-mate-contract-t2-spring-two');
    const filler = contractResource('albam-mate-contract-t2-filler');
    const containers = [web, springOne, springTwo, filler];
    const ownedContainers = new Set();
    const ownedImages = new Set();
    let networkCreated = false;
    try {
        assertUnusedResources(containers, network);
        buildOwnedImage(ownedImages, image, ['frontend']);
        docker(['network', 'create', network]);
        networkCreated = true;
        docker(['run', '-d', '--name', springOne, '--network', network, '--network-alias', 'spring', httpEchoImage, '-listen=:8080', '-text=v1']);
        ownedContainers.add(springOne);
        docker(['run', '-d', '--name', web, '--network', network, image]);
        ownedContainers.add(web);
        const first = waitFor('initial /api proxy', () => {
            const response = docker(['exec', web, 'wget', '-qO-', 'http://127.0.0.1/api/games'], {
                allowFailure: true,
            });
            return response.status === 0 && response.stdout.trim() === 'v1' ? response.stdout.trim() : false;
        });
        assert(first === 'v1', 'initial proxy response is not v1');
        const firstIp = dockerInspectJson(springOne).NetworkSettings.Networks[network].IPAddress;
        removeContainer(springOne);
        ownedContainers.delete(springOne);
        docker(['run', '-d', '--name', filler, '--network', network, httpEchoImage, '-listen=:8080', '-text=filler']);
        ownedContainers.add(filler);
        docker(['run', '-d', '--name', springTwo, '--network', network, '--network-alias', 'spring', httpEchoImage, '-listen=:8080', '-text=v2']);
        ownedContainers.add(springTwo);
        const secondIp = dockerInspectJson(springTwo).NetworkSettings.Networks[network].IPAddress;
        assert(firstIp !== secondIp, `Spring IP did not change: ${firstIp}`);
        const second = waitFor('Nginx DNS re-resolution', () => {
            const response = docker(['exec', web, 'wget', '-qO-', 'http://127.0.0.1/api/games'], {
                allowFailure: true,
            });
            return response.status === 0 && response.stdout.trim() === 'v2' ? response.stdout.trim() : false;
        });
        assert(second === 'v2', 'recreated Spring response is not v2');
        const spa = docker(['exec', web, 'wget', '-qO-', 'http://127.0.0.1/rooms/example']).stdout;
        assert(spa.includes('<div id="root">'), 'SPA fallback did not return index.html');
        console.log(`T2 PASS: SPA works and Nginx reconnected from ${firstIp} to ${secondIp}.`);
    } finally {
        for (const name of [...ownedContainers].reverse()) removeContainer(name);
        if (networkCreated) removeNetwork(network);
        removeOwnedImages(ownedImages);
    }
}

function verifyT3() {
    const project = contractProject('T3');
    const contractDirectory = createTempDirectory('albam-mate-contract-t3');
    const override = path.join(contractDirectory, 'compose.override.yml');
    const springImage = contractImage('albam-mate-spring', 'T3');
    const viteImage = contractImage('albam-mate-vite', 'T3');
    const files = ['compose.local.yml', override];
    const ownedImages = new Set();
    let composeAttempted = false;
    const env = {
        ...process.env,
        ALBAM_MATE_LOCAL_DB_NAME: 'albam_mate_verify',
        ALBAM_MATE_LOCAL_DB_USER: 'verify_user',
        ALBAM_MATE_LOCAL_DB_PASSWORD: 'verify_password',
        ALBAM_MATE_LOCAL_DB_PORT: '0',
        ALBAM_MATE_LOCAL_SPRING_PORT: '0',
        ALBAM_MATE_LOCAL_WEB_PORT: '0',
    };
    try {
        assertProjectUnused(project);
        assertUnusedImages([springImage, viteImage]);
        fs.writeFileSync(
            override,
            `services:\n  spring:\n    image: ${springImage}\n  vite:\n    image: ${viteImage}\n`,
            'utf8',
        );
        compose(files, project, ['build', 'spring'], { env });
        ownedImages.add(springImage);
        compose(files, project, ['build', 'vite'], { env });
        ownedImages.add(viteImage);
        composeAttempted = true;
        compose(files, project, ['up', '-d', '--no-build', '--wait'], { env });
        for (const service of ['postgres', 'spring', 'vite']) {
            const container = serviceContainer(files, project, service, env);
            assertHealthy(container, service);
            const bindings = dockerInspectJson(container).HostConfig.PortBindings;
            assert(Object.keys(bindings).length > 0, `${service} has no host binding`);
            for (const values of Object.values(bindings)) {
                for (const binding of values) assert(binding.HostIp === '127.0.0.1', `${service} binds ${binding.HostIp}`);
            }
        }
        const vite = serviceContainer(files, project, 'vite', env);
        const api = docker(['exec', vite, 'wget', '-qO-', 'http://127.0.0.1/api/games?size=1']).stdout;
        assert(api.includes('"status"'), 'local Compose /api request failed');
        console.log('T3 PASS: PostgreSQL, Spring and Vite are healthy with loopback-only host bindings.');
    } finally {
        if (composeAttempted) cleanupProject(files, project, env);
        removeOwnedImages(ownedImages);
        removeOwnedTempDirectory(contractDirectory, 'albam-mate-contract-t3');
    }
}

function verifyT4() {
    const certificateDirectory = createTempDirectory('albam-mate-contract-t4');
    const springImage = contractImage('albam-mate-spring', 'T4');
    const webImage = contractImage('albam-mate-web-production', 'T4');
    const ownedImages = new Set();
    try {
        const env = productionEnvironment(certificateDirectory);
        const missingRelease = { ...env };
        delete missingRelease.ALBAM_MATE_RELEASE;
        const missingResult = docker(['compose', '-f', 'compose.production.yml', 'config', '--quiet'], {
            env: missingRelease,
            allowFailure: true,
        });
        assert(missingResult.status !== 0, 'production Compose accepted a missing release');
        assertProductionConfig(loadProductionConfig(env));
        buildOwnedImage(ownedImages, springImage, ['.']);
        buildOwnedImage(ownedImages, webImage, ['--file', 'frontend/Dockerfile.production', 'frontend']);
        assertReleaseGate(springImage, { SPRING_PROFILES_ACTIVE: 'production' });
        assertReleaseGate(webImage);
        console.log('T4 PASS: ARM64 images share one required Git SHA, always pull, and have no build fallback.');
    } finally {
        removeOwnedImages(ownedImages);
        removeOwnedTempDirectory(certificateDirectory, 'albam-mate-contract-t4');
    }
}

function writeContextDockerfile(directory) {
    fs.writeFileSync(path.join(directory, 'Dockerfile.contract'), 'FROM alpine:3.22\nCOPY . /context\n', 'utf8');
}

function verifyT5() {
    const rootContext = createTempDirectory('albam-mate-contract-t5-root');
    let frontendContext;
    const rootImage = contractImage('albam-mate-context-root', 'T5');
    const frontendImage = contractImage('albam-mate-context-frontend', 'T5');
    const springImage = contractImage('albam-mate-spring', 'T5');
    const webImage = contractImage('albam-mate-web-production', 'T5');
    const ownedImages = new Set();
    try {
        frontendContext = createTempDirectory('albam-mate-contract-t5-frontend');
        fs.copyFileSync(path.join(repositoryRoot, '.dockerignore'), path.join(rootContext, '.dockerignore'));
        writeContextDockerfile(rootContext);
        fs.writeFileSync(path.join(rootContext, 'allowed.txt'), 'allowed', 'utf8');
        fs.writeFileSync(path.join(rootContext, '.env.production'), 'SECRET_SENTINEL', 'utf8');
        fs.writeFileSync(path.join(rootContext, 'production.env'), 'SECRET_SENTINEL', 'utf8');
        fs.writeFileSync(path.join(rootContext, 'private.key'), 'SECRET_SENTINEL', 'utf8');
        for (const directory of ['docs', 'frontend', 'src/test', 'src/postgresTest']) {
            fs.mkdirSync(path.join(rootContext, directory), { recursive: true });
            fs.writeFileSync(path.join(rootContext, directory, 'sentinel.txt'), 'SECRET_SENTINEL', 'utf8');
        }
        buildOwnedImage(ownedImages, rootImage, ['--file', path.join(rootContext, 'Dockerfile.contract'), rootContext]);
        docker([
            'run',
            '--rm',
            '--entrypoint',
            'sh',
            rootImage,
            '-c',
            'test -f /context/allowed.txt && test ! -e /context/.env.production && test ! -e /context/production.env && test ! -e /context/private.key && test ! -e /context/docs && test ! -e /context/frontend && test ! -e /context/src/test && test ! -e /context/src/postgresTest',
        ]);

        fs.copyFileSync(path.join(repositoryRoot, 'frontend', '.dockerignore'), path.join(frontendContext, '.dockerignore'));
        writeContextDockerfile(frontendContext);
        fs.writeFileSync(path.join(frontendContext, 'allowed.txt'), 'allowed', 'utf8');
        fs.writeFileSync(path.join(frontendContext, '.env.local'), 'SECRET_SENTINEL', 'utf8');
        fs.writeFileSync(path.join(frontendContext, 'production.env'), 'SECRET_SENTINEL', 'utf8');
        fs.writeFileSync(path.join(frontendContext, 'private.key'), 'SECRET_SENTINEL', 'utf8');
        fs.writeFileSync(path.join(frontendContext, 'certificate.pem'), 'SECRET_SENTINEL', 'utf8');
        for (const directory of ['dist', 'node_modules']) {
            fs.mkdirSync(path.join(frontendContext, directory), { recursive: true });
            fs.writeFileSync(path.join(frontendContext, directory, 'sentinel.txt'), 'SECRET_SENTINEL', 'utf8');
        }
        buildOwnedImage(ownedImages, frontendImage, [
            '--file',
            path.join(frontendContext, 'Dockerfile.contract'),
            frontendContext,
        ]);
        docker([
            'run',
            '--rm',
            '--entrypoint',
            'sh',
            frontendImage,
            '-c',
            'test -f /context/allowed.txt && test ! -e /context/.env.local && test ! -e /context/production.env && test ! -e /context/private.key && test ! -e /context/certificate.pem && test ! -e /context/dist && test ! -e /context/node_modules',
        ]);

        buildOwnedImage(ownedImages, springImage, ['.']);
        buildOwnedImage(ownedImages, webImage, ['--file', 'frontend/Dockerfile.production', 'frontend']);
        docker(['run', '--rm', '--entrypoint', 'sh', springImage, '-c', 'test -s /app/app.jar && test ! -e /app/.env.production && test ! -e /app/production.env && test ! -e /workspace']);
        docker(['run', '--rm', '--entrypoint', 'sh', webImage, '-c', 'test -f /usr/share/nginx/html/index.html && test ! -e /usr/share/nginx/html/.env.production && test ! -e /usr/share/nginx/html/production.env && test ! -e /workspace && test ! -e /etc/albam-mate/tls/privkey.pem']);
        console.log('T5 PASS: root/frontend contexts and runtime images exclude env variants, keys and development artifacts.');
    } finally {
        removeOwnedImages(ownedImages);
        removeOwnedTempDirectory(rootContext, 'albam-mate-contract-t5-root');
        if (frontendContext) removeOwnedTempDirectory(frontendContext, 'albam-mate-contract-t5-frontend');
    }
}

function verifyT6() {
    const certificateDirectory = createTempDirectory('albam-mate-contract-t6');
    const network = contractResource('albam-mate-contract-t6');
    const spring = contractResource('albam-mate-contract-t6-spring');
    const web = contractResource('albam-mate-contract-t6-web');
    const webImage = contractImage('albam-mate-web-production', 'T6');
    const ownedImages = new Set();
    let networkCreated = false;
    let springCreated = false;
    let webCreated = false;
    try {
        assertUnusedResources([spring, web], network);
        createCertificate(certificateDirectory);
        fs.copyFileSync(
            path.join(certificateDirectory, 'fullchain.pem'),
            path.join(certificateDirectory, 'rds-ca-bundle.pem'),
        );
        assertProductionConfig(loadProductionConfig(productionEnvironment(certificateDirectory)));
        buildOwnedImage(ownedImages, webImage, ['--file', 'frontend/Dockerfile.production', 'frontend']);
        docker(['network', 'create', network]);
        networkCreated = true;
        docker(['run', '-d', '--name', spring, '--network', network, '--network-alias', 'spring', httpEchoImage, '-listen=:8080', '-text=production-proxy-ok']);
        springCreated = true;
        docker([
            'run',
            '-d',
            '--name',
            web,
            '--env',
            `ALBAM_MATE_RELEASE=${releaseSha}`,
            '--network',
            network,
            '--read-only',
            '--tmpfs',
            '/tmp:rw,noexec,nosuid,size=32m',
            '--security-opt',
            'no-new-privileges:true',
            '--cap-drop',
            'ALL',
            '--cap-add',
            'CHOWN',
            '--cap-add',
            'SETGID',
            '--cap-add',
            'SETUID',
            '--mount',
            `type=bind,source=${certificateDirectory},target=/etc/albam-mate/tls,readonly`,
            webImage,
        ]);
        webCreated = true;
        waitFor('production web health', () => {
            const response = docker(['exec', web, 'wget', '-qO-', 'http://127.0.0.1:8080/healthz'], {
                allowFailure: true,
            });
            return response.status === 0 && response.stdout.trim() === 'ok' ? true : false;
        });
        const proxy = docker([
            'exec',
            web,
            'wget',
            '--no-check-certificate',
            '-qO-',
            'https://127.0.0.1:8443/api/verify',
        ]).stdout.trim();
        assert(proxy === 'production-proxy-ok', `unexpected TLS proxy response: ${proxy}`);
        console.log('T6 PASS: only web publishes 443; TLS and CA mounts are read-only and HTTPS proxies to internal Spring.');
    } finally {
        if (webCreated) removeContainer(web);
        if (springCreated) removeContainer(spring);
        if (networkCreated) removeNetwork(network);
        removeOwnedImages(ownedImages);
        removeOwnedTempDirectory(certificateDirectory, 'albam-mate-contract-t6');
    }
}

function verifyT7() {
    if (process.platform === 'win32') {
        run('cmd.exe', [
            '/d',
            '/s',
            '/c',
            'gradlew.bat test --tests cloud.bamsongi.albammate.global.config.ProductionProfileConfigurationTest --no-daemon',
        ]);
    } else {
        run('./gradlew', [
            'test',
            '--tests',
            'cloud.bamsongi.albammate.global.config.ProductionProfileConfigurationTest',
            '--no-daemon',
        ]);
    }
    console.log('T7 PASS: production datasource, verify-full CA, Flyway/JPA/UTC/forwarded-header/shutdown/cookie properties are fixed by test.');
}

function t8Override(springImage, webImage) {
    const platform = process.arch === 'arm64' ? 'linux/arm64' : 'linux/amd64';
    return `services:
  postgres:
    image: postgres:18.4
    environment:
      POSTGRES_DB: albam_mate_verify
      POSTGRES_USER: verify_user
      POSTGRES_PASSWORD: verify_password
      TZ: UTC
      PGTZ: UTC
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U verify_user -d albam_mate_verify"]
      interval: 2s
      timeout: 5s
      retries: 20
    volumes:
      - postgres_data:/var/lib/postgresql
    networks:
      - application
  spring:
    image: ${springImage}
    platform: ${platform}
    pull_policy: never
    environment:
      SPRING_PROFILES_ACTIVE: local
      ALBAM_MATE_LOCAL_DB_HOST: postgres
      ALBAM_MATE_LOCAL_DB_PORT: 5432
      ALBAM_MATE_LOCAL_DB_NAME: albam_mate_verify
      ALBAM_MATE_LOCAL_DB_USER: verify_user
      ALBAM_MATE_LOCAL_DB_PASSWORD: verify_password
    depends_on:
      postgres:
        condition: service_healthy
  web:
    image: ${webImage}
    platform: ${platform}
    pull_policy: never
    ports: !override
      - "127.0.0.1::8443"
volumes:
  postgres_data:
`;
}

function verifyT8() {
    const project = contractProject('T8');
    const certificateDirectory = createTempDirectory('albam-mate-contract-t8');
    const springImage = contractImage('albam-mate-spring', 'T8');
    const webImage = contractImage('albam-mate-web-production', 'T8');
    const override = path.join(certificateDirectory, 'compose.override.yml');
    const files = ['compose.production.yml', override];
    const ownedImages = new Set();
    let composeAttempted = false;
    try {
        assertProjectUnused(project);
        createCertificate(certificateDirectory);
        fs.copyFileSync(
            path.join(certificateDirectory, 'fullchain.pem'),
            path.join(certificateDirectory, 'rds-ca-bundle.pem'),
        );
        fs.writeFileSync(override, t8Override(springImage, webImage), 'utf8');
        buildOwnedImage(ownedImages, springImage, ['.']);
        buildOwnedImage(ownedImages, webImage, ['--file', 'frontend/Dockerfile.production', 'frontend']);
        const env = productionEnvironment(certificateDirectory);
        composeAttempted = true;
        compose(files, project, ['up', '-d', '--wait'], { env });
        for (const service of ['postgres', 'spring', 'web']) {
            assertHealthy(serviceContainer(files, project, service, env), service);
        }
        const web = serviceContainer(files, project, 'web', env);
        const response = docker([
            'exec',
            web,
            'wget',
            '--no-check-certificate',
            '-qO-',
            'https://127.0.0.1:8443/api/games?size=1',
        ]).stdout;
        assert(response.includes('"status"'), 'production Compose HTTPS API request failed');
        console.log('T8 PASS: production Compose up --wait reports PostgreSQL fixture, Spring and web healthy and serves HTTPS API.');
    } finally {
        if (composeAttempted) {
            const env = productionEnvironment(certificateDirectory);
            cleanupProject(files, project, env);
        }
        removeOwnedImages(ownedImages);
        removeOwnedTempDirectory(certificateDirectory, 'albam-mate-contract-t8');
    }
}

const verifiers = {
    T1: verifyT1,
    T2: verifyT2,
    T3: verifyT3,
    T4: verifyT4,
    T5: verifyT5,
    T6: verifyT6,
    T7: verifyT7,
    T8: verifyT8,
};

try {
    const testId = process.argv[2];
    if (!Object.hasOwn(verifiers, testId)) {
        console.error('Usage: node scripts/verify-docker-deployment.mjs T1|T2|T3|T4|T5|T6|T7|T8');
        process.exitCode = 2;
    } else {
        try {
            verifiers[testId]();
        } catch (error) {
            console.error(`${testId} FAIL: ${error.stack ?? error.message}`);
            process.exitCode = 1;
        }
    }
} finally {
    removeOwnedTempDirectory(dockerConfigDirectory, 'albam-mate-contract-docker-config');
}
