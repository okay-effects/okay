# Backlog

> **The backlog is [`backlog.d/`](backlog.d/), one file per item**
> (boards-d, 2026-09-18).
>
> ```
> scripts/board.sh backlog          # read it, assembled, by section
> backlog.d/<section>/<slug>.md     # one item; the slug is the lane's name
> ```
>
> Filing is a new file. Promoting is `git mv backlog.d/<section>/<x>.md
> sprint.d/queue/<x>.md`. Landing is `git rm` plus `changelog.d/<slug>.md`.
> Closed work is in [`BACKLOG-ARCHIVE.md`](BACKLOG-ARCHIVE.md).
>
> Until 2026-11-24 this file still held the pre-2026-09-18 board below the
> pointer; it is in git history (`git show 943b7b9ac:BACKLOG.md`).
