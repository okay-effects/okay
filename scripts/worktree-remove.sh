#!/bin/sh
# worktree-remove.sh <worktree> — `git worktree remove` for a lane's worktree, including one whose okay-core
# submodule was initialised (`git submodule update --init okay-core`, AGENTS.md "okay-core, the kernel"). git
# refuses those ("working trees containing submodules cannot be moved or removed": it sees the worktree's own
# `modules/` git directory), so land.sh stopped at step 5 after every such lane (audit-real-world, 2026-11-13).
#
# What it does, refusing rather than guessing: for each initialised submodule of the worktree, REFUSE if it has
# uncommitted changes or commits on no remote branch (they live only in that worktree's modules/ directory);
# otherwise `git submodule deinit` it, delete the worktree's `modules/` git directory (a clone of what the remote
# has) and remove the worktree. Never --force. Run from the main checkout, as land.sh is.
set -e
wt="$1"
if [ -z "$wt" ] || [ ! -d "$wt" ]; then echo "usage: sh scripts/worktree-remove.sh <worktree>" >&2; exit 2; fi
gitdir=$(git -C "$wt" rev-parse --absolute-git-dir)
populated=$(git -C "$wt" submodule status 2>/dev/null | awk '$1 !~ /^-/ { print $2 }')
for sub in $populated; do
  if [ -n "$(git -C "$wt/$sub" status --porcelain)" ]; then
    echo "worktree-remove: $wt/$sub has uncommitted changes — not removing" >&2; exit 1
  fi
  if [ -n "$(git -C "$wt/$sub" log --oneline HEAD --not --remotes -- 2>/dev/null)" ]; then
    echo "worktree-remove: $wt/$sub has commits on no remote branch — push them, then re-run" >&2; exit 1
  fi
  git -C "$wt" submodule deinit -q "$sub"
  echo "worktree-remove: deinit $sub (clean, its commits on a remote)"
done
case "$gitdir" in
  */worktrees/*) if [ -d "$gitdir/modules" ]; then rm -rf "$gitdir/modules"; fi ;;
esac
git worktree remove "$wt"
