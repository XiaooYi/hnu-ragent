import { appendFile } from 'node:fs/promises';

const APPLICATION_SERVICES = ['backend', 'mcp-server', 'frontend'];

const serviceDefinitions = {
  backend: { dockerfile: 'deploy/backend.Dockerfile' },
  'mcp-server': { dockerfile: 'deploy/mcp-server.Dockerfile' },
  frontend: { dockerfile: 'deploy/frontend.Dockerfile' },
};

function addAllServices(selectedServices) {
  for (const service of APPLICATION_SERVICES) {
    selectedServices.add(service);
  }
}

function isIgnoredPath(changedPath) {
  return (
    changedPath === 'README.md' ||
    changedPath.startsWith('docs/') ||
    changedPath.startsWith('.agents/') ||
    changedPath.startsWith('.specify/') ||
    changedPath.startsWith('scripts/')
  );
}

/**
 * Returns the application services whose image and container must be replaced
 * for the supplied repository-relative changed paths. Unknown runtime-relevant
 * paths are deliberately treated as a full release rather than skipped.
 */
export function classifyChangedPaths(changedPaths) {
  const selectedServices = new Set();

  for (const rawPath of changedPaths) {
    const changedPath = rawPath.replaceAll('\\', '/').replace(/^\.\//, '');
    if (!changedPath) {
      continue;
    }

    if (changedPath === 'scripts/detect-affected-services.mjs') {
      addAllServices(selectedServices);
      continue;
    }

    if (isIgnoredPath(changedPath)) {
      continue;
    }

    if (changedPath.startsWith('frontend/') || changedPath === 'deploy/frontend.Dockerfile' || changedPath.startsWith('deploy/nginx/')) {
      selectedServices.add('frontend');
      continue;
    }

    if (changedPath.startsWith('bootstrap/') || changedPath === 'deploy/backend.Dockerfile') {
      selectedServices.add('backend');
      continue;
    }

    if (changedPath.startsWith('mcp-server/') || changedPath === 'deploy/mcp-server.Dockerfile') {
      selectedServices.add('mcp-server');
      continue;
    }

    if (
      changedPath.startsWith('framework/') ||
      changedPath.startsWith('infra-ai/') ||
      changedPath === 'pom.xml' ||
      changedPath === 'mvnw' ||
      changedPath === 'mvnw.cmd' ||
      changedPath.startsWith('.mvn/')
    ) {
      selectedServices.add('backend');
      selectedServices.add('mcp-server');
      continue;
    }

    if (
      changedPath === '.github/workflows/deploy-production.yml' ||
      changedPath === 'deploy/compose.yaml' ||
      changedPath === 'deploy/deploy-from-actions.sh' ||
      changedPath.startsWith('deploy/rocketmq/') ||
      changedPath.startsWith('resources/database/')
    ) {
      addAllServices(selectedServices);
      continue;
    }

    addAllServices(selectedServices);
  }

  return APPLICATION_SERVICES.filter((service) => selectedServices.has(service));
}

export function buildMatrix(services) {
  return {
    include: services.map((service) => ({
      service,
      dockerfile: serviceDefinitions[service].dockerfile,
    })),
  };
}

async function runCli() {
  const changedPaths = (await new Response(process.stdin).text()).split(/\r?\n/);
  const services = classifyChangedPaths(changedPaths);
  const output = [
    `services=${JSON.stringify(services)}`,
    `matrix=${JSON.stringify(buildMatrix(services))}`,
    `has_changes=${services.length > 0}`,
  ].join('\n');
  const outputFileIndex = process.argv.indexOf('--github-output');

  if (outputFileIndex >= 0) {
    const outputFile = process.argv[outputFileIndex + 1];
    if (!outputFile) {
      throw new Error('--github-output requires a file path');
    }
    await appendFile(outputFile, `${output}\n`);
    return;
  }

  process.stdout.write(`${output}\n`);
}

if (process.argv[1]?.endsWith('detect-affected-services.mjs')) {
  await runCli();
}
