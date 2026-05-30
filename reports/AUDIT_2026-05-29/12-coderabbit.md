# CodeRabbit Review — wim-android

**Date:** 2026-05-29
**Scope:** wim-android repo only (HEAD = `49b50a6 feat(bubble): relay injected text to Termux:3133 phone bridge`)
**Status:** FAILED — TOOL NOT INSTALLED

## Failure

CodeRabbit CLI is not installed on this Windows machine. Checked:

- `coderabbit` / `cr` not on PATH (bash and PowerShell)
- Not in `%LOCALAPPDATA%\Programs\CodeRabbit`
- Not in `%LOCALAPPDATA%\coderabbit`
- Not in `%APPDATA%\npm\coderabbit.cmd`
- Not in `C:\Program Files\CodeRabbit`
- Not in npm global package list

The coderabbit:code-review skill explicitly forbids piping remote install scripts to a shell and requires the user to install from the official source (https://www.coderabbit.ai/cli) before review can run. Even if installed, `coderabbit auth login` requires interactive browser auth that can't be completed inside this agent context.

## To Run This Audit

1. Install CodeRabbit CLI (npm or verified binary from https://www.coderabbit.ai/cli)
2. Run `coderabbit auth login` interactively
3. Re-run this audit step: `coderabbit review --agent --dir C:\Users\georg\Documents\GitHub\wim-android`

No findings were fabricated. This file documents the tool gap so the AUDIT_2026-05-29 set stays honest.
