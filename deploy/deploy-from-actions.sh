#!/usr/bin/env bash
# Invoked only by the GitHub Actions self-hosted runner on the production CVM.
# The runner checks out the exact pushed commit into its own workspace; do not
# run git pull or git reset against the legacy /home/ubuntu/ragent directory.

set -Eeuo pipefail

readonly DEPLOY_ENV_FILE="${RAGENT_DEPLOY_ENV_FILE:-/home/ubuntu/ragent-secrets/ragent.env}"
readonly COMPOSE_FILE="${RAGENT_COMPOSE_FILE:-deploy/compose.yaml}"
readonly LOCK_FILE="${RAGENT_DEPLOY_LOCK_FILE:-/tmp/hnu-ragent-deploy.lock}"

if ! command -v docker >/dev/null 2>&1; then
  echo 'Docker is not installed or is not available to the runner user.' >&2
  exit 1
fi

if [[ ! -r "$DEPLOY_ENV_FILE" ]]; then
  echo "Production environment file is missing or unreadable: $DEPLOY_ENV_FILE" >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Compose file was not found in the checked-out commit: $COMPOSE_FILE" >&2
  exit 1
fi

# Prevent a manual deployment and a GitHub deployment from operating on the
# same Docker project simultaneously.
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo 'Another HNU RAgent deployment is already running.' >&2
  exit 1
fi

compose() {
  docker compose --env-file "$DEPLOY_ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

echo "Deploying commit ${GITHUB_SHA:-unknown} from ${GITHUB_REPOSITORY:-local checkout}"

# Build sequentially: backend and mcp-server both compile Maven modules, and
# concurrent builds are slower and less stable on the 2-core production CVM.
# BuildKit is required for the persistent Maven and npm cache mounts declared
# in the application Dockerfiles.
export DOCKER_BUILDKIT=1
compose build backend
compose build mcp-server
compose build frontend

# Source-code releases only replace application containers. Stateful middleware
# and its Docker volumes remain online and are never removed by this workflow.
compose up -d --no-deps --force-recreate backend mcp-server frontend

# Let JVM applications complete their startup before declaring the deployment
# successful. Print relevant logs when any application service is not running.
sleep 10
for service in backend mcp-server frontend; do
  if ! compose ps --status running --services | grep -Fxq "$service"; then
    echo "Service did not remain running after deployment: $service" >&2
    compose logs --tail=120 "$service" >&2 || true
    exit 1
  fi
done

compose ps
echo 'Deployment completed successfully.'
