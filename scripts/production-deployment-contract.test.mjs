import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { chmod, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const execFile = promisify(execFileCallback);
const bashPath = 'C:/Program Files/Git/bin/bash.exe';

async function classifyChangedPaths(changedPaths) {
  try {
    const { classifyChangedPaths: classify } = await import('./detect-affected-services.mjs');
    return classify(changedPaths);
  } catch (error) {
    assert.fail(`affected-service detector must be available: ${error.message}`);
  }
}

async function buildMatrix(services) {
  try {
    const { buildMatrix: build } = await import('./detect-affected-services.mjs');
    assert.equal(typeof build, 'function', 'affected-service detector must provide a build matrix');
    return build(services);
  } catch (error) {
    assert.fail(`affected-service matrix builder must be available: ${error.message}`);
  }
}

async function readRepositoryFile(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), 'utf8');
}

const IMAGE_VARIABLE_BY_SERVICE = {
  backend: 'RAGENT_BACKEND_IMAGE',
  'mcp-server': 'RAGENT_MCP_SERVER_IMAGE',
  frontend: 'RAGENT_FRONTEND_IMAGE',
};

const RELEASE_RECORD = [
  'RAGENT_BACKEND_IMAGE=ccr.ccs.tencentyun.com/hnu-ragent/ragent-backend@sha256:oldbackend',
  'RAGENT_MCP_SERVER_IMAGE=ccr.ccs.tencentyun.com/hnu-ragent/ragent-mcp-server@sha256:oldmcp',
  'RAGENT_FRONTEND_IMAGE=ccr.ccs.tencentyun.com/hnu-ragent/ragent-frontend@sha256:oldfrontend',
  '',
].join('\n');

// Mirrors the workflow, which exports an image variable only for the services
// selected for this release. Exporting the omitted ones here would hide the
// very bug these tests exist to catch.
function workflowImageVariables(targetServices) {
  const variables = {};
  for (const rawService of targetServices.split(',')) {
    const service = rawService.trim();
    const variable = IMAGE_VARIABLE_BY_SERVICE[service];
    if (variable) {
      variables[variable] = `ccr.ccs.tencentyun.com/hnu-ragent/ragent-${service}:test`;
    }
  }
  return variables;
}

async function runSelectiveDeployment(targetServices, { lastSuccessfulRelease } = {}) {
  const fixtureDirectory = await mkdtemp(path.join(tmpdir(), 'ragent-selective-deploy-'));
  const dockerLogPath = path.join(fixtureDirectory, 'docker.log');
  const dockerPath = path.join(fixtureDirectory, 'docker');
  const flockPath = path.join(fixtureDirectory, 'flock');
  const sleepPath = path.join(fixtureDirectory, 'sleep');
  const envPath = path.join(fixtureDirectory, 'ragent.env');
  const composePath = path.join(fixtureDirectory, 'compose.yaml');

  await writeFile(envPath, 'COMPOSE_PROJECT_NAME=ragent\n');
  await writeFile(composePath, 'services: {}\n');
  const releasePath = path.join(fixtureDirectory, 'last-successful-release.env');
  if (lastSuccessfulRelease) {
    await writeFile(releasePath, lastSuccessfulRelease);
  }
  await writeFile(
    dockerPath,
    `#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
printf 'RAGENT_BACKEND_IMAGE=%s\\n' "\${RAGENT_BACKEND_IMAGE:-<unset>}" >> "$FAKE_DOCKER_LOG"
printf 'RAGENT_MCP_SERVER_IMAGE=%s\\n' "\${RAGENT_MCP_SERVER_IMAGE:-<unset>}" >> "$FAKE_DOCKER_LOG"
printf 'RAGENT_FRONTEND_IMAGE=%s\\n' "\${RAGENT_FRONTEND_IMAGE:-<unset>}" >> "$FAKE_DOCKER_LOG"
if [[ "$1" == image ]]; then
  printf 'ccr.ccs.tencentyun.com/hnu-ragent/test@sha256:fixture\\n'
  exit 0
fi
if [[ "$1" == compose && " $* " == *" ps "* ]]; then
  printf 'frontend\\n'
fi
`,
  );
  await chmod(dockerPath, 0o700);
  await writeFile(flockPath, '#!/usr/bin/env bash\nexit 0\n');
  await chmod(flockPath, 0o700);
  await writeFile(sleepPath, '#!/usr/bin/env bash\nexit 0\n');
  await chmod(sleepPath, 0o700);

  const environment = {
    ...process.env,
    PATH: `${fixtureDirectory};${process.env.PATH}`,
    FAKE_DOCKER_LOG: dockerLogPath,
    RAGENT_TARGET_SERVICES: targetServices,
    RAGENT_DEPLOY_ENV_FILE: envPath,
    RAGENT_COMPOSE_FILE: composePath,
    RAGENT_DEPLOY_LOCK_FILE: path.join(fixtureDirectory, 'deploy.lock'),
    RAGENT_LAST_SUCCESSFUL_RELEASE_FILE: releasePath,
    ...workflowImageVariables(targetServices),
  };

  try {
    await execFile(bashPath, ['deploy/deploy-from-actions.sh'], {
      cwd: repositoryRoot,
      env: environment,
    });
    return readFile(dockerLogPath, 'utf8');
  } finally {
    await rm(fixtureDirectory, { recursive: true, force: true });
  }
}

test('production deployment publishes immutable TCR images without a Gitee source mirror', async () => {
  const workflow = await readRepositoryFile('.github/workflows/deploy-production.yml');

  assert.match(workflow, /ccr\.ccs\.tencentyun\.com\/hnu-ragent\/ragent-backend/);
  assert.match(workflow, /TCR_USERNAME/);
  assert.match(workflow, /TCR_PASSWORD/);
  assert.match(workflow, /docker\/build-push-action/);
  assert.doesNotMatch(workflow, /GITEE_|gitee\.com|sync-source-mirror/i);
});

test('Compose consumes externally built application images instead of build contexts', async () => {
  const compose = await readRepositoryFile('deploy/compose.yaml');

  for (const service of ['backend', 'mcp-server', 'frontend']) {
    const serviceStart = compose.indexOf(`  ${service}:`);
    const nextService = compose.indexOf('\n  ', serviceStart + 1);
    const serviceDefinition = compose.slice(serviceStart, nextService === -1 ? undefined : nextService);

    assert.ok(serviceStart >= 0, `expected ${service} service to exist`);
    assert.doesNotMatch(serviceDefinition, /\n    build:/);
  }

  assert.match(compose, /image: \$\{RAGENT_BACKEND_IMAGE\}/);
  assert.match(compose, /image: \$\{RAGENT_MCP_SERVER_IMAGE\}/);
  assert.match(compose, /image: \$\{RAGENT_FRONTEND_IMAGE\}/);
});

test('CVM deployment pulls prebuilt images and retains a rollback release record', async () => {
  const deploymentScript = await readRepositoryFile('deploy/deploy-from-actions.sh');

  assert.match(deploymentScript, /compose pull/);
  assert.doesNotMatch(deploymentScript, /compose build/);
  assert.match(deploymentScript, /LAST_SUCCESSFUL_RELEASE_FILE/);
  assert.match(deploymentScript, /rollback/);
});

test('frontend-only releases pull and recreate only the frontend container', async () => {
  const dockerLog = await runSelectiveDeployment('frontend', {
    lastSuccessfulRelease: RELEASE_RECORD,
  });

  assert.match(dockerLog, /pull frontend/);
  assert.match(dockerLog, /up -d --no-deps --force-recreate frontend/);
  assert.doesNotMatch(dockerLog, /pull backend|pull mcp-server/);
  assert.doesNotMatch(dockerLog, /force-recreate backend|force-recreate mcp-server/);
});

test('frontend-only releases reuse the last successful digests for unchanged services', async () => {
  const dockerLog = await runSelectiveDeployment('frontend', {
    lastSuccessfulRelease: RELEASE_RECORD,
  });

  assert.match(dockerLog, /pull frontend/);
  assert.doesNotMatch(dockerLog, /pull backend|pull mcp-server/);

  // Compose runs as a child process, so the images of services left out of this
  // release must reach it through the environment, not just the shell.
  assert.match(dockerLog, /RAGENT_BACKEND_IMAGE=ccr\.ccs\.tencentyun\.com\/hnu-ragent\/ragent-backend@sha256:oldbackend/);
  assert.match(dockerLog, /RAGENT_MCP_SERVER_IMAGE=ccr\.ccs\.tencentyun\.com\/hnu-ragent\/ragent-mcp-server@sha256:oldmcp/);
});

test('changed paths select only the application services that must be rebuilt and deployed', async () => {
  assert.deepEqual(await classifyChangedPaths(['frontend/src/pages/Home.tsx']), ['frontend']);
  assert.deepEqual(await classifyChangedPaths(['bootstrap/src/main/java/App.java']), ['backend']);
  assert.deepEqual(await classifyChangedPaths(['mcp-server/src/main/java/McpServer.java']), ['mcp-server']);
  assert.deepEqual(
    await classifyChangedPaths(['framework/src/main/java/SharedConfiguration.java']),
    ['backend', 'mcp-server'],
  );
  assert.deepEqual(await classifyChangedPaths(['README.md', 'docs/architecture.md']), []);
  assert.deepEqual(
    await classifyChangedPaths(['scripts/detect-affected-services.mjs']),
    ['backend', 'mcp-server', 'frontend'],
  );
  assert.deepEqual(
    await classifyChangedPaths(['deploy/compose.yaml']),
    ['backend', 'mcp-server', 'frontend'],
  );
});

test('build matrix contains only the selected service Dockerfiles', async () => {
  assert.deepEqual(await buildMatrix(['frontend']), {
    include: [{ service: 'frontend', dockerfile: 'deploy/frontend.Dockerfile' }],
  });
  assert.deepEqual(await buildMatrix(['backend', 'mcp-server']), {
    include: [
      { service: 'backend', dockerfile: 'deploy/backend.Dockerfile' },
      { service: 'mcp-server', dockerfile: 'deploy/mcp-server.Dockerfile' },
    ],
  });
});
