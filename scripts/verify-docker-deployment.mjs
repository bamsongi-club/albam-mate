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
        ALBAM_MATE_APP2_HOST: 'app-b.albam-mate.internal',
        ALBAM_MATE_DB_HOST: 'postgres.albam-mate.internal',
        ALBAM_MATE_DB_NAME: 'albam_mate',
        ALBAM_MATE_DB_USER: 'verify_user',
        ALBAM_MATE_DB_PASSWORD: 'verify_password',
        ALBAM_MATE_REDIS_HOST: 'redis.example.internal',
        ALBAM_MATE_REDIS_PORT: '6379',
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

function loadComposeConfig(file, env) {
    return JSON.parse(docker(['compose', '-f', file, 'config', '--format', 'json'], { env }).stdout);
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
    assert(
        config.services.spring.environment.ALBAM_MATE_REDIS_HOST === 'redis.example.internal',
        'Spring does not receive the required Redis host',
    );
    assert(
        config.services.spring.environment.ALBAM_MATE_REDIS_PORT === '6379',
        'Spring does not receive the Redis port',
    );
    assert(
        config.services.spring.environment.JDK_JAVA_OPTIONS === '-Xmx256m',
        'Spring does not receive the P1 heap limit through JDK_JAVA_OPTIONS',
    );
    assert(
        String(config.services.spring.mem_limit) === String(512 * 1024 * 1024) || config.services.spring.mem_limit === '512m',
        `Spring memory limit is ${config.services.spring.mem_limit}`,
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
    assert(config.services.web.ports.length === 1, `web publishes ${config.services.web.ports.length} host ports`);
    const webPort = config.services.web.ports[0];
    assert(String(webPort.published) === '443' && String(webPort.target) === '8443', 'web is not 443 -> 8443');
    const webTls = config.services.web.volumes.find((volume) => volume.target === '/etc/albam-mate/tls');
    assert(webTls?.read_only === true, 'TLS mount is not read-only');
    assert(
        config.services.web.environment.ALBAM_MATE_APP2_HOST === 'app-b.albam-mate.internal',
        'web does not receive the required App2 private DNS host',
    );
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
    const redis = contractResource('albam-mate-contract-t1-redis');
    const spring = contractResource('albam-mate-contract-t1-spring');
    const ownedImages = new Set();
    const ownedContainers = new Set();
    let networkCreated = false;
    try {
        assertUnusedResources([postgres, redis, spring], network);
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
        docker(['run', '-d', '--name', redis, '--network', network, 'redis:8.4-alpine']);
        ownedContainers.add(redis);
        waitFor('Redis health', () => {
            const response = docker(['exec', redis, 'redis-cli', 'ping'], { allowFailure: true });
            return response.status === 0 && response.stdout.trim() === 'PONG';
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
            'ALBAM_MATE_LOCAL_REDIS_HOST=redis',
            '--env',
            'ALBAM_MATE_LOCAL_REDIS_PORT=6379',
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
    const filler = contractResource('albam-mate-contract-t3-filler');
    const files = ['compose.local.yml', override];
    const ownedImages = new Set();
    let composeAttempted = false;
    let fillerCreated = false;
    const env = {
        ...process.env,
        ALBAM_MATE_LOCAL_DB_NAME: 'albam_mate_verify',
        ALBAM_MATE_LOCAL_DB_USER: 'verify_user',
        ALBAM_MATE_LOCAL_DB_PASSWORD: 'verify_password',
        ALBAM_MATE_LOCAL_DB_PORT: '0',
        ALBAM_MATE_LOCAL_REDIS_PORT: '0',
        ALBAM_MATE_LOCAL_PROXY_PORT: '0',
    };
    try {
        assertProjectUnused(project);
        assertUnusedResources([filler], null);
        assertUnusedImages([springImage, viteImage]);
        fs.writeFileSync(
            override,
            `services:\n  spring-1:\n    image: ${springImage}\n  spring-2:\n    image: ${springImage}\n  proxy:\n    image: ${viteImage}\n`,
            'utf8',
        );
        compose(files, project, ['build', 'spring-1'], { env });
        ownedImages.add(springImage);
        compose(files, project, ['build', 'proxy'], { env });
        ownedImages.add(viteImage);
        const config = JSON.parse(compose(files, project, ['config', '--format', 'json'], { env }).stdout);
        for (const service of ['spring-1', 'spring-2']) {
            assert(
                config.services[service].depends_on.postgres.condition === 'service_healthy',
                `${service} does not wait for PostgreSQL health`,
            );
            assert(
                config.services[service].depends_on.redis.condition === 'service_healthy',
                `${service} does not wait for Redis health`,
            );
        }
        for (const spring of ['spring-1', 'spring-2']) {
            assert(
                config.services.proxy.depends_on[spring].condition === 'service_healthy',
                `proxy does not wait for ${spring} health`,
            );
            assert(
                config.services[spring].networks.default.aliases.includes('spring'),
                `${spring} does not publish the shared spring DNS alias`,
            );
        }
        composeAttempted = true;
        compose(files, project, ['up', '-d', '--no-build', '--wait'], { env });
        for (const service of ['postgres', 'redis', 'spring-1', 'spring-2', 'proxy']) {
            const container = serviceContainer(files, project, service, env);
            assertHealthy(container, service);
            const bindings = dockerInspectJson(container).HostConfig.PortBindings;
            const expectsPublishedPort = ['postgres', 'redis', 'proxy'].includes(service);
            assert(
                expectsPublishedPort === (Object.keys(bindings).length > 0),
                `${service} host binding expectation is incorrect`,
            );
            for (const values of Object.values(bindings)) {
                for (const binding of values) assert(binding.HostIp === '127.0.0.1', `${service} binds ${binding.HostIp}`);
            }
        }
        const proxy = serviceContainer(files, project, 'proxy', env);
        const firstSpring = serviceContainer(files, project, 'spring-1', env);
        const firstSpringIp = Object.values(dockerInspectJson(firstSpring).NetworkSettings.Networks)[0].IPAddress;
        const initialApi = docker(['exec', proxy, 'wget', '-qO-', 'http://127.0.0.1/api/games?size=1']).stdout;
        assert(initialApi.includes('"status"'), 'local Compose /api request failed before Spring recreation');

        compose(files, project, ['rm', '--stop', '--force', 'spring-1'], { env });
        docker([
            'run',
            '-d',
            '--name',
            filler,
            '--network',
            `${project}_default`,
            httpEchoImage,
            '-listen=:8080',
            '-text=filler',
        ]);
        fillerCreated = true;
        const survivingApi = waitFor('local proxy DNS re-resolution after Spring removal', () => {
            const response = docker(['exec', proxy, 'wget', '-qO-', 'http://127.0.0.1/api/games?size=1'], {
                allowFailure: true,
            });
            return response.status === 0 && response.stdout.includes('"status"') ? response.stdout : false;
        });
        assert(survivingApi.includes('"status"'), 'local proxy did not route to the surviving Spring instance');

        compose(files, project, ['up', '-d', '--no-build', '--wait', 'spring-1'], { env });
        const recreatedSpring = serviceContainer(files, project, 'spring-1', env);
        assertHealthy(recreatedSpring, 'spring-1 after recreation');
        const recreatedSpringIp = Object.values(dockerInspectJson(recreatedSpring).NetworkSettings.Networks)[0].IPAddress;
        assert(firstSpringIp !== recreatedSpringIp, `Spring IP did not change: ${firstSpringIp}`);
        const restoredApi = waitFor('local proxy DNS re-resolution after Spring recreation', () => {
            const response = docker(['exec', proxy, 'wget', '-qO-', 'http://127.0.0.1/api/games?size=1'], {
                allowFailure: true,
            });
            return response.status === 0 && response.stdout.includes('"status"') ? response.stdout : false;
        });
        assert(restoredApi.includes('"status"'), 'local proxy did not route after Spring recreation');
        console.log(`T3 PASS: local proxy, two Spring instances, PostgreSQL and Redis are healthy with loopback-only host bindings and DNS recovery (${firstSpringIp} -> ${recreatedSpringIp}).`);
    } finally {
        if (fillerCreated) removeContainer(filler);
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
        const missingApp2 = { ...env };
        delete missingApp2.ALBAM_MATE_APP2_HOST;
        const missingApp2Result = docker(['compose', '-f', 'compose.production.yml', 'config', '--quiet'], {
            env: missingApp2,
            allowFailure: true,
        });
        assert(missingApp2Result.status !== 0, 'production Compose accepted a missing App2 host');
        assertProductionConfig(loadProductionConfig(env));
        buildOwnedImage(ownedImages, springImage, ['.']);
        buildOwnedImage(ownedImages, webImage, ['--file', 'frontend/Dockerfile.production', 'frontend']);
        assertReleaseGate(springImage, { SPRING_PROFILES_ACTIVE: 'production' });
        assertReleaseGate(webImage, { ALBAM_MATE_APP2_HOST: env.ALBAM_MATE_APP2_HOST });
        const missingApp2Entrypoint = docker(['run', '--rm', '--env', `ALBAM_MATE_RELEASE=${releaseSha}`, webImage], {
            allowFailure: true,
        });
        assert(missingApp2Entrypoint.status !== 0, 'web entrypoint accepted a missing App2 host');
        const app2WithPortEntrypoint = docker([
            'run',
            '--rm',
            '--env',
            `ALBAM_MATE_RELEASE=${releaseSha}`,
            '--env',
            'ALBAM_MATE_APP2_HOST=app-b.albam-mate.internal:8080',
            webImage,
        ], { allowFailure: true });
        assert(app2WithPortEntrypoint.status !== 0, 'web entrypoint accepted an App2 host with a port');
        for (const invalidApp2Host of [
            '127.0.0.1',
            'localhost',
            'app-b.albam-mate.internal bad',
            'app-b.albam-mate.internal$(id)',
            'app-b.albam-mate.internal;',
        ]) {
            const invalidHostEntrypoint = docker([
                'run',
                '--rm',
                '--env',
                `ALBAM_MATE_RELEASE=${releaseSha}`,
                '--env',
                `ALBAM_MATE_APP2_HOST=${invalidApp2Host}`,
                webImage,
            ], { allowFailure: true });
            assert(invalidHostEntrypoint.status !== 0, `web entrypoint accepted invalid App2 host: ${invalidApp2Host}`);
        }
        console.log('T4 PASS: ARM64 images retain the release gate and enforce App2 host, 512m Spring memory and JDK heap injection.');
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
    const app2 = contractResource('albam-mate-contract-t6-app2');
    const web = contractResource('albam-mate-contract-t6-web');
    const webImage = contractImage('albam-mate-web-production', 'T6');
    const ownedImages = new Set();
    let networkCreated = false;
    let springCreated = false;
    let app2Created = false;
    let webCreated = false;
    try {
        assertUnusedResources([spring, app2, web], network);
        createCertificate(certificateDirectory);
        assertProductionConfig(loadProductionConfig(productionEnvironment(certificateDirectory)));
        buildOwnedImage(ownedImages, webImage, ['--file', 'frontend/Dockerfile.production', 'frontend']);
        docker(['network', 'create', network]);
        networkCreated = true;
        docker(['run', '-d', '--name', spring, '--network', network, '--network-alias', 'spring', httpEchoImage, '-listen=:8080', '-text=production-proxy-app1']);
        springCreated = true;
        docker(['run', '-d', '--name', app2, '--network', network, '--network-alias', 'app-b.albam-mate.internal', httpEchoImage, '-listen=:8080', '-text=production-proxy-app2']);
        app2Created = true;
        docker([
            'run',
            '-d',
            '--name',
            web,
            '--env',
            `ALBAM_MATE_RELEASE=${releaseSha}`,
            '--env',
            'ALBAM_MATE_APP2_HOST=app-b.albam-mate.internal',
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
        const observedBodies = new Set();
        const observedUpstreams = new Set();
        for (let attempt = 0; attempt < 12; attempt += 1) {
            const response = docker([
                'exec',
                web,
                'wget',
                '--no-check-certificate',
                '-S',
                '-qO-',
                'https://127.0.0.1:8443/api/verify',
            ]);
            const body = response.stdout.trim();
            const upstream = upstreamHeader(response);
            assert(
                body === 'production-proxy-app1' || body === 'production-proxy-app2',
                `unexpected production upstream response: ${body}`,
            );
            observedBodies.add(body);
            observedUpstreams.add(upstream);
        }
        assert(observedBodies.has('production-proxy-app1'), 'repeated requests never observed App1');
        assert(observedBodies.has('production-proxy-app2'), 'repeated requests never observed App2');
        assert(observedUpstreams.size === 2, `repeated requests observed ${observedUpstreams.size} upstream addresses`);
        verifyDatabaseHealthcheck();
        console.log('T6 PASS: repeated HTTPS requests observed both App1/App2 bodies and upstream headers while both backends stayed healthy; PostgreSQL also reached healthy.');
    } finally {
        if (webCreated) removeContainer(web);
        if (app2Created) removeContainer(app2);
        if (springCreated) removeContainer(spring);
        if (networkCreated) removeNetwork(network);
        removeOwnedImages(ownedImages);
        removeOwnedTempDirectory(certificateDirectory, 'albam-mate-contract-t6');
    }
}

function upstreamHeader(response) {
    const match = response.stderr.match(/^\s*X-Albam-Mate-Upstream:\s*(.+)$/imu);
    assert(match, `response omitted X-Albam-Mate-Upstream: ${response.stderr}`);
    return match[1].trim();
}

function verifyDatabaseHealthcheck() {
    const project = contractProject('T6db');
    const directory = createTempDirectory('albam-mate-contract-t6-db');
    const override = path.join(directory, 'compose.override.yml');
    const files = ['compose.db.yml', override];
    const env = {
        ...process.env,
        ALBAM_MATE_DB_NAME: 'albam_mate_verify',
        ALBAM_MATE_DB_USER: 'verify_user',
        ALBAM_MATE_DB_PASSWORD: 'verify_password',
    };
    let composeAttempted = false;
    try {
        assertProjectUnused(project);
        fs.writeFileSync(
            override,
            'services:\n  postgres:\n    ports: !override []\n    volumes:\n      - postgres_data:/var/lib/postgresql\nvolumes:\n  postgres_data:\n',
            'utf8',
        );
        const config = JSON.parse(compose(files, project, ['config', '--format', 'json'], { env }).stdout);
        const renderedHealthcheck = config.services.postgres.healthcheck.test.at(-1);
        assert(/\$\$?POSTGRES_USER/u.test(renderedHealthcheck), `rendered healthcheck lost POSTGRES_USER: ${renderedHealthcheck}`);
        assert(/\$\$?POSTGRES_DB/u.test(renderedHealthcheck), `rendered healthcheck lost POSTGRES_DB: ${renderedHealthcheck}`);
        assert(!renderedHealthcheck.includes('""'), `rendered healthcheck contains an empty database input: ${renderedHealthcheck}`);
        composeAttempted = true;
        compose(files, project, ['up', '-d', '--wait'], { env });
        const postgres = serviceContainer(files, project, 'postgres', env);
        assertHealthy(postgres, 'PostgreSQL');
        const healthcheck = dockerInspectJson(postgres).Config.Healthcheck.Test.at(-1);
        assert(/\$POSTGRES_USER/u.test(healthcheck), `container healthcheck lost POSTGRES_USER: ${healthcheck}`);
        assert(/\$POSTGRES_DB/u.test(healthcheck), `container healthcheck lost POSTGRES_DB: ${healthcheck}`);
        assert(!healthcheck.includes('""'), `container healthcheck contains an empty database input: ${healthcheck}`);
    } finally {
        if (composeAttempted) cleanupProject(files, project, env);
        removeOwnedTempDirectory(directory, 'albam-mate-contract-t6-db');
    }
}

function verifyT7() {
    const certificateDirectory = createTempDirectory('albam-mate-contract-t7');
    const performanceEnvironment = {
        APP_SECURITY_AUTHREQUEST_LOGINLIMIT: '20000',
        APP_SECURITY_AUTHREQUEST_LOGINFAILURELIMIT: '20000',
        APP_SECURITY_AUTHREQUEST_SIGNUPLIMIT: '5',
        APP_SECURITY_AUTHREQUEST_HASHSLOTS: '4',
        APP_SECURITY_PASSWORD_BCRYPTCOST: '10',
        APP_NOTIFICATION_RELAY_POLLINTERVAL: '5s',
        APP_NOTIFICATION_RELAY_MAXEVENTSPERRUN: '50',
        MANAGEMENT_SERVER_PORT: '9090',
        MANAGEMENT_SERVER_ADDRESS: '127.0.0.1',
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: 'health,metrics',
    };
    const env = { ...productionEnvironment(certificateDirectory), ...performanceEnvironment };
    try {
        docker(['compose', '-f', 'compose.production.yml', 'config', '--quiet'], { env });
        docker(['compose', '-f', 'compose.app2.yml', 'config', '--quiet'], { env });
        docker(['compose', '-f', 'compose.db.yml', 'config', '--quiet'], { env });
        const app2Config = loadComposeConfig('compose.app2.yml', env);
        const app1Config = loadProductionConfig(env);
        for (const [name, value] of Object.entries(performanceEnvironment)) {
            assert(app1Config.services.spring.environment[name] === value, `App1 Spring does not receive ${name}`);
            assert(app2Config.services.spring.environment[name] === value, `App2 Spring does not receive ${name}`);
        }
        assert(
            String(app2Config.services.spring.mem_limit) === String(512 * 1024 * 1024) || app2Config.services.spring.mem_limit === '512m',
            `App2 Spring memory limit is ${app2Config.services.spring.mem_limit}`,
        );
        assert(
            app2Config.services.spring.environment.JDK_JAVA_OPTIONS === '-Xmx256m',
            'App2 Spring does not receive the P1 heap limit',
        );
        if (process.platform === 'win32') {
            run('cmd.exe', [
                '/d',
                '/s',
                '/c',
                'gradlew.bat test --tests cloud.bamsongi.albammate.global.config.ProductionProfileConfigurationTest --rerun --fail-fast',
            ]);
        } else {
            run('./gradlew', [
                'test',
                '--tests',
                'cloud.bamsongi.albammate.global.config.ProductionProfileConfigurationTest',
                '--rerun',
                '--fail-fast',
            ]);
        }
        console.log('T7 PASS: production profile regression and App1/App2/PostgreSQL role Compose configs pass without an RDS CA mount.');
    } finally {
        removeOwnedTempDirectory(certificateDirectory, 'albam-mate-contract-t7');
    }
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
  redis:
    image: redis:8.4-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 2s
      timeout: 5s
      retries: 20
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
      ALBAM_MATE_LOCAL_REDIS_HOST: redis
      ALBAM_MATE_LOCAL_REDIS_PORT: 6379
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    networks:
      application:
        aliases:
          - app-b.albam-mate.internal
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
        fs.writeFileSync(override, t8Override(springImage, webImage), 'utf8');
        buildOwnedImage(ownedImages, springImage, ['.']);
        buildOwnedImage(ownedImages, webImage, ['--file', 'frontend/Dockerfile.production', 'frontend']);
        const env = productionEnvironment(certificateDirectory);
        assertProductionConfig(loadProductionConfig(env));
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
        run('node', ['scripts/check-doc-links.mjs']);
        console.log('T8 PASS: production Compose up --wait reports PostgreSQL fixture, Spring and web healthy, serves HTTPS API and has valid guide links.');
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
