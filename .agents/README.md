# Running the lanes

The harness is a separate, private repository (`agent-harness`). This repository holds only its adapter: `project/` (gates, protected paths, prompt) and `campaigns/` (backlogs). The harness reads `project/` from `origin/main` and refuses any version but `HARNESS_VERSION`.

## Once
1. Merge this adapter to `main`, and put `agent-harness/bin` on your `PATH`.
2. `mkdir -p ~/.fondaria-agents`, copy the harness's `template/config.env.example` to `~/.fondaria-agents/config.env`, `chmod 600` it and set the models.
3. Decide what unattended sessions may do:
   - Claude: copy `project/lane-permissions.example.json` to `~/.fondaria-agents/claude-settings.json` and edit it (set the harness path). `.claude/settings.json` asks before `git commit`, `git push` and `rm`. An unattended session has nobody to ask, so those stay refused until you change that. Coders need `git commit`; pushes happen inside `harness submit`.
   - Antigravity: set `AGY_PERMISSION_ARGS`.
   - `mvn` and `make` run whatever the build says, so an allow-list limits accidents, not a hostile build. For a hard boundary run the lanes under a separate macOS user.
4. On GitHub: protect `main` (require the two CI checks), enable Dependabot alerts and private vulnerability reporting, add the `NVD_APIKEY` secret (the weekly dependency scan has never run without it).
5. Create the backlog. It is a dry run unless you add `--create`, which opens **public** issues:
   `harness campaign .agents/campaigns/2026-11-stack-then-security.md`

## Daily
Run inside any checkout of this repository.

| | |
|---|---|
| Start | `harness start` (tmux session `fondaria`; clones `~/.fondaria-agents/repo`) |
| See what waits on you | `harness digest` |
| Status, usage, live view | `harness ctl status` · `usage` · `watch <lane>` |
| Pause, resume, stop | `harness ctl pause [lane]` · `resume [lane]` · `stop` |
| Private findings | `harness findings`, files in `~/.fondaria-agents/findings/` |

Aura Home's lanes use their own home and tmux session, but the same Docker VM. Fondaria's verify waits while `aura-*` containers run; pause those lanes first or expect long waits. Aura's side does not wait for Fondaria.

To try a change to the adapter before it is merged, prefix a command with `HARNESS_ADAPTER_DIR=.agents/project`.
