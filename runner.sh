#!/usr/bin/env bash
set -e

# ─────────────────────────────────────────────
# Config
# ─────────────────────────────────────────────
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$PROJECT_ROOT/src"
BUILD_DIR="$PROJECT_ROOT/build"
MAIN_CLASS="Main"

# ─────────────────────────────────────────────
# Clean
# ─────────────────────────────────────────────
echo "🧹 Cleaning build directory..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# ─────────────────────────────────────────────
# Compile
# ─────────────────────────────────────────────
echo "☕ Compiling Java sources..."
javac \
  -encoding UTF-8 \
  -d "$BUILD_DIR" \
  $(find "$SRC_DIR" -name "*.java")

# ─────────────────────────────────────────────
# Run
# ─────────────────────────────────────────────
echo "🚀 Starting LocalServer..."
cd "$PROJECT_ROOT"
java -cp "$BUILD_DIR" "$MAIN_CLASS"
