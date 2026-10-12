#!/usr/bin/env node
// Reads a GitHub Actions workflow and a stage.yaml, and says whether a pull request would run
// every one of the stage's verification commands, in order, in a way that fails the pull request
// when a command fails. It reads the files; it runs nothing. Whether GitHub really does so is a
// separate case, verified on GitHub once a remote exists (F01-S01, AC-10).
//
//   node qa/shared/check-ci-workflow.mjs <workflow.yml> <stage.yaml>
//
// Exits 0 when the workflow is right, 1 with one line per finding, and 2 when it was given
// nothing to check.
import fs from 'node:fs';

import { parse } from 'yaml';

const [workflowPath, stagePath] = process.argv.slice(2);
if (!workflowPath || !stagePath || !fs.existsSync(workflowPath) || !fs.existsSync(stagePath)) {
  console.error('ci: usage: check-ci-workflow.mjs <workflow.yml> <stage.yaml> (both must exist)');
  process.exit(2);
}

const workflow = parse(fs.readFileSync(workflowPath, 'utf8'));
const commands = parse(fs.readFileSync(stagePath, 'utf8'))?.verification?.commands ?? [];
if (!Array.isArray(commands) || commands.length === 0) {
  console.error(`ci: ${stagePath} lists no verification.commands, so there is nothing to look for`);
  process.exit(2);
}

const findings = [];
const one = (text) => String(text ?? '').trim().replace(/\s+/g, ' ');

// --- it runs on a pull request, on every pull request -------------------------------------------
// YAML 1.1 parsers read the key `on` as the boolean true; accept either spelling of the key.
const triggers = workflow?.on ?? workflow?.true;
const names = typeof triggers === 'string' ? [triggers] : Array.isArray(triggers) ? triggers : Object.keys(triggers ?? {});
if (!names.includes('pull_request')) {
  findings.push(`the workflow does not run on pull_request (it runs on: ${names.join(', ') || 'nothing'})`);
} else if (triggers && typeof triggers === 'object' && !Array.isArray(triggers) && triggers.pull_request) {
  const filter = triggers.pull_request;
  for (const key of ['paths', 'paths-ignore', 'branches', 'branches-ignore', 'types']) {
    if (key in filter) {
      findings.push(`pull_request is narrowed by "${key}", so some pull requests would not be checked`);
    }
  }
}

// --- one job runs every command, in order, and none may fail quietly --------------------------
const jobs = Object.entries(workflow?.jobs ?? {});
if (jobs.length === 0) {
  findings.push('the workflow has no job');
}
let best = { missing: commands, job: null };
for (const [name, job] of jobs) {
  const steps = Array.isArray(job?.steps) ? job.steps : [];
  const problems = [];
  if (job?.if !== undefined) problems.push(`job "${name}" runs only if "${one(job.if)}"`);
  if (job?.['continue-on-error']) problems.push(`job "${name}" has continue-on-error, so its failure does not fail the pull request`);
  const defaultShell = job?.defaults?.run?.shell ?? workflow?.defaults?.run?.shell;
  let position = -1;
  const missing = [];
  for (const command of commands) {
    const index = steps.findIndex((step, at) => at > position && one(step?.run) === one(command));
    if (index === -1) {
      const anywhere = steps.find((step) => one(step?.run) === one(command));
      missing.push(anywhere ? `"${command}" is run out of order` : `"${command}" is not run`);
      continue;
    }
    position = index;
    const step = steps[index];
    if (step['continue-on-error']) problems.push(`the step that runs "${command}" has continue-on-error, so its failure is ignored`);
    if (step.if !== undefined) problems.push(`the step that runs "${command}" runs only if "${one(step.if)}"`);
    const shell = step.shell ?? defaultShell;
    if (shell !== undefined && !/^(bash|sh)$/.test(String(shell))) problems.push(`the step that runs "${command}" uses the shell "${shell}", which may not stop at a failing command`);
  }
  // A later step that always succeeds cannot hide an earlier failure, but one that runs "always" can be skipped over; nothing to check there.
  if (missing.length < best.missing.length || best.job === null) {
    best = { missing, job: name, problems };
  }
}
if (best.job !== null) {
  for (const entry of best.missing) findings.push(`job "${best.job}": ${entry}`);
  for (const entry of best.problems ?? []) findings.push(entry);
}

console.log(`ci: ${workflowPath}: ${jobs.length} job(s), ${commands.length} verification command(s) to find`);
if (findings.length > 0) {
  for (const finding of findings) console.error(`ci: ${finding}`);
  console.error(`ci: ${findings.length} finding(s)`);
  process.exit(1);
}
console.log('ci: a pull request runs every verification command, in order, and a failing command fails it');
