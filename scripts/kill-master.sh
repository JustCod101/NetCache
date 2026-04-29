#!/usr/bin/env bash
set -euo pipefail

SERVICE_NAME="${1:-master-1}"

docker compose stop "$SERVICE_NAME"
echo "Stopped $SERVICE_NAME. Re-run the sample client or failover scenario to validate recovery."
