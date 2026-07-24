#!/usr/bin/env bash
# Linux/macOS: fat JAR 통합 빌드 (jpackage exe는 Windows에서 scripts/build-windows.ps1 사용)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/backend"
./gradlew clean bootJar
echo ""
echo "JAR: $ROOT/backend/build/libs/"
ls -la "$ROOT/backend/build/libs/"*.jar
echo ""
echo "실행 예:"
echo "  java -jar build/libs/evidence-auto-backend-*-SNAPSHOT.jar --spring.profiles.active=desktop"
