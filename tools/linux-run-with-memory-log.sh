#!/usr/bin/env bash

set -u

objdir="${MOZ_OBJDIR:-obj-x86_64-pc-linux-gnu}"
if [[ "$objdir" = /* ]]; then
  objdir_path="$objdir"
else
  objdir_path="$PWD/$objdir"
fi
firefox_bin="$objdir_path/dist/bin/firefox"
log_dir="$objdir/tmp"
mkdir -p "$log_dir"
log_file="$log_dir/firefox-process-memory-$(date -u +%Y%m%dT%H%M%SZ).log"

./mach run "$@" &
mach_pid=$!
printf 'Recording Firefox process memory every 500 ms in %s\n' "$log_file" >&2

while kill -0 "$mach_pid" 2>/dev/null; do
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
  available_kb=$(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo)
  swap_free_kb=$(awk '/^SwapFree:/ { print $2 }' /proc/meminfo)
  for process_dir in /proc/[0-9]*; do
    [[ -r "$process_dir/comm" && -r "$process_dir/status" && -r "$process_dir/cmdline" ]] || continue
    read -r process_name < "$process_dir/comm"
    command_line=$(tr '\0' ' ' < "$process_dir/cmdline" 2>/dev/null)
    [[ "$command_line" == "$firefox_bin"* ]] || continue
    pid=${process_dir##*/}
    read -r ppid rss_kb anon_kb vmsize_kb < <(
      awk '/^PPid:/ { ppid=$2 } /^VmRSS:/ { rss=$2 } /^RssAnon:/ { anon=$2 } /^VmSize:/ { vmsize=$2 } END { print ppid, rss, anon, vmsize }' \
        "$process_dir/status" 2>/dev/null
    )
    printf '%s pid=%s ppid=%s comm=%s rss_kb=%s anon_kb=%s vmsize_kb=%s available_kb=%s swap_free_kb=%s command=%s\n' \
      "$timestamp" "$pid" "${ppid:-?}" "$process_name" "${rss_kb:-?}" "${anon_kb:-?}" "${vmsize_kb:-?}" \
      "${available_kb:-?}" "${swap_free_kb:-?}" "${command_line:-?}" >> "$log_file"
  done
  sleep 0.1
done

wait "$mach_pid"
