#!/usr/bin/env sh
set -eu
CLAWKIT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$CLAWKIT_DIRECTORY/clawkit.jar" ]; then
  printf '%s\n' 'clawkit.jar is missing. Use the extracted package or java -jar on the built CLI JAR.' >&2
  exit 3
fi
exec java -Dfile.encoding=UTF-8 -jar "$CLAWKIT_DIRECTORY/clawkit.jar" "$@"
