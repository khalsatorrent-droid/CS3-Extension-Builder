#!/usr/bin/env bash
# release-notes.sh — extracts release notes from CHANGELOG for the current version
#                    and appends recent git log
# Usage: ./scripts/release-notes.sh <version>
# Output: prints release notes to stdout

set -euo pipefail

VERSION=${1:-}

if [[ -z "$VERSION" ]]; then
  VERSION=$(grep '^android.versionName=' version.properties | cut -d= -f2)
fi

CHANGELOG_FILE="CHANGELOG.md"

echo "## What's New in v${VERSION}"
echo ""

# Extract the section for this version from CHANGELOG
awk -v ver="$VERSION" '
  /^## \['"$VERSION"'\]/ { found=1; next }
  found && /^## \[/ { exit }
  found { print }
' "$CHANGELOG_FILE" | sed '/^[[:space:]]*$/d' | head -50

echo ""
echo "---"
echo ""
echo "### Commits since last release"
echo ""

# Get previous tag to show commits since then
PREV_TAG=$(git tag --sort=-version:refname | grep '^v' | sed -n '2p' || true)

if [[ -n "$PREV_TAG" ]]; then
  git log "${PREV_TAG}..HEAD" --oneline --no-merges | head -30 | while read -r line; do
    echo "- $line"
  done
else
  git log --oneline --no-merges | head -30 | while read -r line; do
    echo "- $line"
  done
fi
