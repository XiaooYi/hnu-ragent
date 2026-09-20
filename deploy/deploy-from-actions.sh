#!/usr/bin/env bash
# Invoked by the production self-hosted runner after GitHub-hosted Actions has
# built and pushed the three application images to Tencent TCR.

set -Eeuo pipefail

readonly DEPLOY_ENV_FILE="${RAGENT_DEPLOY_ENV_FILE:-/home/ubuntu/ragent-secrets/ragent.env}"
readonly COMPOSE_FILE="${RAGENT_COMPOSE_FILE:-deploy/compose.yaml}"
readonly LOCK_FILE="${RAGENT_DEPLOY_LOCK_FILE:-/tmp/hnu-ragent-deploy.lock}"
readonly LAST_SUCCESSFUL_RELEASE_FILE="${RAGENT_LAST_SUCCESSFUL_RELEASE_FILE:-/home/ubuntu/ragent-deploy/last-successful-release.env}"
readonly APPLICATION_SERVICES=(backend mcp-server frontend)
readonly TARGET_SERVICES_RAW="${RAGENT_TARGET_SERVICES:-backend,mcp-server,frontend}"

IFS=',' read -r -a TARGET_SERVICES <<< "$TARGET_SERVICES_RAW"
if [[ ${#TARGET_SERVICES[@]} -eq 0 ]]; then
  echo 'At least one application service must be selected for deployment.' >&2
  exit 1
fi

for target_service in "${TARGET_SERVICES[@]}"; do
  case "$target_service" in
    backend|mcp-server|frontend) ;;
    *)
      echo "Unsupported application service requested for deployment: $target_service" >&2
      exit 1
      ;;
  esac
done

if [[ -r "$LAST_SUCCESSFUL_RELEASE_FILE" ]]; then
  readonly REQUESTED_BACKEND_IMAGE="${RAGENT_BACKEND_IMAGE:-}"
  readonly REQUESTED_MCP_SERVER_IMAGE="${RAGENT_MCP_SERVER_IMAGE:-}"
  readonly REQUESTED_FRONTEND_IMAGE="${RAGENT_FRONTEND_IMAGE:-}"

  # The release record is generated with shell-escaped image references by
  # save_release and has runner-only permissions. It supplies unchanged
  # services so Compose can still render a complete application definition.
  # shellcheck disable=SC1090
  source "$LAST_SUCCESSFUL_RELEASE_FILE"

  for target_service in "${TARGET_SERVICES[@]}"; do
    case "$target_service" in
      backend) RAGENT_BACKEND_IMAGE="$REQUESTED_BACKEND_IMAGE" ;;
      mcp-server) RAGENT_MCP_SERVER_IMAGE="$REQUESTED_MCP_SERVER_IMAGE" ;;
      frontend) RAGENT_FRONTEND_IMAGE="$REQUESTED_FRONTEND_IMAGE" ;;
    esac
  done
fi

for required_variable in RAGENT_BACKEND_IMAGE RAGENT_MCP_SERVER_IMAGE RAGENT_FRONTEND_IMAGE; do
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

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo 'Another HNU RAgent deployment is already running.' >&2
  exit 1
fi

compose() {
  docker compose --env-file "$DEPLOY_ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

image_digest() {
  docker image inspect --format '{{index .RepoDigests 0}}' "$1"
}

save_release() {
  local release_directory temporary_file
  release_directory="$(dirname "$LAST_SUCCESSFUL_RELEASE_FILE")"
  mkdir -p "$release_directory"
  temporary_file="$(mktemp "${LAST_SUCCESSFUL_RELEASE_FILE}.tmp.XXXXXX")"
  chmod 600 "$temporary_file"
  {
    printf 'RAGENT_BACKEND_IMAGE=%q\n' "$(image_digest "$RAGENT_BACKEND_IMAGE")"
    printf 'RAGENT_MCP_SERVER_IMAGE=%q\n' "$(image_digest "$RAGENT_MCP_SERVER_IMAGE")"
    printf 'RAGENT_FRONTEND_IMAGE=%q\n' "$(image_digest "$RAGENT_FRONTEND_IMAGE")"
  } > "$temporary_file"
  mv -f "$temporary_file" "$LAST_SUCCESSFUL_RELEASE_FILE"
}

rollback() {
  local failed_exit_code="$1"
  trap - ERR
  if [[ ! -r "$LAST_SUCCESSFUL_RELEASE_FILE" ]]; then
    echo "Deployment failed; no prior successful release record exists at $LAST_SUCCESSFUL_RELEASE_FILE." >&2
    exit "$failed_exit_code"
  fi

  echo 'Deployment failed; rolling back application containers to the last successful image digests.' >&2
  # This file is created with shell-escaped values by save_release above and is
  # writable only by the deployment runner.
  # shellcheck disable=SC1090
  source "$LAST_SUCCESSFUL_RELEASE_FILE"
  compose pull "${TARGET_SERVICES[@]}"
  compose up -d --no-deps --force-recreate "${TARGET_SERVICES[@]}"
  exit "$failed_exit_code"
}

trap 'rollback "$?"' ERR

echo "Deploying immutable application images for commit ${GITHUB_SHA:-unknown}"
printf '%s\n' "  backend:    $RAGENT_BACKEND_IMAGE"
printf '%s\n' "  mcp-server: $RAGENT_MCP_SERVER_IMAGE"
printf '%s\n' "  frontend:   $RAGENT_FRONTEND_IMAGE"

# Validate interpolation before replacing any running container. Middleware and
# persistent volumes are deliberately excluded from every release operation.
compose config --quiet
compose pull "${TARGET_SERVICES[@]}"
compose up -d --no-deps --force-recreate "${TARGET_SERVICES[@]}"

sleep 10
for service in "${TARGET_SERVICES[@]}"; do
  if ! compose ps --status running --services | grep -Fxq "$service"; then
    echo "Service did not remain running after deployment: $service" >&2
    compose logs --tail=120 "$service" >&2 || true
    exit 1
  fi
done

save_release
compose ps
echo 'Registry deployment completed successfully.'
