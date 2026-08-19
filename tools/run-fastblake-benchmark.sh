#!/bin/sh
set -eu

# Portable launcher for the benchmark bundle. The bundle deliberately does
# not ship a JDK; pass the JDK to use explicitly with --jdk, or set JAVA_HOME.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JDK_DIR=${JAVA_HOME:-}

case "${1:-}" in
    --jdk)
        [ "$#" -ge 2 ] || { echo "error: --jdk requires a JDK directory" >&2; exit 2; }
        JDK_DIR=$2
        shift 2
        ;;
    --jdk=*)
        JDK_DIR=${1#--jdk=}
        shift
        ;;
esac

if [ -z "$JDK_DIR" ]; then
    echo "error: provide --jdk /path/to/jdk (or set JAVA_HOME)" >&2
    exit 2
fi

JAVA_BIN=$JDK_DIR/bin/java
if [ ! -x "$JAVA_BIN" ]; then
    echo "error: '$JAVA_BIN' is not an executable Java launcher" >&2
    exit 2
fi

exec "$JAVA_BIN" \
    --add-modules jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    -jar "$SCRIPT_DIR/fastblake-test.jar" "$@"
