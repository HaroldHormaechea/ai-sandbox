'use strict';

/*
 * UC-37 — unit tests for the in-container transcript-tail helper's extracted
 * resolution seams (container-bin/aisandbox-conversation-tail).
 *
 * These live under container-bin/ (production scope) rather than server/android
 * test trees because they exercise the Node helper, which has no home in
 * `paths.test`. They use ONLY the node:20 stdlib (`node:test` + `node:assert`)
 * already present in the sandbox base image — no new dependencies.
 *
 * Run: `node --test container-bin/`   (or `npm test` from container-bin/)
 *
 * The headline regression: the original helper resolved the active transcript by
 * scanning the live claude PID's OPEN .jsonl fd — a premise that is empirically
 * false (claude holds no transcript fd open between writes), so resolution always
 * returned null and the conversation channel hung silently. These tests prove the
 * replacement resolves correctly from a directory listing + process identity
 * ALONE, with no held fd anywhere in the picture.
 */

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const helper = require('./aisandbox-conversation-tail');

const TEAM = 'ai-sandbox-uc-37';
const FOREIGN_TEAM = 'orbital-frontier-uc-9';

// ──────────────────────── slugFromCwd ────────────────────────

test('slugFromCwd matches the empirical cwd.replace(/[^a-zA-Z0-9]/g, "-") encoding', () => {
  assert.strictEqual(helper.slugFromCwd('/workspace/project-builder'), '-workspace-project-builder');
  // '.' is non-alphanumeric → '-', so '/.claude' becomes '--claude' (double dash).
  assert.strictEqual(helper.slugFromCwd('/workspace/p/.claude/teams/dev-team'), '-workspace-p--claude-teams-dev-team');
  assert.strictEqual(helper.slugFromCwd('/workspace/ai-sandbox-uc-37'), '-workspace-ai-sandbox-uc-37');
  // Underscores are also non-alphanumeric.
  assert.strictEqual(helper.slugFromCwd('/a/b_c'), '-a-b-c');
  assert.strictEqual(helper.slugFromCwd(''), null);
  assert.strictEqual(helper.slugFromCwd(null), null);
});

// ──────────────────────── parseClaudeIdentity ────────────────────────

const NUL = String.fromCharCode(0);

test('parseClaudeIdentity handles the `--flag value` form (real teammate cmdline)', () => {
  const cmdline = [
    '/usr/local/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe',
    '--agent-id', 'analyst@' + TEAM,
    '--agent-name', 'analyst',
    '--team-name', TEAM,
    '--parent-session-id', '1de345d3-4b95-4f8f-b18a-a0c43dc58491',
    '--dangerously-skip-permissions',
    '', // trailing NUL that /proc/<pid>/cmdline ends with
  ].join(NUL);
  const id = helper.parseClaudeIdentity(cmdline);
  assert.strictEqual(id.agentName, 'analyst');
  assert.strictEqual(id.teamName, TEAM);
  assert.strictEqual(id.parentSessionId, '1de345d3-4b95-4f8f-b18a-a0c43dc58491');
});

test('parseClaudeIdentity handles the `--flag=value` form', () => {
  const cmdline = ['claude', '--agent-name=qa', '--team-name=' + TEAM, '--parent-session-id=abc-123', ''].join(NUL);
  const id = helper.parseClaudeIdentity(cmdline);
  assert.strictEqual(id.agentName, 'qa');
  assert.strictEqual(id.teamName, TEAM);
  assert.strictEqual(id.parentSessionId, 'abc-123');
});

test('parseClaudeIdentity returns all-null for a plain main-session cmdline', () => {
  const id = helper.parseClaudeIdentity(['claude', '--dangerously-skip-permissions', ''].join(NUL));
  assert.strictEqual(id.agentName, null);
  assert.strictEqual(id.teamName, null);
  assert.strictEqual(id.parentSessionId, null);
  // Empty / nullish input is tolerated.
  assert.deepStrictEqual(helper.parseClaudeIdentity(''), { agentName: null, teamName: null, parentSessionId: null });
  assert.deepStrictEqual(helper.parseClaudeIdentity(null), { agentName: null, teamName: null, parentSessionId: null });
});

// ──────────────────────── selectAgentTranscript (teammate/subagent anchoring) ────────────────────────

test('selectAgentTranscript anchors by (agentName, teamName), newest mtime wins', () => {
  const candidates = [
    { path: '/p/analyst-old.jsonl', mtimeMs: 10, agentName: 'analyst', teamName: TEAM },
    { path: '/p/analyst-new.jsonl', mtimeMs: 30, agentName: 'analyst', teamName: TEAM },
    { path: '/p/qa.jsonl', mtimeMs: 99, agentName: 'qa', teamName: TEAM },
  ];
  assert.strictEqual(helper.selectAgentTranscript(candidates, 'analyst', TEAM), '/p/analyst-new.jsonl');
  assert.strictEqual(helper.selectAgentTranscript(candidates, 'qa', TEAM), '/p/qa.jsonl');
  assert.strictEqual(helper.selectAgentTranscript(candidates, 'developer', TEAM), null);
});

test('AC23 — selectAgentTranscript NEVER picks a foreign team\'s same-named agent, even if newer', () => {
  const candidates = [
    { path: '/p/mine-analyst.jsonl', mtimeMs: 10, agentName: 'analyst', teamName: TEAM },
    // foreign team, same agent name, NEWER mtime — must still be excluded.
    { path: '/p/foreign-analyst.jsonl', mtimeMs: 9999, agentName: 'analyst', teamName: FOREIGN_TEAM },
  ];
  assert.strictEqual(helper.selectAgentTranscript(candidates, 'analyst', TEAM), '/p/mine-analyst.jsonl');
});

// ──────────────────────── selectMainByParent (tier-1 main resolution) ────────────────────────

test('tier-1 — selectMainByParent anchors main to the orchestrator stem exactly (no fd needed)', () => {
  const orchStem = '1de345d3-4b95-4f8f-b18a-a0c43dc58491';
  const candidates = [
    { path: '/p/' + orchStem + '.jsonl', stem: orchStem, mtimeMs: 50 },
    { path: '/p/other.jsonl', stem: 'other', mtimeMs: 9999 }, // newer but not the parent stem
  ];
  // A teammate's --parent-session-id is exactly the orchestrator's transcript stem.
  const parentIds = new Set([orchStem]);
  assert.strictEqual(helper.selectMainByParent(candidates, parentIds), '/p/' + orchStem + '.jsonl');
  // No matching parent id → tier-1 declines (caller falls through to tier-2).
  assert.strictEqual(helper.selectMainByParent(candidates, new Set(['nope'])), null);
});

test('AC23 — tier-1 picks the parent stem even when a FOREIGN orchestrator file is newer', () => {
  const mineStem = 'mine-orch';
  const candidates = [
    { path: '/p/mine-orch.jsonl', stem: mineStem, mtimeMs: 100 },
    { path: '/p/foreign-orch.jsonl', stem: 'foreign-orch', mtimeMs: 9999 }, // foreign, newer
  ];
  // Our teammates advertise OUR orchestrator stem; the foreign team's stem is absent.
  assert.strictEqual(helper.selectMainByParent(candidates, new Set([mineStem])), '/p/mine-orch.jsonl');
});

// ──────────────────────── selectMainNewestNoAgent (tier-2 fallback) ────────────────────────

test('tier-2 — selectMainNewestNoAgent picks the newest agentName-absent transcript', () => {
  const candidates = [
    { path: '/p/teammate.jsonl', mtimeMs: 9999, agentNamePresent: true }, // a teammate file — excluded
    { path: '/p/orch-stale.jsonl', mtimeMs: 100, agentNamePresent: false },
    { path: '/p/orch-live.jsonl', mtimeMs: 500, agentNamePresent: false }, // the live (newest) main session
  ];
  assert.strictEqual(helper.selectMainNewestNoAgent(candidates), '/p/orch-live.jsonl');
});

test('AC23 — tier-2 excludes teammate files and prefers the live (actively-appended) main file', () => {
  // In the no-team fallback the live main session is the one being appended right
  // now (newest mtime); stale foreign main files are older, teammate files carry
  // agentName and are filtered out entirely.
  const candidates = [
    { path: '/p/foreign-teammate.jsonl', mtimeMs: 9999, agentNamePresent: true }, // newest, but has agentName
    { path: '/p/foreign-orch-stale.jsonl', mtimeMs: 50, agentNamePresent: false }, // older foreign main
    { path: '/p/my-live-orch.jsonl', mtimeMs: 800, agentNamePresent: false }, // my live main session
  ];
  assert.strictEqual(helper.selectMainNewestNoAgent(candidates), '/p/my-live-orch.jsonl');
  // Nothing agentName-absent → null (resolution then fails loud with no-transcript).
  assert.strictEqual(
    helper.selectMainNewestNoAgent([{ path: '/p/x.jsonl', mtimeMs: 1, agentNamePresent: true }]),
    null,
  );
});

// ──────────────────────── no-open-fd resolution (the headline regression) ────────────────────────

test('regression — main resolves from a shared slug-dir listing + identity ALONE, no held fd', () => {
  // Models the real bind-mounted ~/.claude/projects/<slug>/ shared across teams:
  // the original helper would have scanned the live claude PID's fds and found
  // ZERO open .jsonl, returning null and hanging. The identity+listing path below
  // resolves deterministically with no fd anywhere.
  const orchStem = 'orch-A';
  const slugListing = [
    { path: '/slug/orch-A.jsonl', stem: 'orch-A', mtimeMs: 100 }, // MY orchestrator (this team)
    { path: '/slug/ana-A.jsonl', stem: 'ana-A', mtimeMs: 90 }, // my analyst teammate
    { path: '/slug/orch-FOREIGN.jsonl', stem: 'orch-FOREIGN', mtimeMs: 9999 }, // another team, NEWER
    { path: '/slug/ana-FOREIGN.jsonl', stem: 'ana-FOREIGN', mtimeMs: 9999 }, // another team's analyst
  ];
  // A teammate process advertises --parent-session-id = my orchestrator stem.
  const parentIds = new Set([orchStem]);
  const resolved = helper.selectMainByParent(slugListing, parentIds);
  assert.strictEqual(resolved, '/slug/orch-A.jsonl', 'must anchor to my orchestrator, not the newer foreign one');
});

// ──────────────────────── FS seams: readTranscriptIdentity + listTopLevelTranscripts ────────────────────────

function writeJsonl(dir, name, lines) {
  const p = path.join(dir, name);
  fs.writeFileSync(p, lines.map((o) => JSON.stringify(o)).join('\n') + '\n');
  return p;
}

test('readTranscriptIdentity detects (agentName, teamName) from a teammate file head', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'uc37-id-'));
  try {
    const p = writeJsonl(dir, 'teammate.jsonl', [
      { type: 'summary', sessionId: 's1' },
      { type: 'agent-setting', sessionId: 's1' },
      { type: 'attachment', sessionId: 's1', agentName: 'analyst', teamName: TEAM },
      { type: 'user', sessionId: 's1', agentName: 'analyst', teamName: TEAM },
    ]);
    const id = helper.readTranscriptIdentity(p, 131072);
    assert.strictEqual(id.agentNamePresent, true);
    assert.strictEqual(id.agentName, 'analyst');
    assert.strictEqual(id.teamName, TEAM);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('readTranscriptIdentity reports agentName-absent for an orchestrator/main file', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'uc37-id-'));
  try {
    const p = writeJsonl(dir, 'orch.jsonl', [
      { type: 'summary', sessionId: 'orch-1' },
      { type: 'user', sessionId: 'orch-1' }, // no agentName anywhere
      { type: 'assistant', sessionId: 'orch-1', message: { content: [] } },
    ]);
    const id = helper.readTranscriptIdentity(p, 131072);
    assert.strictEqual(id.agentNamePresent, false);
    assert.strictEqual(id.agentName, null);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('readTranscriptIdentity tolerates a malformed/partial head without throwing', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'uc37-id-'));
  try {
    const p = path.join(dir, 'broken.jsonl');
    fs.writeFileSync(p, '{not json\n{"type":"attachment","agentName":"qa","teamName":"' + TEAM + '"}\n{partial');
    const id = helper.readTranscriptIdentity(p, 131072);
    assert.strictEqual(id.agentName, 'qa'); // the one valid line still parses
    assert.strictEqual(id.teamName, TEAM);
    // Non-existent file → safe default, no throw.
    assert.deepStrictEqual(helper.readTranscriptIdentity(path.join(dir, 'nope.jsonl'), 131072), {
      agentNamePresent: false,
      agentName: null,
      teamName: null,
    });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('listTopLevelTranscripts lists *.jsonl with stems and skips non-jsonl / subdirs', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'uc37-ls-'));
  try {
    writeJsonl(dir, 'aaaa-1111.jsonl', [{ type: 'summary' }]);
    writeJsonl(dir, 'bbbb-2222.jsonl', [{ type: 'summary' }]);
    fs.writeFileSync(path.join(dir, 'notes.txt'), 'ignore me');
    fs.mkdirSync(path.join(dir, 'aaaa-1111')); // the subagents-parent dir — must be skipped
    const listed = helper.listTopLevelTranscripts(dir);
    const stems = listed.map((c) => c.stem).sort();
    assert.deepStrictEqual(stems, ['aaaa-1111', 'bbbb-2222']);
    for (const c of listed) {
      assert.ok(c.path.endsWith('.jsonl'));
      assert.strictEqual(typeof c.mtimeMs, 'number');
    }
    // Missing dir → empty list, no throw.
    assert.deepStrictEqual(helper.listTopLevelTranscripts(path.join(dir, 'does-not-exist')), []);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── end-to-end FS resolution (no fd) ────────────────────────

test('end-to-end — a shared slug dir resolves the right transcript by listing + identity, no fd', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'uc37-e2e-'));
  try {
    const orchStem = 'cafef00d-orch';
    writeJsonl(dir, orchStem + '.jsonl', [
      { type: 'summary', sessionId: orchStem },
      { type: 'user', sessionId: orchStem },
    ]);
    writeJsonl(dir, 'deadbeef-ana.jsonl', [
      { type: 'attachment', sessionId: 'deadbeef-ana', agentName: 'analyst', teamName: TEAM },
    ]);
    writeJsonl(dir, 'feedface-foreign.jsonl', [
      { type: 'attachment', sessionId: 'feedface-foreign', agentName: 'analyst', teamName: FOREIGN_TEAM },
    ]);

    const listing = helper.listTopLevelTranscripts(dir);

    // Tier-1 main: anchored by a teammate's parent-session-id == orchestrator stem.
    const mainResolved = helper.selectMainByParent(listing, new Set([orchStem]));
    assert.strictEqual(mainResolved, path.join(dir, orchStem + '.jsonl'));

    // Teammate target: anchored by (agentName, teamName), enriching the listing
    // with each file's sniffed identity — foreign analyst excluded by teamName.
    const enriched = listing.map((c) => {
      const id = helper.readTranscriptIdentity(c.path, 131072);
      return { path: c.path, mtimeMs: c.mtimeMs, agentName: id.agentName, teamName: id.teamName };
    });
    const teammateResolved = helper.selectAgentTranscript(enriched, 'analyst', TEAM);
    assert.strictEqual(teammateResolved, path.join(dir, 'deadbeef-ana.jsonl'));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
