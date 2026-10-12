#!/usr/bin/env bash
# Publishes $OUT/publish/* as the assets of the `device-check` pre-release (never "latest").
# The release is kept and only its assets are replaced: GitHub refused to re-create the tag right
# after the release had been deleted. Needs GH_TOKEN (contents: write), GITHUB_REPOSITORY, GITHUB_SHA.
set -u
TAG=device-check
REPO="$GITHUB_REPOSITORY"
DIR="${OUT:-device-results}/publish"
TITLE="Device check (latest run, not for install)"
mkdir -p "$DIR"
NOTES="$(mktemp)"
{
  echo "Tested commit: $GITHUB_SHA. The tag may point elsewhere; assets are replaced on every run."
  echo
  if [ -s "$DIR/summary.md" ]; then head -c 110000 "$DIR/summary.md"; else echo "No summary was produced (see the workflow run)."; fi
} > "$NOTES"

if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  echo "Reusing the existing $TAG release; deleting its old assets"
  gh api "repos/$REPO/releases/tags/$TAG" --jq '.assets[].id' | while read -r id; do
    gh api -X DELETE "repos/$REPO/releases/assets/$id" >/dev/null || echo "could not delete asset $id"
  done
  gh release edit "$TAG" -R "$REPO" --title "$TITLE" --notes-file "$NOTES" --prerelease --latest=false
else
  echo "Creating the $TAG release at $GITHUB_SHA"
  if ! gh release create "$TAG" -R "$REPO" --target "$GITHUB_SHA" --title "$TITLE" --notes-file "$NOTES" --prerelease --latest=false; then
    default="$(gh api "repos/$REPO" --jq .default_branch)"
    echo "::warning::Creating the tag at $GITHUB_SHA was refused; retrying on the default branch ($default)"
    gh release create "$TAG" -R "$REPO" --target "$default" --title "$TITLE" --notes-file "$NOTES" --prerelease --latest=false \
      || { echo "::error::Could not create the $TAG release"; exit 1; }
  fi
fi
shopt -s nullglob
files=("$DIR"/*)
[ ${#files[@]} -gt 0 ] && gh release upload "$TAG" -R "$REPO" --clobber "${files[@]}"
echo "Published ${#files[@]} assets: https://github.com/$REPO/releases/tag/$TAG"
