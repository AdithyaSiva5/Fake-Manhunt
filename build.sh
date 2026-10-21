#!/usr/bin/env bash
# Builds FakeManhunt.jar against the Paper libraries of an existing server.
# Usage: ./build.sh /path/to/paper/server   (run the server once first so libraries/ exists)
set -euo pipefail
cd "$(dirname "$0")"
server="${1:?Usage: ./build.sh /path/to/paper/server}"
cp=$(find "$server/libraries" -name '*.jar' | tr '\n' ':')
rm -rf build && mkdir -p build
javac --release 25 -cp "$cp" -d build src/fakemanhunt/*.java
cp src/plugin.yml src/config.yml build/
jar cf FakeManhunt.jar -C build .
echo "Built FakeManhunt.jar"
