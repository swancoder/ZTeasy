#!/usr/bin/env bash
# Run from the root of the ZTeasy repository:
#   bash scripts/install-hooks.sh

set -e

HOOKS_DIR="$(git rev-parse --git-dir)/hooks"
GITHOOKS_DIR="$(git rev-parse --show-toplevel)/.githooks"

echo "Installing ZTeasy git hooks..."

cp "$GITHOOKS_DIR/pre-commit" "$HOOKS_DIR/pre-commit"
chmod +x "$HOOKS_DIR/pre-commit"
echo "✅ pre-commit hook installed"

echo ""
echo "Done. The hook runs automatically on every commit."
echo "To skip once: git commit --no-verify"
