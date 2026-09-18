#!/usr/bin/env bash
# Invoked by the production self-hosted runner after GitHub-hosted Actions has
# built and pushed the three application images to Tencent TCR.

set -Eeuo pipefail

readonly DEPLOY_ENV_FILE="${RAGENT_DEPLOY_ENV_FILE:-/home/ubuntu/ragent-secrets/ragent.env}"
readonly COMPOSE_FILE="${RAGENT_COMPOSE_FILE:-deploy/compose.yaml}"
readonly LOCK_FILE="${RAGENT_DEPLOY_LOCK_FILE:-/tmp/hnu-ragent-deploy.lock}"

for required_variable in RAGENT_BACKEND_IMAGE RAGENT_MCP_IMAGE RAGENT_FRONTEND_IMAGE; do
  if [[ -z "${!required_variable:-}" ]]; then
    echo "Required deployment image variable is empty: ${required_variable}" >&2
    exit 1
  fi
done

if ! command -v docker >/dev/null 2>&1; then
  echo 'Docker is not installed or is not available to the runner user.' >&2
  exit 1
fi

if [[ ! -r "$DEPLOY_ENV_FILE" ]]; then
  echo "Production environment file is missing or unreadable: $DEPLOY_ENV_FILE" >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Compose file was not found: $COMPOSE_FILE" >&2
  exit 1
fi

# Prevent two deliveries from replacing the same containers concurrently.
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo 'Another HNU RAgent deployment is already running.' >&2
  exit 1
fi

compose() {
  docker compose --env-file "$DEPLOY_ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

echo "Deploying immutable application images for commit ${GITHUB_SHA:-unknown}"
printf '%s\n' "  backend:    $RAGENT_BACKEND_IMAGE"
printf '%s\n' "  mcp-server: $RAGENT_MCP_IMAGE"
printf '%s\n' "  frontend:   $RAGENT_FRONTEND_IMAGE"

# Validate interpolation before changing any running container. Middleware is
# deliberately excluded: its stateful Docker volumes stay online.
compose config --quiet
compose pull backend mcp-server frontend
compose up -d --no-deps --force-recreate backend mcp-server frontend

sleep 10
for service in backend mcp-server frontend; do
  if ! compose ps --status running --services | grep -Fxq "$service"; then
    echo "Service did not remain running after deployment: $service" >&2
    compose logs --tail=120 "$service" >&2 || true
    exit 1
  fi
done

compose ps
echo 'Registry deployment completed successfully.'
