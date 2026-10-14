#!/usr/bin/env bash
# bump-version.sh — bumps version.properties and prepends CHANGELOG entry
# Usage: ./scripts/bump-version.sh [patch|minor|major|none]
# Default: patch

set -euo pipefail

BUMP=${1:-patch}
PROPS_FILE="version.properties"
CHANGELOG_FILE="CHANGELOG.md"

# Read current version
CURRENT_NAME=$(grep '^android.versionName=' "$PROPS_FILE" | cut -d= -f2)
CURRENT_CODE=$(grep '^android.versionCode=' "$PROPS_FILE" | cut -d= -f2)

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_NAME"

case "$BUMP" in
  major)
    MAJOR=$((MAJOR + 1))
    MINOR=0
    PATCH=0
    ;;
  minor)
    MINOR=$((MINOR + 1))
    PATCH=0
    ;;
  patch)
    PATCH=$((PATCH + 1))
    ;;
  none)
    echo "No version bump requested."
    echo "VERSION_NAME=$CURRENT_NAME" >> "${GITHUB_ENV:-/dev/null}"
    echo "VERSION_CODE=$CURRENT_CODE" >> "${GITHUB_ENV:-/dev/null}"
    exit 0
    ;;
  *)
    echo "Unknown bump type: $BUMP (use patch/minor/major/none)" >&2
    exit 1
    ;;
esac

NEW_NAME="${MAJOR}.${MINOR}.${PATCH}"
NEW_CODE=$((CURRENT_CODE + 1))

echo "Bumping: $CURRENT_NAME ($CURRENT_CODE) → $NEW_NAME ($NEW_CODE)"

# Update version.properties
sed -i "s/^android.versionName=.*/android.versionName=${NEW_NAME}/" "$PROPS_FILE"
sed -i "s/^android.versionCode=.*/android.versionCode=${NEW_CODE}/" "$PROPS_FILE"

# Prepend CHANGELOG entry for new version
DATE=$(date +%Y-%m-%d)
TEMP_FILE=$(mktemp)

awk -v ver="$NEW_NAME" -v date="$DATE" '
  /^## \[Unreleased\]/ {
    print $0
    print ""
    print "## [" ver "] - " date
    print ""
    print "### Changed"
    print "- See commit log for details"
    next
  }
  { print }
' "$CHANGELOG_FILE" > "$TEMP_FILE"

mv "$TEMP_FILE" "$CHANGELOG_FILE"

echo "Updated $PROPS_FILE and $CHANGELOG_FILE"

# Export for GitHub Actions
echo "VERSION_NAME=$NEW_NAME" >> "${GITHUB_ENV:-/dev/null}"
echo "VERSION_CODE=$NEW_CODE" >> "${GITHUB_ENV:-/dev/null}"
