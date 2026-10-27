#!/usr/bin/env bash
# Keeps a release's R8 mapping file (needed to read its crash stack traces) on the
# `mappings` branch, compressed, instead of attaching it to the release.
#   archive-mapping.sh <mapping.txt> <version>
set -euo pipefail
mapping="$1"
version="$2"
dir="$RUNNER_TEMP/mappings"
git config --global user.name "github-actions[bot]"
git config --global user.email "41898282+github-actions[bot]@users.noreply.github.com"
if git fetch -q origin mappings:refs/remotes/origin/mappings 2>/dev/null; then
    git worktree add -q -B mappings "$dir" origin/mappings
else
    git worktree add -q --orphan -b mappings "$dir"
fi
xz -9 -T0 -c "$mapping" > "$dir/twill-$version-mapping.txt.xz"
cd "$dir"
git add "twill-$version-mapping.txt.xz"
git commit -qm "Mapping file for Twill $version"
git push -q origin mappings
echo "::notice title=Mapping file kept::twill-$version-mapping.txt.xz on the mappings branch ($(du -h "twill-$version-mapping.txt.xz" | cut -f1))"
