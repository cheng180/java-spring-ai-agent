## Agent skills

### Issue tracker

GitHub Issues on `cheng180/java-spring-ai-agent`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default canonical labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at repo root. See `docs/agents/domain.md`.

## Web access

Fetch all web content with Bash `curl`; do not use WebFetch or WebSearch. WebFetch fails in this environment because its pre-fetch domain safety check calls claude.ai, which is unreachable from this network; WebSearch is a server-side Anthropic tool that the local relay does not support. For GitHub URLs, use the gh-proxy mirror (`https://gh-proxy.com/` prefix).
