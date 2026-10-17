#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: bash apply-update.sh /path/to/your/original/just-in-transit" >&2
  exit 1
fi
update_dir="$(cd "$(dirname "$0")" && pwd -P)"
project_dir="$(cd "$1" && pwd -P)"
if [ "$update_dir" = "$project_dir" ]; then
  echo "Choose your original project folder, not this extracted update folder." >&2
  exit 1
fi
if [ ! -f "$project_dir/pom.xml" ] || ! grep -q '<artifactId>just-in-transit</artifactId>' "$project_dir/pom.xml"; then
  echo "The destination does not look like the Just in Transit project." >&2
  exit 1
fi
if [ -f "$project_dir/data/commutes.mv.db" ] && command -v lsof >/dev/null 2>&1; then
  if lsof "$project_dir/data/commutes.mv.db" >/dev/null 2>&1; then
    echo "Stop the running app before updating; its database is still open." >&2
    exit 1
  fi
fi

backup_path="$(dirname "$project_dir")/just-in-transit-backup-$(date +%Y%m%d-%H%M%S)-$$.tar.gz"
backup_items=()
for item in src scripts docs .mvn pom.xml README.md UPDATE-GUIDE.md mvnw mvnw.cmd data apply-update.sh; do
  if [ -e "$project_dir/$item" ]; then backup_items+=("$item"); fi
done
tar -czf "$backup_path" -C "$project_dir" "${backup_items[@]}"

for item in src scripts docs .mvn; do
  mkdir -p "$project_dir/$item"
  cp -R "$update_dir/$item/." "$project_dir/$item/"
done
for item in pom.xml README.md UPDATE-GUIDE.md mvnw mvnw.cmd apply-update.sh; do
  cp "$update_dir/$item" "$project_dir/$item"
done
# Generate a local one-time import code without committing it or copying it from another machine.
if [ ! -f "$project_dir/data/owner-restore.properties" ] && command -v openssl >/dev/null 2>&1; then
  mkdir -p "$project_dir/data"
  (umask 077; echo "jit.legacy-import-code=$(openssl rand -hex 24)" > "$project_dir/data/owner-restore.properties")
fi
echo "Update copied. Backup: $backup_path"
echo "After restarting, open / for the simplified personal commute planner."
echo "Legacy import remains available only when jit.personal.enabled=false; see UPDATE-GUIDE.md."
echo "In your original project, run: sh mvnw clean test"
echo "Then restart the app. Your Git history and saved commute database were preserved."
