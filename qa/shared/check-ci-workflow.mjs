#!/usr/bin/env node
// Reads a GitHub Actions workflow and a stage.yaml, and says whether a pull request would run
// every one of the stage's verification commands in a way that fails the pull request when a
// command fails (F01-S01, AC-10). It reads the files; it runs nothing. Whether GitHub really does
// so is a separate case, verified on GitHub (TC-F01-S01-057).
//
//   node qa/shared/check-ci-workflow.mjs <workflow.yml> <stage.yaml> <the API's test source root>
//
// What "runs every command and fails when one fails" means for a workflow of parallel jobs:
//   - it runs on every pull request;
//   - the check main's branch protection requires (named exactly REQUIRED_CHECK) waits, through
//     `needs`, for every job that runs a verification command; it runs `if: always()` (without
//     it a failed job makes the check "skipped", which branch protection counts as passing), and
//     its step fails unless every job it waits for succeeded;
//   - every command is a step of such a job, word for word, that cannot be skipped or ignored, and
//     the commands a job runs come in the stage's order;
//   - the API's `./mvnw -q verify` may be split into matrix shards of one `verify` each, with
//     `-Dtest=` patterns; then one shard must be exactly the negation of the others' patterns, the
//     others' patterns must not overlap, and every test class in the API's test sources must be
//     run by exactly one shard.
//
// Exits 0 when the workflow is right, 1 with one line per finding, and 2 when it was given
// nothing to check.
import fs from 'node:fs';
import path from 'node:path';

import { parse } from 'yaml';

const REQUIRED_CHECK = 'Verification commands';
const SHARDED = 'cd apps/api && ./mvnw -q verify';
const SHARD_ARGS = '"-Dtest=$TESTS" -Dsurefire.failIfNoSpecifiedTests=false';

const [workflowPath, stagePath, testRoot] = process.argv.slice(2);
if (!workflowPath || !stagePath || !testRoot || ![workflowPath, stagePath, testRoot].every((p) => fs.existsSync(p))) {
  console.error('ci: usage: check-ci-workflow.mjs <workflow.yml> <stage.yaml> <API test source root> (all must exist)');
  process.exit(2);
}

const workflow = parse(fs.readFileSync(workflowPath, 'utf8'));
const commands = parse(fs.readFileSync(stagePath, 'utf8'))?.verification?.commands ?? [];
if (!Array.isArray(commands) || commands.length === 0) {
  console.error(`ci: ${stagePath} lists no verification.commands, so there is nothing to look for`);
  process.exit(2);
}

const findings = [];
const notes = [];
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

const jobs = Object.entries(workflow?.jobs ?? {});
if (jobs.length === 0) findings.push('the workflow has no job');

// --- the required check: waits for the jobs, always runs, fails unless all succeeded -----------
const required = jobs.filter(([, job]) => job?.name === REQUIRED_CHECK);
let waitedFor = new Set();
if (required.length !== 1) {
  findings.push(`the workflow has ${required.length} job(s) named "${REQUIRED_CHECK}", the check main's branch protection requires; it needs exactly one`);
} else {
  const [checkId, check] = required[0];
  const needs = check.needs === undefined ? [] : Array.isArray(check.needs) ? check.needs : [check.needs];
  waitedFor = new Set(needs.map(String));
  if (one(check.if) !== 'always()') {
    findings.push(`"${REQUIRED_CHECK}" (job ${checkId}) does not run if: always(), so a failed job would leave it skipped, which branch protection counts as passing`);
  }
  if (check['continue-on-error']) findings.push(`"${REQUIRED_CHECK}" has continue-on-error, so its failure does not fail the pull request`);
  const steps = Array.isArray(check.steps) ? check.steps : [];
  const gate = steps.find((step) => /toJSON\(\s*needs\s*\)/.test(Object.values(step?.env ?? {}).join(' ')) && /jq\s+-e\b/.test(String(step?.run ?? ''))
    && /all\(\s*\.value\.result\s*==\s*"success"\s*\)/.test(String(step?.run ?? '')));
  if (!gate) {
    findings.push(`"${REQUIRED_CHECK}" has no step that fails unless every job it waits for succeeded (jq -e over toJSON(needs) with all(.value.result == "success"))`);
  } else {
    if (gate['continue-on-error']) findings.push(`"${REQUIRED_CHECK}": the step that tests the jobs' results has continue-on-error`);
    if (gate.if !== undefined) findings.push(`"${REQUIRED_CHECK}": the step that tests the jobs' results runs only if "${one(gate.if)}"`);
    const shell = gate.shell ?? check?.defaults?.run?.shell ?? workflow?.defaults?.run?.shell;
    if (shell !== undefined && !/^(bash|sh)$/.test(String(shell))) findings.push(`"${REQUIRED_CHECK}": the step that tests the jobs' results uses the shell "${shell}"`);
  }
  for (const name of waitedFor) {
    if (!jobs.some(([id]) => id === name)) findings.push(`"${REQUIRED_CHECK}" waits for "${name}", which is not a job of the workflow`);
  }
}

// --- the API's test shards ----------------------------------------------------------------------
/** A Surefire -Dtest pattern as a regular expression over a class's path without its extension. */
function patternRegex(pattern) {
  let out = '';
  for (let i = 0; i < pattern.length; i++) {
    if (pattern.startsWith('**', i)) { out += '.*'; i++; }
    else if (pattern[i] === '*') out += '[^/]*';
    else out += pattern[i].replace(/[.+?^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${out}$`);
}

/** Every test class under the API's test sources, as a path such as com/salonplatform/staff/XTest. */
function testClasses(root) {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.java') && /@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/.test(fs.readFileSync(full, 'utf8'))) {
        out.push(path.relative(root, full).replace(/\\/g, '/').replace(/\.java$/, ''));
      }
    }
  };
  walk(root);
  return out;
}

/** Whether a shard's -Dtest value runs a class: some inclusion matches (all, if it has none) and no exclusion does. */
function shardRuns(patterns, cls) {
  const includes = patterns.filter((p) => !p.startsWith('!'));
  const excludes = patterns.filter((p) => p.startsWith('!')).map((p) => p.slice(1));
  return (includes.length === 0 || includes.some((p) => patternRegex(p).test(cls))) && !excludes.some((p) => patternRegex(p).test(cls));
}

/** Checks a sharded step; returns whether it may stand for the command. */
function checkShards(jobId, job, step) {
  const shards = job?.strategy?.matrix?.shard;
  if (one(step?.env?.TESTS) !== '${{ matrix.shard.tests }}' || !Array.isArray(shards) || shards.length === 0) {
    findings.push(`job "${jobId}" runs "${SHARDED}" with -Dtest=$TESTS, but TESTS is not \${{ matrix.shard.tests }} of a matrix of shards`);
    return false;
  }
  const split = (value) => one(value).split(',').map((p) => p.trim()).filter(Boolean);
  const all = shards.map((shard) => ({ name: String(shard?.name ?? '(unnamed)'), patterns: split(shard?.tests) }));
  const rest = all.filter((s) => s.patterns.length > 0 && s.patterns.every((p) => p.startsWith('!')));
  const named = all.filter((s) => !rest.includes(s));
  for (const shard of all) if (shard.patterns.length === 0) findings.push(`shard "${shard.name}" of job "${jobId}" names no test pattern, so it runs every test class`);
  if (rest.length !== 1) {
    findings.push(`job "${jobId}" has ${rest.length} shard(s) of exclusions only; it needs exactly one "rest" shard that excludes what the others run`);
  } else {
    const included = named.flatMap((s) => s.patterns);
    for (const shard of named) for (const p of shard.patterns) if (p.startsWith('!')) findings.push(`shard "${shard.name}" mixes an exclusion (${p}) into its inclusions`);
    const negated = new Set(rest[0].patterns.map((p) => p.slice(1)));
    const missing = included.filter((p) => !p.startsWith('!') && !negated.has(p));
    const extra = [...negated].filter((p) => !included.includes(p));
    if (missing.length) findings.push(`shard "${rest[0].name}" does not exclude ${missing.join(', ')}, so those classes run twice`);
    if (extra.length) findings.push(`shard "${rest[0].name}" excludes ${extra.join(', ')}, which no other shard runs, so no shard runs those classes`);
  }
  const classes = testClasses(testRoot);
  if (classes.length === 0) {
    findings.push(`no test class found under ${testRoot}, so the shards cannot be shown to cover them`);
    return false;
  }
  const none = [];
  const twice = [];
  for (const cls of classes) {
    const by = all.filter((s) => shardRuns(s.patterns, cls)).map((s) => s.name);
    if (by.length === 0) none.push(cls);
    if (by.length > 1) twice.push(`${cls} (${by.join(' and ')})`);
  }
  if (none.length) findings.push(`no shard of job "${jobId}" runs ${none.length} test class(es), for example ${none.slice(0, 3).join(', ')}`);
  if (twice.length) findings.push(`more than one shard of job "${jobId}" runs ${twice.length} test class(es), for example ${twice.slice(0, 3).join(', ')}`);
  notes.push(`ci: job "${jobId}": ${all.length} shard(s) over ${classes.length} test classes: ${none.length} run by none, ${twice.length} by more than one`);
  return true;
}

// --- every command: a step of a job the check waits for, in order, never skipped or ignored ----
const runsCommand = new Map(); // command -> job ids that run it
for (const [jobId, job] of jobs) {
  if (job?.name === REQUIRED_CHECK) continue;
  const steps = Array.isArray(job?.steps) ? job.steps : [];
  const defaultShell = job?.defaults?.run?.shell ?? workflow?.defaults?.run?.shell;
  let previous = -1;
  for (const command of commands) {
    const index = steps.findIndex((step) => one(step?.run) === one(command)
      || (one(command) === SHARDED && one(step?.run) === `${SHARDED} ${SHARD_ARGS}`));
    if (index === -1) continue;
    const step = steps[index];
    if (index < previous) findings.push(`job "${jobId}": "${command}" is run out of order`);
    previous = Math.max(previous, index);
    const problems = [];
    if (job?.if !== undefined) problems.push(`job "${jobId}" runs only if "${one(job.if)}"`);
    if (job?.['continue-on-error']) problems.push(`job "${jobId}" has continue-on-error, so its failure does not fail the pull request`);
    if (step['continue-on-error']) problems.push(`the step that runs "${command}" has continue-on-error, so its failure is ignored`);
    if (step.if !== undefined) problems.push(`the step that runs "${command}" runs only if "${one(step.if)}"`);
    const shell = step.shell ?? defaultShell;
    if (shell !== undefined && !/^(bash|sh)$/.test(String(shell))) problems.push(`the step that runs "${command}" uses the shell "${shell}", which may not stop at a failing command`);
    if (one(step.run) !== one(command) && !checkShards(jobId, job, step)) continue;
    findings.push(...problems);
    if (problems.length === 0) runsCommand.set(command, [...(runsCommand.get(command) ?? []), jobId]);
  }
}
for (const command of commands) {
  const by = runsCommand.get(command) ?? [];
  if (by.length === 0) {
    findings.push(`"${command}" is not run`);
  } else if (!by.some((jobId) => waitedFor.has(jobId))) {
    findings.push(`"${command}" is run only by job(s) ${by.join(', ')}, which "${REQUIRED_CHECK}" does not wait for, so its failure does not fail the pull request`);
  }
}

console.log(`ci: ${workflowPath}: ${jobs.length} job(s), ${commands.length} verification command(s) to find`);
for (const note of notes) console.log(note);
if (findings.length > 0) {
  for (const finding of [...new Set(findings)]) console.error(`ci: ${finding}`);
  console.error(`ci: ${new Set(findings).size} finding(s)`);
  process.exit(1);
}
console.log(`ci: a pull request runs every verification command in a job "${REQUIRED_CHECK}" waits for, and a failing command fails it`);
