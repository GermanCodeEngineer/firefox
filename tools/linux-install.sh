#!/usr/bin/env bash

set -euo pipefail

objdir="${MOZ_OBJDIR:-obj-x86_64-pc-linux-gnu}"
install_dir="${FIREFOX_INSTALL_DIR:-$HOME/.local/opt/firefox-local}"
package=""
shopt -s nullglob
for candidate in "$objdir"/dist/firefox-*.tar.xz; do
  if [[ -z "$package" || "$candidate" -nt "$package" ]]; then
    package="$candidate"
  fi
done

if [[ -z "$package" ]]; then
  printf 'No Firefox package found in %s/dist. Run npm run linux-build-package first.\n' "$objdir" >&2
  exit 1
fi

mkdir -p "$(dirname "$install_dir")"
staging_dir=$(mktemp -d "${install_dir}.tmp.XXXXXX")
trap 'rm -rf -- "$staging_dir"' EXIT
tar -xJf "$package" -C "$staging_dir" --strip-components=1
if [[ ! -x "$staging_dir/firefox" ]]; then
  printf 'Package does not contain a Firefox executable: %s\n' "$package" >&2
  exit 1
fi

backup=""
if [[ -e "$install_dir" || -L "$install_dir" ]]; then
  backup="${install_dir}.backup-$(date +%Y%m%d-%H%M%S-%N)"
  mv -T -- "$install_dir" "$backup"
  printf 'Previous installation saved to %s\n' "$backup"
fi
if ! mv -T -- "$staging_dir" "$install_dir"; then
  if [[ -n "$backup" ]]; then
    mv -T -- "$backup" "$install_dir"
  fi
  exit 1
fi
printf 'Installed %s to %s\n' "$package" "$install_dir"
