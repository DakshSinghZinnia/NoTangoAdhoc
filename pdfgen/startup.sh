#!/bin/sh
set -e

# Load env vars if a secrets file is mounted
if [ -f /mnt/secrets-store/app.env ]; then
  set -a
  . /mnt/secrets-store/app.env
  set +a
fi

# Optional: quick diagnostics in container logs
soffice --version || true
fc-match "Poppins" || true

exec java -javaagent:/app/dd-java-agent.jar -XX:MaxRAMPercentage=70.0 -jar /app/app.jar