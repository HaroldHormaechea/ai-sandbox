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
  assert.strictEqual(id.sessionId, null);
  // Empty / nullish input is tolerated. The identity shape now carries sessionId,
  // added by the entrypoint restart loop's `claude --session-id <uuid>` (the
  // tier-0 main anchor) — a plain cmdline still yields null for it.
  const ALL_NULL = { agentName: null, teamName: null, parentSessionId: null, sessionId: null };
  assert.deepStrictEqual(helper.parseClaudeIdentity(''), ALL_NULL);
  assert.deepStrictEqual(helper.parseClaudeIdentity(null), ALL_NULL);
});

// sessionId extraction — BOTH flag forms (current entrypoint stamps --session-id
// on the main pane's cmdline; this is the tier-0 anchor for the session-bleed fix).
test('parseClaudeIdentity extracts sessionId from the `--session-id <value>` form', () => {
  const sid = '7f3c1a90-0b2e-4d44-9c11-aa55bb66cc77';
  const cmdline = ['claude', '--session-id', sid, '--dangerously-skip-permissions', ''].join(NUL);
  const id = helper.parseClaudeIdentity(cmdline);
  assert.strictEqual(id.sessionId, sid);
  // A main pane carries no teammate identity.
  assert.strictEqual(id.agentName, null);
  assert.strictEqual(id.teamName, null);
  assert.strictEqual(id.parentSessionId, null);
});

test('parseClaudeIdentity extracts sessionId from the `--session-id=<value>` form', () => {
  const sid = 'feedface-1234-5678-9abc-def012345678';
  const cmdline = ['claude', '--session-id=' + sid, '--dangerously-skip-permissions', ''].join(NUL);
  const id = helper.parseClaudeIdentity(cmdline);
  assert.strictEqual(id.sessionId, sid);
  assert.strictEqual(id.agentName, null);
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

// ════════════════════════════════════════════════════════════════════════════
// UC — session-bleed fix (tier-0 sessionId-exact main anchoring)
//
// THE BUG: structured "conversation" mode showed a NEW session ANOTHER session's
// conversation. Root cause — a solo session's main transcript was resolved by
// newest-mtime in the SHARED (bind-mounted) ~/.claude slug dir, so it grabbed a
// foreign, more-recently-appended session's transcript.
//
// THE FIX: the entrypoint now launches `claude --session-id <uuid>`, so the main
// pane's cmdline carries its OWN session id. When a sessionId is present the main
// transcript is anchored to <session-id>.jsonl EXACTLY and ONLY — null on no-match
// (NO fallthrough to newest-mtime), so a freshly-started session shows a transient
// no-transcript state until its OWN transcript lands, never a foreign one.
// ════════════════════════════════════════════════════════════════════════════

// ──────────────────────── selectMainBySessionId (tier-0 exact anchoring) ────────────────────────

test('selectMainBySessionId — exact stem match returns that candidate', () => {
  const sid = 'aaaa-bbbb-cccc';
  const candidates = [
    { path: '/slug/' + sid + '.jsonl', stem: sid, mtimeMs: 100 },
    { path: '/slug/other.jsonl', stem: 'other', mtimeMs: 9999 }, // newer, but not our stem
  ];
  assert.strictEqual(helper.selectMainBySessionId(candidates, sid), '/slug/' + sid + '.jsonl');
});

test('selectMainBySessionId — NO match returns null (never falls through to newest-mtime)', () => {
  const candidates = [
    // Only foreign sessions exist; ours has not appeared yet. Both are NEWER than
    // anything we'd accept — selecting by mtime here is exactly the bleed bug.
    { path: '/slug/foreign-1.jsonl', stem: 'foreign-1', mtimeMs: 5000 },
    { path: '/slug/foreign-2.jsonl', stem: 'foreign-2', mtimeMs: 9999 },
  ];
  assert.strictEqual(helper.selectMainBySessionId(candidates, 'mine-not-here'), null);
  // Empty / nullish sessionId is also null (defensive — never anchors anything).
  assert.strictEqual(helper.selectMainBySessionId(candidates, null), null);
  assert.strictEqual(helper.selectMainBySessionId(candidates, ''), null);
  assert.strictEqual(helper.selectMainBySessionId([], 'mine-not-here'), null);
});

test('selectMainBySessionId — pathological duplicate stems → newest mtime among them', () => {
  const sid = 'dup-stem';
  const candidates = [
    { path: '/slug/dup-old.jsonl', stem: sid, mtimeMs: 10 },
    { path: '/slug/dup-new.jsonl', stem: sid, mtimeMs: 50 },
  ];
  assert.strictEqual(helper.selectMainBySessionId(candidates, sid), '/slug/dup-new.jsonl');
});

// ──────────────────────── selectMainTranscript (regime dispatch) ────────────────────────

// HEADLINE regression — the exact reported scenario. A new session's --session-id
// matches NO candidate, while a FOREIGN, agentName-absent, NEWER-mtime main file
// sits in the shared slug dir. The OLD newest-mtime logic would have routed that
// foreign transcript to the client (the bleed). With tier-0 it MUST be null.
test('HEADLINE regression — sessionId present but unmatched + newer foreign main file → null (no bleed)', () => {
  const candidates = [
    // Foreign session's main file: no agentName, NEWER mtime than anything of ours.
    // Under the buggy newest-mtime path this would be selected and leaked.
    { path: '/slug/foreign-orch.jsonl', stem: 'foreign-orch-sid', mtimeMs: 9999, agentNamePresent: false },
  ];
  const resolved = helper.selectMainTranscript(candidates, { sessionId: 'my-brand-new-sid' });
  assert.strictEqual(resolved, null, 'a new session must NOT adopt a foreign session\'s newer transcript');
});

// own-file-exists — sessionId present AND matching returns OUR transcript even when
// a foreign file has a strictly newer mtime. Proves the anchor is stem-exact, not
// mtime-ranked, once our own file is present.
test('selectMainTranscript — sessionId matches own file; foreign newer-mtime file is ignored', () => {
  const mySid = 'my-own-sid';
  const candidates = [
    { path: '/slug/' + mySid + '.jsonl', stem: mySid, mtimeMs: 100, agentNamePresent: false }, // mine
    { path: '/slug/foreign.jsonl', stem: 'foreign-sid', mtimeMs: 9999, agentNamePresent: false }, // newer, foreign
  ];
  assert.strictEqual(
    helper.selectMainTranscript(candidates, { sessionId: mySid }),
    '/slug/' + mySid + '.jsonl',
  );
});

// sessionId present → selectMainBySessionId ONLY: parentSessionIds must be ignored
// entirely (no fallthrough through Tier-1 either).
test('selectMainTranscript — sessionId present takes precedence over any parentSessionIds match', () => {
  const mySid = 'sid-x';
  const parentStem = 'parent-y';
  const candidates = [
    { path: '/slug/parent-y.jsonl', stem: parentStem, mtimeMs: 9999, agentNamePresent: false },
    // our own file does NOT yet exist
  ];
  // Even though a parent-anchored candidate exists & is newer, sessionId regime
  // returns null because OUR sid is unmatched — it never consults parentSessionIds.
  assert.strictEqual(
    helper.selectMainTranscript(candidates, { sessionId: mySid, parentSessionIds: new Set([parentStem]) }),
    null,
  );
});

// old-entrypoint degradation — NO sessionId on the cmdline (pre-fix image). The
// unchanged two-tier path must still work: Tier 1 (selectMainByParent) then
// Tier 2 (selectMainNewestNoAgent). Backward-compatibility guard.
test('selectMainTranscript — no sessionId → Tier 1 parent-anchor wins when a parent stem matches', () => {
  const parentStem = 'orch-stem';
  const candidates = [
    { path: '/slug/orch-stem.jsonl', stem: parentStem, mtimeMs: 50, agentNamePresent: false },
    { path: '/slug/newer-noise.jsonl', stem: 'noise', mtimeMs: 9999, agentNamePresent: false }, // newer, not the parent
  ];
  assert.strictEqual(
    helper.selectMainTranscript(candidates, { parentSessionIds: new Set([parentStem]) }),
    '/slug/orch-stem.jsonl',
  );
});

test('selectMainTranscript — no sessionId, no parent match → Tier 2 newest agentName-absent file', () => {
  const candidates = [
    { path: '/slug/teammate.jsonl', stem: 't', mtimeMs: 9999, agentNamePresent: true }, // excluded (has agentName)
    { path: '/slug/main-stale.jsonl', stem: 'm1', mtimeMs: 100, agentNamePresent: false },
    { path: '/slug/main-live.jsonl', stem: 'm2', mtimeMs: 500, agentNamePresent: false }, // newest main
  ];
  // Empty parent set → Tier 1 declines → Tier 2 picks the newest agentName-absent file.
  assert.strictEqual(
    helper.selectMainTranscript(candidates, { parentSessionIds: new Set() }),
    '/slug/main-live.jsonl',
  );
  // Absent opts entirely is tolerated and behaves as the no-sessionId regime.
  assert.strictEqual(helper.selectMainTranscript(candidates, undefined), '/slug/main-live.jsonl');
});

// ──────────────────────── end-to-end FS — tier-0 isolation in a shared slug dir ────────────────────────

test('end-to-end — shared slug dir, new session resolves null until its OWN transcript appears', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'uc-sid-'));
  try {
    // A foreign solo session already wrote a main transcript with a NEWER mtime.
    const foreignStem = 'foreign-session-id';
    writeJsonl(dir, foreignStem + '.jsonl', [
      { type: 'summary', sessionId: foreignStem },
      { type: 'user', sessionId: foreignStem },
    ]);

    const mySid = 'my-session-id';

    // Phase 1 — our transcript does NOT exist yet. Listing sees only the foreign
    // (newer) file. Tier-0 must return null — NOT the foreign transcript.
    let listing = helper.listTopLevelTranscripts(dir);
    assert.strictEqual(helper.selectMainTranscript(listing, { sessionId: mySid }), null,
      'before our transcript lands we must show no-transcript, never the foreign one');

    // Phase 2 — our own transcript now appears (claude wrote it). It is OLDER in
    // mtime than the foreign file but Tier-0 anchors by exact stem, so it wins.
    const myPath = writeJsonl(dir, mySid + '.jsonl', [
      { type: 'summary', sessionId: mySid },
      { type: 'user', sessionId: mySid },
    ]);
    // Force the foreign file to be the newer of the two (defeats any mtime tiebreak).
    const future = fs.statSync(myPath).mtime.getTime() + 60_000;
    fs.utimesSync(path.join(dir, foreignStem + '.jsonl'), new Date(future), new Date(future));

    listing = helper.listTopLevelTranscripts(dir);
    assert.strictEqual(helper.selectMainTranscript(listing, { sessionId: mySid }), myPath,
      'once our transcript exists, tier-0 anchors to it by exact stem despite the foreign newer mtime');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── --scan-pending sanity (live/integration note) ────────────────────────
//
// Required case 6 from the proposal: `--scan-pending` should return an idle /
// non-foreign result on an unmatched sessionId. This is NOT reachable as a pure
// unit: scanPending() → resolveTranscriptOnce() → resolvePanePid()/findClaudePid()
// depend on a live tmux server and /proc walk for the claude PID, neither of which
// exists in this stdlib-only harness. The underlying SELECTION it relies on is
// already proven dead-on by the tier-0 tests above (an unmatched sessionId yields
// null → resolveTranscriptForClaude returns null → scanPending prints `idle`,
// never a foreign transcript's tail). We therefore assert the reachable invariant
// here and flag the full --scan-pending path as a live/integration check.
test('scan-pending invariant — an unmatched sessionId yields null resolution (→ idle, not foreign)', () => {
  // This is the exact decision scanPending() bottoms out on for a brand-new
  // session whose own transcript has not yet appeared in the shared slug dir.
  const candidates = [
    { path: '/slug/foreign-a.jsonl', stem: 'foreign-a', mtimeMs: 7000, agentNamePresent: false },
    { path: '/slug/foreign-b.jsonl', stem: 'foreign-b', mtimeMs: 9999, agentNamePresent: false },
  ];
  assert.strictEqual(helper.selectMainTranscript(candidates, { sessionId: 'unmatched-new-sid' }), null);
});

// ════════════════════════════════════════════════════════════════════════════
// UC-40 — stranded-question live delivery (idle-flush + backfill partial fix)
//
// THE BUG: in the structured (non-tmux) conversation view the assistant message
// that carries an `AskUserQuestion` (and the equivalent `ExitPlanMode` approval)
// never appeared until the user answered in tmux. `claude` writes that assistant
// line then BLOCKS awaiting the answer WITHOUT a trailing newline, so the helper —
// which only emits newline-terminated lines — stranded it in `residual` with no
// idle drain. It appeared "retroactively" only when the next turn supplied the
// newline. A second, latent bug: `backfill` advanced the offset to the last
// newline (not buf.length), so a connect-time trailing partial would be re-read
// and DUPLICATED by the next readNewLines.
//
// THE FIX (exported seams, all driven here against REAL temp files, no mocks):
//   • readNewLines(reader, nowMs) — emits only \n-terminated lines; stamps
//     residualSinceMs only when new bytes actually arrive; suppresses exactly one
//     newline-terminated copy equal to a previously idle-flushed line (one-shot).
//   • idleFlush(reader, nowMs)    — returns the residual ONCE, only when it is
//     non-empty, unchanged for >= FLUSH_IDLE_MS, AND parses as a complete JSON
//     object; never mutates offset/residual.
//   • backfill(reader, n, nowMs)  — sets offset = buf.length so the held trailing
//     partial is never double-read.
// nowMs is threaded everywhere so idle timing is DETERMINISTIC — no sleeps.
// ════════════════════════════════════════════════════════════════════════════

const SESS = 'sess-uc40';

// One-line transcript builders (each returns a single JSON string, no newline).
const userLine = (t) => JSON.stringify({ type: 'user', message: { role: 'user', content: t }, sessionId: SESS });
const turnEndLine = (n) => JSON.stringify({ type: 'system', subtype: 'turn_duration', durationMs: n || 1234, sessionId: SESS });
const assistantTextLine = (t) =>
  JSON.stringify({ type: 'assistant', message: { role: 'assistant', content: [{ type: 'text', text: t }] }, sessionId: SESS });
// Assistant message bundling a text block + an AskUserQuestion tool_use in the
// SAME content[] array — the exact shape that disappeared (AC4).
const assistantQuestionLine = (t) =>
  JSON.stringify({
    type: 'assistant',
    message: {
      role: 'assistant',
      content: [
        { type: 'text', text: t || 'Which option do you prefer?' },
        {
          type: 'tool_use',
          id: 'toolu_q',
          name: 'AskUserQuestion',
          input: { questions: [{ question: 'A or B?', header: 'Choice', options: [{ label: 'A' }, { label: 'B' }] }] },
        },
      ],
    },
    sessionId: SESS,
  });
// Plan-mode approval prompt — same "assistant message that then blocks on input"
// mechanism (AC5).
const assistantExitPlanLine = (t) =>
  JSON.stringify({
    type: 'assistant',
    message: {
      role: 'assistant',
      content: [
        { type: 'text', text: t || 'Here is the plan' },
        { type: 'tool_use', id: 'toolu_p', name: 'ExitPlanMode', input: { plan: '1. do it\n2. ship it' } },
      ],
    },
    sessionId: SESS,
  });

const mkTmp = (pfx) => fs.mkdtempSync(path.join(os.tmpdir(), pfx));
// Join complete lines into a newline-terminated transcript body.
const body = (lines) => lines.join('\n') + '\n';

// Capture everything the helper writes to stdout during fn() (for backfill()),
// stripping the trailing newline `out()` appends. Synchronous — restores before
// the node:test reporter writes its own TAP output.
function captureOut(fn) {
  const orig = process.stdout.write;
  const lines = [];
  process.stdout.write = (chunk) => {
    lines.push(String(chunk).replace(/\n$/, ''));
    return true;
  };
  try {
    fn();
  } finally {
    process.stdout.write = orig;
  }
  return lines;
}

// Sanity: the constants the timing logic hinges on (=900, =3×POLL_MS).
test('UC-40 — FLUSH_IDLE_MS is 900 and equals 3×POLL_MS', () => {
  assert.strictEqual(helper.POLL_MS, 300);
  assert.strictEqual(helper.FLUSH_IDLE_MS, 900);
  assert.strictEqual(helper.FLUSH_IDLE_MS, 3 * helper.POLL_MS);
});

// ──────────────────────── 1. Headline (AC1/AC2/AC9) ────────────────────────

test('UC-40 AC1/AC2/AC9 — unterminated assistant+AskUserQuestion line: held, idle-flushed once, no double-emit', () => {
  const dir = mkTmp('uc40-headline-');
  try {
    const file = path.join(dir, 'main.jsonl');
    // Prior, newline-terminated turns already on disk.
    fs.writeFileSync(file, body([userLine('deploy please'), turnEndLine()]));
    const reader = helper.makeReader(file, 'main');
    const t0 = 1_000_000;
    // Backfill the existing complete lines; residual must be empty afterward.
    captureOut(() => helper.backfill(reader, 200, t0));
    assert.strictEqual(reader.residual, '', 'newline-terminated backfill leaves no residual');

    // claude writes the assistant message + its AskUserQuestion, then BLOCKS —
    // NO trailing newline yet.
    const qline = assistantQuestionLine('Which deploy target?');
    fs.appendFileSync(file, qline);

    // AC1/AC2: readNewLines emits NOTHING (no terminator) — the line is stranded.
    const t1 = t0 + helper.POLL_MS;
    assert.deepStrictEqual(helper.readNewLines(reader, t1), []);
    assert.strictEqual(reader.residual, qline);

    // Before the idle window elapses: not flushed.
    assert.strictEqual(helper.idleFlush(reader, t1 + helper.FLUSH_IDLE_MS - 1), null);

    // At the idle window: the stranded complete-JSON line is delivered live, ONCE.
    assert.strictEqual(helper.idleFlush(reader, t1 + helper.FLUSH_IDLE_MS), qline);
    // Still blocked, polled again → one-shot guard: no re-emit.
    assert.strictEqual(helper.idleFlush(reader, t1 + helper.FLUSH_IDLE_MS + helper.POLL_MS), null);
    // idleFlush NEVER mutates offset/residual.
    assert.strictEqual(reader.residual, qline);

    // The real newline finally arrives, followed by the next turn's line.
    const next = turnEndLine();
    fs.appendFileSync(file, '\n' + next + '\n');
    const emitted = helper.readNewLines(reader, t1 + 2 * helper.FLUSH_IDLE_MS);
    // The idle-flushed question's newline-terminated copy is suppressed exactly
    // once; only the genuinely-new line is emitted (no double-emit).
    assert.deepStrictEqual(emitted, [next]);
    assert.strictEqual(reader.pendingFlushed, null);
    assert.strictEqual(reader.residual, '');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── 2. complete-JSON-but-unterminated NOT emitted pre-idle ────────────────────────

test('UC-40 #2 — a complete-JSON but unterminated line is NOT emitted by readNewLines alone (pre-idle)', () => {
  const dir = mkTmp('uc40-noemit-');
  try {
    const file = path.join(dir, 'main.jsonl');
    const qline = assistantQuestionLine('still blocked');
    fs.writeFileSync(file, qline); // ONLY content, no trailing newline
    const reader = helper.makeReader(file, 'main');
    const t0 = 2_000_000;
    // No newline anywhere → readNewLines emits nothing, holds it in residual.
    assert.deepStrictEqual(helper.readNewLines(reader, t0), []);
    assert.strictEqual(reader.residual, qline);
    // And the idle window has NOT elapsed, so idleFlush also declines for now.
    assert.strictEqual(helper.idleFlush(reader, t0), null);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── 3. genuinely partial residual is NEVER idle-flushed ────────────────────────

test('UC-40 #3 — a genuinely partial/unparseable residual is NOT idle-flushed even after the window elapses', () => {
  const dir = mkTmp('uc40-partial-');
  try {
    const file = path.join(dir, 'main.jsonl');
    const half = '{"type":"assistant","message":{"role":"assistant","content":[{"a":1,'; // truncated mid-write
    fs.writeFileSync(file, half);
    const reader = helper.makeReader(file, 'main');
    const t0 = 3_000_000;
    assert.deepStrictEqual(helper.readNewLines(reader, t0), []);
    assert.strictEqual(reader.residual, half);
    // JSON.parse fails → idleFlush MUST decline, no matter how long it sits.
    assert.strictEqual(helper.idleFlush(reader, t0 + helper.FLUSH_IDLE_MS), null);
    assert.strictEqual(helper.idleFlush(reader, t0 + 100 * helper.FLUSH_IDLE_MS), null);
    assert.strictEqual(reader.pendingFlushed, null);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── 4. residualSinceMs resets while the writer grows the line ────────────────────────

test('UC-40 #4 — residualSinceMs resets on every grow; no premature flush mid-write', () => {
  const dir = mkTmp('uc40-grow-');
  try {
    const file = path.join(dir, 'main.jsonl');
    const reader = helper.makeReader(file, 'main');
    const t0 = 4_000_000;

    const full = assistantQuestionLine('mid-write');
    const cut = Math.floor(full.length / 2);
    const prefix = full.slice(0, cut);
    const suffix = full.slice(cut);
    // Guard the fixture: the prefix alone must be incomplete JSON.
    assert.throws(() => JSON.parse(prefix.trimEnd()), 'prefix fixture must be unparseable');

    // Poll 1 — writer has written the prefix only (no newline).
    fs.writeFileSync(file, prefix);
    assert.deepStrictEqual(helper.readNewLines(reader, t0), []);
    assert.strictEqual(reader.residualSinceMs, t0);

    // Poll 2 — writer appended the suffix, completing the object (still no newline).
    fs.appendFileSync(file, suffix);
    assert.deepStrictEqual(helper.readNewLines(reader, t0 + helper.POLL_MS), []);
    // The timer RESET to the second poll's clock because new bytes arrived.
    assert.strictEqual(reader.residualSinceMs, t0 + helper.POLL_MS);
    assert.strictEqual(reader.residual, full);

    // FLUSH_IDLE_MS measured from t0 has already elapsed here — but measured from
    // the RESET (t0+POLL_MS) it has NOT. The residual IS now complete JSON, so the
    // ONLY thing preventing a flush is the reset → proves no premature mid-write flush.
    assert.strictEqual(helper.idleFlush(reader, t0 + helper.FLUSH_IDLE_MS), null);

    // Once the writer goes quiet for a full window from the last grow, it flushes.
    assert.strictEqual(helper.idleFlush(reader, t0 + helper.POLL_MS + helper.FLUSH_IDLE_MS), full);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── 5. backfill secondary-bug regression (trailing partial held ONCE) ────────────────────────

test('UC-40 #5 — backfill of a no-trailing-newline file holds the partial ONCE, idle-flushable, no double-emit', () => {
  const dir = mkTmp('uc40-backfill-');
  try {
    const file = path.join(dir, 'main.jsonl');
    const l1 = userLine('hello');
    const l2 = assistantTextLine('working on it');
    const qline = assistantQuestionLine('connect-time pending question');
    // Complete lines + a trailing partial (the blocking question), NO final newline.
    fs.writeFileSync(file, body([l1, l2]) + qline);
    const reader = helper.makeReader(file, 'main');
    const t0 = 5_000_000;

    // backfill emits ONLY the complete lines — never the trailing partial.
    const emitted = captureOut(() => helper.backfill(reader, 200, t0));
    assert.deepStrictEqual(emitted, ['main\t' + l1, 'main\t' + l2]);

    // The fix: offset == buf.length (NOT the last-newline offset), partial held in
    // residual exactly once.
    const size = fs.statSync(file).size;
    assert.strictEqual(reader.offset, size, 'offset advanced to buf.length, not completeEnd');
    assert.strictEqual(reader.residual, qline);
    assert.strictEqual(reader.pendingFlushed, null);

    // No new bytes → readNewLines re-reads NOTHING (the pre-fix bug would re-read
    // [completeEnd,size) and DUPLICATE the partial).
    assert.deepStrictEqual(helper.readNewLines(reader, t0 + helper.POLL_MS), []);
    assert.strictEqual(reader.residual, qline);

    // The connect-time pending question becomes idle-flushable after the window.
    assert.strictEqual(helper.idleFlush(reader, t0), null); // window not elapsed
    assert.strictEqual(helper.idleFlush(reader, t0 + helper.FLUSH_IDLE_MS), qline);

    // The eventual newline + next line: the flushed copy is suppressed once; no
    // doubled/corrupt emit.
    const next = turnEndLine();
    fs.appendFileSync(file, '\n' + next + '\n');
    assert.deepStrictEqual(helper.readNewLines(reader, t0 + 2 * helper.FLUSH_IDLE_MS), [next]);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── 6. ExitPlanMode (AC5) ────────────────────────

test('UC-40 AC5 — an unterminated ExitPlanMode approval line flushes live the same way', () => {
  const dir = mkTmp('uc40-plan-');
  try {
    const file = path.join(dir, 'main.jsonl');
    fs.writeFileSync(file, body([userLine('plan it')]));
    const reader = helper.makeReader(file, 'main');
    const t0 = 6_000_000;
    captureOut(() => helper.backfill(reader, 200, t0));

    const planLine = assistantExitPlanLine('proposed plan');
    fs.appendFileSync(file, planLine); // blocking, no newline

    const t1 = t0 + helper.POLL_MS;
    assert.deepStrictEqual(helper.readNewLines(reader, t1), []);
    assert.strictEqual(helper.idleFlush(reader, t1 + helper.FLUSH_IDLE_MS - 1), null);
    assert.strictEqual(helper.idleFlush(reader, t1 + helper.FLUSH_IDLE_MS), planLine);

    // Newline lands later → suppressed once, no double-emit.
    fs.appendFileSync(file, '\n');
    assert.deepStrictEqual(helper.readNewLines(reader, t1 + 2 * helper.FLUSH_IDLE_MS), []);
    assert.strictEqual(reader.pendingFlushed, null);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── 7. Subagent / isSidechain (AC7) ────────────────────────

test('UC-40 AC7 — a subagent reader (source subagent:<id>) gets the same live idle delivery', () => {
  const dir = mkTmp('uc40-sub-');
  try {
    const file = path.join(dir, 'agent-abc123.jsonl');
    const reader = helper.makeReader(file, 'subagent:abc123');
    assert.strictEqual(reader.source, 'subagent:abc123');
    const t0 = 7_000_000;

    const qline = assistantQuestionLine('teammate needs a decision');
    fs.writeFileSync(file, qline); // blocking, no newline
    assert.deepStrictEqual(helper.readNewLines(reader, t0), []);
    // Same idle-flush path applies regardless of source.
    assert.strictEqual(helper.idleFlush(reader, t0 + helper.FLUSH_IDLE_MS), qline);
    // Source is untouched — streamLoop prefixes `subagent:abc123\t<line>` on emit.
    assert.strictEqual(reader.source, 'subagent:abc123');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ──────────────────────── 8. No regression (AC8) + rotation reset ────────────────────────

test('UC-40 AC8 — normal newline-terminated turns stream unaffected (no spurious suppression)', () => {
  const dir = mkTmp('uc40-normal-');
  try {
    const file = path.join(dir, 'main.jsonl');
    fs.writeFileSync(file, ''); // start empty, stream live
    const reader = helper.makeReader(file, 'main');
    const t0 = 8_000_000;

    // First two complete turns arrive together.
    const a = userLine('q1');
    const b = assistantTextLine('a1');
    fs.appendFileSync(file, body([a, b]));
    assert.deepStrictEqual(helper.readNewLines(reader, t0), [a, b]);
    assert.strictEqual(reader.residual, '');
    assert.strictEqual(reader.pendingFlushed, null);

    // A third complete turn streams on the next poll — nothing suppressed.
    const c = turnEndLine();
    fs.appendFileSync(file, body([c]));
    assert.deepStrictEqual(helper.readNewLines(reader, t0 + helper.POLL_MS), [c]);

    // A legitimately repeated identical line is NOT suppressed (suppression is a
    // one-shot keyed on an idle flush, which never happened here).
    fs.appendFileSync(file, body([c]));
    assert.deepStrictEqual(helper.readNewLines(reader, t0 + 2 * helper.POLL_MS), [c]);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('UC-40 AC8 — rotation/truncation reset clears the idle-flush fields (no stale suppression)', () => {
  const dir = mkTmp('uc40-rotate-');
  try {
    const file = path.join(dir, 'main.jsonl');
    // A deliberately LARGE original so the rotated single-line file is strictly
    // smaller (st.size < reader.offset → triggers the truncation-reset branch).
    fs.writeFileSync(
      file,
      body([
        assistantQuestionLine('old-1'),
        assistantQuestionLine('old-2'),
        assistantTextLine('old-3'),
        turnEndLine(),
      ]),
    );
    const reader = helper.makeReader(file, 'main');
    const t0 = 9_000_000;
    helper.readNewLines(reader, t0); // sets ino + offset == original size
    const origOffset = reader.offset;
    assert.ok(origOffset > 0);

    // Simulate a prior idle flush still on the books for THIS reader (committed
    // model: pendingFlushed HOLDS the flushed residual string, null when clear).
    const stale = assistantQuestionLine('stale-flushed-question');
    reader.pendingFlushed = stale;
    reader.residual = 'leftover-junk';

    // Rotation: the entrypoint restart loop spawned a fresh claude → a brand-new,
    // SMALLER transcript that happens to contain a line equal to the stale one.
    // rm+recreate also changes the inode, the other reset trigger.
    fs.rmSync(file);
    fs.writeFileSync(file, body([stale]));
    assert.ok(fs.statSync(file).size < origOffset, 'rotated file must be smaller to drive the reset');
    const emitted = helper.readNewLines(reader, t0 + helper.POLL_MS);

    // The reset cleared pendingFlushed, so the new line is NOT wrongly suppressed
    // by a stale flush record.
    assert.deepStrictEqual(emitted, [stale]);
    assert.strictEqual(reader.pendingFlushed, null);
    assert.strictEqual(reader.residual, '');
    assert.strictEqual(reader.offset, fs.statSync(file).size);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ════════════════════════════════════════════════════════════════════════════
// UC-41 — collectToolDetail (the exported pure seam behind `--fetch-detail`):
// given the main + subagent transcript line sets, return the matched
// `<source>\t<raw>` envelope lines for ONE toolUseId — the tool_use block whose
// `id` matches (→ full input) and the tool_result block whose `tool_use_id`
// matches (→ full result). Lines are kept WHOLE (valid JSON) so the server can
// parse them; total emitted bytes are bounded by maxBytes. No mocks, no fs.
// ════════════════════════════════════════════════════════════════════════════

const toolUseLine = (id, name, input) =>
  JSON.stringify({
    type: 'assistant',
    message: { role: 'assistant', content: [{ type: 'tool_use', id, name, input }] },
    sessionId: SESS,
  });
const toolResultLine = (toolUseId, content, isError) =>
  JSON.stringify({
    type: 'user',
    message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: toolUseId, is_error: !!isError, content }] },
    sessionId: SESS,
  });

test('UC-41 — collectToolDetail constants are stable', () => {
  assert.strictEqual(helper.CTRL_DETAIL_NOT_FOUND, 'detail-not-found');
  assert.strictEqual(helper.DETAIL_OUTPUT_CAP_BYTES, 65536);
});

test('UC-41 — collectToolDetail matches BOTH the tool_use and its tool_result by id', () => {
  const use = toolUseLine('tu1', 'Bash', { command: 'ls -la /workspace' });
  const result = toolResultLine('tu1', 'total 0\ndrwxr-xr-x', false);
  const noise = toolUseLine('tuOTHER', 'Edit', { file_path: '/x' });
  const sources = [{ source: 'main', lines: [use, noise, result] }];

  const matched = helper.collectToolDetail('tu1', sources);

  assert.strictEqual(matched.length, 2);
  // Both carry the `main\t<raw>` envelope; the noise line is excluded.
  assert.ok(matched.every((l) => l.startsWith('main\t')));
  assert.ok(matched.some((l) => l.includes('ls -la /workspace')));
  assert.ok(matched.some((l) => l.includes('drwxr-xr-x')));
  assert.ok(!matched.some((l) => l.includes('tuOTHER')));
});

test('UC-41 — collectToolDetail returns [] when the id is not found (drives detail-not-found)', () => {
  const sources = [{ source: 'main', lines: [toolUseLine('tuA', 'Bash', { command: 'pwd' })] }];
  assert.deepStrictEqual(helper.collectToolDetail('tuMISSING', sources), []);
});

test('UC-41 — collectToolDetail stamps the subagent source onto a teammate match', () => {
  const sources = [
    { source: 'main', lines: [toolUseLine('tuMain', 'Bash', { command: 'echo main' })] },
    { source: 'subagent:agent-7', lines: [toolUseLine('tuSub', 'Skill', { skill: 'verify' })] },
  ];

  const matched = helper.collectToolDetail('tuSub', sources);

  assert.strictEqual(matched.length, 1);
  assert.ok(matched[0].startsWith('subagent:agent-7\t'), `expected subagent source prefix, got ${matched[0]}`);
  assert.ok(matched[0].includes('verify'));
});

test('UC-41 — collectToolDetail keeps lines WHOLE and stops once the byte cap would be exceeded', () => {
  const big = 'X'.repeat(2000);
  const first = toolResultLine('tuCap', big, false); // ~2 KB, kept (first is always kept)
  const second = toolUseLine('tuCap', 'Bash', { command: big }); // would push past a 3 KB cap → dropped
  const sources = [{ source: 'main', lines: [first, second] }];

  const matched = helper.collectToolDetail('tuCap', sources, 3000);

  // The first matched line is kept whole; the second is dropped by the flood-guard.
  assert.strictEqual(matched.length, 1);
  assert.ok(matched[0].includes(big));
  // It is intact JSON after the envelope (never truncated mid-line).
  const raw = matched[0].slice(matched[0].indexOf('\t') + 1);
  assert.doesNotThrow(() => JSON.parse(raw));
});

test('UC-41 — collectToolDetail is robust to bad input', () => {
  assert.deepStrictEqual(helper.collectToolDetail(null, [{ source: 'main', lines: [] }]), []);
  assert.deepStrictEqual(helper.collectToolDetail('tu1', null), []);
  // Malformed JSON lines are skipped, not thrown.
  assert.deepStrictEqual(helper.collectToolDetail('tu1', [{ source: 'main', lines: ['not-json', '{ broken'] }]), []);
});

// ════════════════════════════════════════════════════════════════════════════
// UC-42 — collectToolDetail also correlates a harness-INJECTED user line (a Skill
// SKILL.md body) to its host Skill `toolUseId`. The injected line carries the host
// id at the TOP LEVEL as `sourceToolUseID` (NOT inside a tool_use/tool_result
// block), so `--fetch-detail` re-reads the actual skill body as the bubble's detail
// (AC2 plumbing).
// ════════════════════════════════════════════════════════════════════════════

// An injected SKILL.md body: top-level sourceToolUseID, NO tool_use/tool_result block.
const injectedSkillBodyLine = (sourceToolUseID, body) =>
  JSON.stringify({
    type: 'user',
    sourceToolUseID,
    message: { role: 'user', content: body },
    sessionId: SESS,
  });

test('UC-42 — collectToolDetail correlates a top-level sourceToolUseID line to the Skill toolUseId', () => {
  const use = toolUseLine('tuSkill', 'Skill', { skill: 'deep-research' });
  const injected = injectedSkillBodyLine('tuSkill', '# deep-research\nfull SKILL.md body here');
  const noise = injectedSkillBodyLine('tuOTHER', 'a different skill body');
  const sources = [{ source: 'main', lines: [use, injected, noise] }];

  const matched = helper.collectToolDetail('tuSkill', sources);

  // Both the Skill tool_use AND its correlated injected body are returned; noise excluded.
  assert.strictEqual(matched.length, 2);
  assert.ok(matched.every((l) => l.startsWith('main\t')));
  assert.ok(matched.some((l) => l.includes('deep-research') && l.includes('tool_use')));
  assert.ok(matched.some((l) => l.includes('full SKILL.md body here')));
  assert.ok(!matched.some((l) => l.includes('a different skill body')));
});

test('UC-42 — collectToolDetail stamps the subagent source onto a correlated injected body', () => {
  const sources = [
    { source: 'main', lines: [toolUseLine('tuMain', 'Bash', { command: 'echo hi' })] },
    {
      source: 'subagent:agent-7',
      lines: [
        toolUseLine('tuSub', 'Skill', { skill: 'verify' }),
        injectedSkillBodyLine('tuSub', 'teammate skill body'),
      ],
    },
  ];

  const matched = helper.collectToolDetail('tuSub', sources);

  // AC9 — both lines fold under the teammate's own source, never the main pane.
  assert.strictEqual(matched.length, 2);
  assert.ok(matched.every((l) => l.startsWith('subagent:agent-7\t')));
  assert.ok(matched.some((l) => l.includes('teammate skill body')));
});

// ════════════════════════════════════════════════════════════════════════════
// UC-47 — conversation-name derivation seams
//
// The `--conversation-name` one-shot resolves the MAIN pane's active transcript
// and prints a single derived name (or empty). The runtime resolve/exec path is
// not exercisable without a live container, but the DERIVATION is pure and is
// the actual anti-regression surface — the original UC-37 fetch-detail helper's
// array-only guard (`if (!Array.isArray(content)) continue`) would have made the
// PRIMARY case (string content, ~253/263 real lines) ALWAYS return empty. These
// tests pin the tiered derivation, the structural skip-classifier, and the
// codepoint cap, and ALSO run the real seam against on-disk transcripts.
// ════════════════════════════════════════════════════════════════════════════

// ── builders mirroring the real transcript line shapes ──
const summaryLine = (s) => JSON.stringify({ type: 'summary', summary: s });
// String-content user prompt — the COMMON (253/263) case.
const userStringLine = (t) => JSON.stringify({ type: 'user', message: { role: 'user', content: t } });
// Array-content user prompt — text blocks concatenated.
const userArrayLine = (...texts) =>
  JSON.stringify({
    type: 'user',
    message: { role: 'user', content: texts.map((t) => ({ type: 'text', text: t })) },
  });
const assistantLine = (t) =>
  JSON.stringify({ type: 'assistant', message: { role: 'assistant', content: [{ type: 'text', text: t }] } });

// ── sanitizeOneLine ──
test('UC-47 sanitizeOneLine collapses any whitespace run to a single space and trims', () => {
  assert.strictEqual(helper.sanitizeOneLine('  hello\n\tworld   \n  again '), 'hello world again');
  assert.strictEqual(helper.sanitizeOneLine('single'), 'single');
  assert.strictEqual(helper.sanitizeOneLine('   \n\t  '), '');
  assert.strictEqual(helper.sanitizeOneLine(undefined), '');
  assert.strictEqual(helper.sanitizeOneLine(42), '');
});

// ── capCodepoints (AC5) ──
test('UC-47 capCodepoints truncates by codepoint and never splits a surrogate pair', () => {
  assert.strictEqual(helper.capCodepoints('abcdef', 3), 'abc');
  assert.strictEqual(helper.capCodepoints('abc', 5), 'abc'); // under the cap, unchanged
  // Astral-plane emoji are 2 UTF-16 units each but 1 codepoint — capping at 2
  // must yield exactly 2 whole emoji, never a lone surrogate half.
  const emoji = '😀😁😂😃';
  const capped = helper.capCodepoints(emoji, 2);
  assert.strictEqual(Array.from(capped).length, 2);
  assert.strictEqual(capped, '😀😁');
  // The capped string must remain well-formed (no lone surrogate).
  assert.ok(!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(capped));
});

test('UC-47 CONVERSATION_NAME_MAX_CP is 120 and finalizeName caps at it (emoji-safe)', () => {
  assert.strictEqual(helper.CONVERSATION_NAME_MAX_CP, 120);
  const longAscii = 'x'.repeat(200);
  assert.strictEqual(Array.from(helper.finalizeName(longAscii)).length, 120);
  const longEmoji = '😀'.repeat(200);
  const cappedEmoji = helper.finalizeName(longEmoji);
  assert.strictEqual(Array.from(cappedEmoji).length, 120);
  assert.ok(!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(cappedEmoji));
});

// ── finalizeName ──
test('UC-47 finalizeName returns null for empty/whitespace-only input', () => {
  assert.strictEqual(helper.finalizeName(''), null);
  assert.strictEqual(helper.finalizeName('   \n\t '), null);
  assert.strictEqual(helper.finalizeName('  kept  '), 'kept');
});

// ── isNonPromptUserLine classifier ──
test('UC-47 isNonPromptUserLine — a plain string-content user prompt is a REAL prompt', () => {
  assert.strictEqual(helper.isNonPromptUserLine({ type: 'user', message: { content: 'real prompt' } }), false);
});

test('UC-47 isNonPromptUserLine skips a folded harness/Skill body (sourceToolUseID set)', () => {
  assert.strictEqual(
    helper.isNonPromptUserLine({ sourceToolUseID: 'tu_123', message: { content: 'folded body' } }),
    true,
  );
});

test('UC-47 isNonPromptUserLine skips an isMeta system note', () => {
  assert.strictEqual(helper.isNonPromptUserLine({ isMeta: true, message: { content: 'meta note' } }), true);
});

test('UC-47 isNonPromptUserLine skips a tool_result-carrying array line', () => {
  const o = { message: { content: [{ type: 'tool_result', content: 'output' }] } };
  assert.strictEqual(helper.isNonPromptUserLine(o), true);
});

test('UC-47 isNonPromptUserLine skips a slash-command wrapper and a local-command-stdout echo', () => {
  const cmd = {
    message: { content: '<command-name>/foo</command-name>\n<command-args>bar</command-args>' },
  };
  assert.strictEqual(helper.isNonPromptUserLine(cmd), true);
  const stdout = { message: { content: '<local-command-stdout>some output</local-command-stdout>' } };
  assert.strictEqual(helper.isNonPromptUserLine(stdout), true);
});

test('UC-47 isNonPromptUserLine treats a null/absent object defensively as non-prompt', () => {
  assert.strictEqual(helper.isNonPromptUserLine(null), true);
  assert.strictEqual(helper.isNonPromptUserLine(undefined), true);
});

// ── extractUserText (the anti-regression core) ──
test('UC-47 extractUserText reads STRING content DIRECTLY (the 253/263 common case)', () => {
  // This is the exact case the old array-only guard would have dropped.
  assert.strictEqual(helper.extractUserText('hello world'), 'hello world');
});

test('UC-47 extractUserText concatenates the text blocks of ARRAY content', () => {
  const content = [
    { type: 'text', text: 'first' },
    { type: 'tool_use', name: 'Bash' }, // non-text block ignored
    { type: 'text', text: 'second' },
  ];
  assert.strictEqual(helper.extractUserText(content), 'first\nsecond');
});

test('UC-47 extractUserText returns empty for a non-string/non-array shape', () => {
  assert.strictEqual(helper.extractUserText(undefined), '');
  assert.strictEqual(helper.extractUserText({ foo: 'bar' }), '');
});

// ── deriveConversationName — tiered ──
test('UC-47 deriveConversationName PRIMARY — first real STRING-content user prompt wins', () => {
  const lines = [
    assistantLine('assistant noise'),
    userStringLine('Refactor the SessionRow to show the conversation name'),
    userStringLine('a later prompt that must NOT win'),
  ];
  assert.strictEqual(
    helper.deriveConversationName(lines),
    'Refactor the SessionRow to show the conversation name',
  );
});

test('UC-47 deriveConversationName reads an array-text-block first prompt', () => {
  const lines = [userArrayLine('Add a ', 'conversation name field')];
  assert.strictEqual(helper.deriveConversationName(lines), 'Add a\nconversation name field'.replace(/\s+/g, ' '));
});

test('UC-47 deriveConversationName skips meta/command/stdout/tool_result/folded lines to the first REAL prompt', () => {
  const lines = [
    JSON.stringify({ type: 'user', isMeta: true, message: { content: 'meta' } }),
    JSON.stringify({ type: 'user', message: { content: '<command-name>/clear</command-name>\n<command-args></command-args>' } }),
    JSON.stringify({ type: 'user', message: { content: '<local-command-stdout>stdout</local-command-stdout>' } }),
    JSON.stringify({ type: 'user', sourceToolUseID: 'tu_9', message: { content: 'folded skill body' } }),
    JSON.stringify({ type: 'user', message: { content: [{ type: 'tool_result', content: 'res' }] } }),
    userStringLine('the actual first human prompt'),
  ];
  assert.strictEqual(helper.deriveConversationName(lines), 'the actual first human prompt');
});

test('UC-47 deriveConversationName tier-0 — newest summary beats the first user prompt', () => {
  const lines = [
    userStringLine('the first user prompt'),
    summaryLine('Conversation about UC-47'),
  ];
  assert.strictEqual(helper.deriveConversationName(lines), 'Conversation about UC-47');
});

test('UC-47 deriveConversationName tier-0 — the LAST (newest) summary wins among several', () => {
  const lines = [
    summaryLine('older summary'),
    userStringLine('a prompt'),
    summaryLine('newest summary'),
  ];
  assert.strictEqual(helper.deriveConversationName(lines), 'newest summary');
});

test('UC-47 deriveConversationName — an empty/whitespace extraction is skipped, the next real prompt wins', () => {
  const lines = [
    userStringLine('   '), // sanitizes to empty → keep scanning
    userArrayLine(''), // empty text block → empty → keep scanning
    userStringLine('finally a real one'),
  ];
  assert.strictEqual(helper.deriveConversationName(lines), 'finally a real one');
});

test('UC-47 deriveConversationName tier-2 — no transcript / no real prompt → null', () => {
  assert.strictEqual(helper.deriveConversationName([]), null);
  assert.strictEqual(helper.deriveConversationName(null), null);
  // Only assistant + non-prompt user lines → null (row falls back to tmuxTitle, AC3).
  const lines = [
    assistantLine('only assistant text'),
    JSON.stringify({ type: 'user', isMeta: true, message: { content: 'meta' } }),
  ];
  assert.strictEqual(helper.deriveConversationName(lines), null);
});

test('UC-47 deriveConversationName tolerates a malformed JSON line without throwing', () => {
  const lines = ['{not valid json', userStringLine('survives the bad line')];
  assert.strictEqual(helper.deriveConversationName(lines), 'survives the bad line');
});

test('UC-47 deriveConversationName caps a pathologically long first prompt at 120 codepoints (AC5)', () => {
  const lines = [userStringLine('y'.repeat(500))];
  const name = helper.deriveConversationName(lines);
  assert.strictEqual(Array.from(name).length, 120);
});

// ── Corpus check: run the PURE seam against REAL transcripts on disk ──
// Not an assertion-heavy test — it proves the derivation produces sane,
// non-empty names on the actual ~/.claude/projects corpus (no docker needed)
// and surfaces a few examples in the test output for the QA report.
test('UC-47 deriveConversationName produces sane names on REAL on-disk transcripts', () => {
  const os = require('node:os');
  const fs = require('node:fs');
  const path = require('node:path');
  const projectsRoot = path.join(os.homedir(), '.claude', 'projects');
  if (!fs.existsSync(projectsRoot)) {
    console.log('  [corpus] ~/.claude/projects absent — skipping live-corpus check');
    return; // environment-gated, never a hard failure
  }
  const jsonls = [];
  const walk = (dir, depth) => {
    if (depth > 2 || jsonls.length >= 12) return;
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
    for (const e of entries) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p, depth + 1);
      else if (e.isFile() && e.name.endsWith('.jsonl')) jsonls.push(p);
      if (jsonls.length >= 12) return;
    }
  };
  walk(projectsRoot, 0);
  if (jsonls.length === 0) {
    console.log('  [corpus] no .jsonl transcripts found — skipping');
    return;
  }
  let withName = 0;
  const examples = [];
  for (const file of jsonls) {
    let lines;
    try {
      lines = fs.readFileSync(file, 'utf8').split('\n').filter((l) => l.length > 0);
    } catch (e) {
      continue;
    }
    const name = helper.deriveConversationName(lines);
    if (name) {
      withName++;
      // Invariants on every derived name: single line, within the cap.
      assert.ok(!name.includes('\n'), 'derived name must be single-line');
      assert.ok(Array.from(name).length <= helper.CONVERSATION_NAME_MAX_CP, 'derived name within codepoint cap');
      if (examples.length < 5) examples.push(`${path.basename(file)} → ${JSON.stringify(name)}`);
    }
  }
  console.log(`  [corpus] derived a name for ${withName}/${jsonls.length} real transcripts. Examples:`);
  for (const ex of examples) console.log(`    ${ex}`);
  // At least one real transcript should yield a name — proves the PRIMARY
  // string-content path fires on the real corpus (the anti-regression point).
  assert.ok(withName > 0, 'expected at least one real transcript to derive a conversation name');
});

// ════════════════════════════════════════════════════════════════════════════
// UC-48 — deriveWorking: the binary working/idle list-row signal (pure seam).
//
// working=true iff there is ≥1 transcript entry AND the last MEANINGFUL entry is
// NOT a {type:"system",subtype:"turn_duration"} turn-end; a pending
// AskUserQuestion (after the last turn-end) is at-rest IDLE; empty/no-input is
// idle. Reuses the UC-40 line builders (userLine / turnEndLine /
// assistantTextLine / assistantQuestionLine) defined above.
// ════════════════════════════════════════════════════════════════════════════

test('UC-48 deriveWorking — empty / non-array input is idle (false)', () => {
  assert.strictEqual(helper.deriveWorking([]), false);
  assert.strictEqual(helper.deriveWorking(null), false);
  assert.strictEqual(helper.deriveWorking(undefined), false);
  assert.strictEqual(helper.deriveWorking('not-an-array'), false);
});

test('UC-48 deriveWorking — a turn-end as the last entry is idle (turn complete)', () => {
  const lines = [userLine('do the thing'), assistantTextLine('on it'), turnEndLine()];
  assert.strictEqual(helper.deriveWorking(lines), false);
});

test('UC-48 deriveWorking — mid-flight (last entry is not a turn-end) is working', () => {
  // A turn started (user prompt) and the assistant is producing output with no
  // turn-end yet → working.
  const lines = [userLine('do the thing'), assistantTextLine('working on it')];
  assert.strictEqual(helper.deriveWorking(lines), true);
});

test('UC-48 deriveWorking — a fresh turn after a previous turn-end is working again', () => {
  const lines = [
    userLine('first'),
    assistantTextLine('done first'),
    turnEndLine(),
    userLine('second'),
    assistantTextLine('mid second turn'),
  ];
  assert.strictEqual(helper.deriveWorking(lines), true);
});

test('UC-48 deriveWorking — a pending AskUserQuestion is at-rest IDLE (conversation-view parity)', () => {
  // The assistant asked a question and is BLOCKED awaiting the answer → idle.
  const lines = [userLine('start'), assistantQuestionLine('A or B?')];
  assert.strictEqual(helper.deriveWorking(lines), false);
});

test('UC-48 deriveWorking — an ANSWERED question (turn-end after it) is idle', () => {
  const lines = [userLine('start'), assistantQuestionLine('A or B?'), turnEndLine()];
  assert.strictEqual(helper.deriveWorking(lines), false);
});

test('UC-48 deriveWorking — work resumed after a question was answered is working', () => {
  const lines = [
    userLine('start'),
    assistantQuestionLine('A or B?'),
    turnEndLine(),
    userLine('A'),
    assistantTextLine('continuing with A'),
  ];
  assert.strictEqual(helper.deriveWorking(lines), true);
});

test('UC-48 deriveWorking — tolerates a malformed JSON line without throwing', () => {
  const lines = ['{ this is not json', userLine('start'), assistantTextLine('mid turn')];
  assert.strictEqual(helper.deriveWorking(lines), true);
});

// Anti-regression — the one-shot emits TWO lines from the SAME readAllLines:
// line1 = deriveConversationName(lines) || '', line2 = deriveWorking ? 'working'
// : 'idle'. We assert the two seams agree on the same input (the exact pair the
// production conversationName() prints). The in-container exec wiring of out()
// is live-verify scope; this pins the data contract.
test('UC-48 — name and working seams produce the expected (line1, line2) pair from one input', () => {
  const lines = [userLine('Refactor the SessionRow'), assistantTextLine('mid turn')];
  const line1 = helper.deriveConversationName(lines) || '';
  const line2 = helper.deriveWorking(lines) ? 'working' : 'idle';
  assert.strictEqual(line1, 'Refactor the SessionRow');
  assert.strictEqual(line2, 'working');

  // And an idle, completed turn → name still present, line2 idle.
  const idleLines = [userLine('Refactor the SessionRow'), assistantTextLine('done'), turnEndLine()];
  assert.strictEqual(helper.deriveConversationName(idleLines) || '', 'Refactor the SessionRow');
  assert.strictEqual(helper.deriveWorking(idleLines) ? 'working' : 'idle', 'idle');
});

test('UC-48 deriveWorking — optional live-corpus smoke over real transcripts (env-gated)', () => {
  const os = require('node:os');
  const fs = require('node:fs');
  const path = require('node:path');
  const projectsRoot = path.join(os.homedir(), '.claude', 'projects');
  if (!fs.existsSync(projectsRoot)) {
    console.log('  [corpus] ~/.claude/projects absent — skipping deriveWorking corpus smoke');
    return;
  }
  const jsonls = [];
  const walk = (dir, depth) => {
    if (depth > 2 || jsonls.length >= 12) return;
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
    for (const e of entries) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p, depth + 1);
      else if (e.isFile() && e.name.endsWith('.jsonl')) jsonls.push(p);
      if (jsonls.length >= 12) return;
    }
  };
  walk(projectsRoot, 0);
  let working = 0;
  for (const file of jsonls) {
    let lines;
    try { lines = fs.readFileSync(file, 'utf8').split('\n').filter((l) => l.length > 0); } catch (e) { continue; }
    // Invariant: deriveWorking never throws and always returns a boolean.
    const w = helper.deriveWorking(lines);
    assert.strictEqual(typeof w, 'boolean', 'deriveWorking must return a boolean on every real transcript');
    if (w) working++;
  }
  console.log(`  [corpus] deriveWorking returned working=true for ${working}/${jsonls.length} real transcripts`);
});

// ════════════════════════════════════════════════════════════════════════════
// UC-49 — pending-question detection: looksLikePendingAskUserQuestion (pure
// predicate seam) + the 3-line / mutual-exclusion data contract.
//
// The matcher reads the VISIBLE pane chrome of a LIVE, awaiting-answer
// AskUserQuestion sheet (the transcript CANNOT see a blocking question — UC-48's
// live finding). It requires CO-OCCURRING chrome: an affordance row ("Type
// something" / "Chat about this") PLUS either the option cursor ❯ (single sheet)
// OR the multi-question wizard tab strip (Submit + a checkbox glyph). ExitPlanMode
// is explicitly excluded. The fixtures below approximate the real 2.1.169 chrome;
// the EXACT tokens are locked against a REAL pending ask in the live capture-pane
// gate (see the QA live-gate report) — these unit fixtures pin the predicate's
// logic (co-occurrence, exclusion, single-vs-multi, empty/null).
// ════════════════════════════════════════════════════════════════════════════

// A single-question sheet: a focused option (❯) + the "Type something" free-text
// "Other" row. → pending.
const UC49_SINGLE_QUESTION_PANE = [
  '╭──────────────────────────────────────────────╮',
  '│ Which database should we use?                  │',
  '│                                                │',
  '│  ❯ 1. PostgreSQL                               │',
  '│    2. MySQL                                    │',
  '│    3. SQLite                                   │',
  '│    4. Type something else                      │',
  '╰──────────────────────────────────────────────╯',
  '  Chat about this instead · esc to cancel',
].join('\n');

// A multi-question wizard: the Submit review tab + checkbox glyphs + the
// "Type something" affordance, but NO option cursor — exercises the wizard branch
// of the OR (affordance + Submit + checkbox), independent of ❯. → pending.
const UC49_MULTI_QUESTION_PANE = [
  '╭──────────────────────────────────────────────╮',
  '│ Configure your project                         │',
  '│  ← ☐ Language    ☑ Framework    Submit →       │',
  '│    Type something                              │',
  '╰──────────────────────────────────────────────╯',
].join('\n');

// ExitPlanMode (UC-40 delivers it live too) — the plan-approval prompt. Even when
// it carries an option cursor AND we inject the affordance text, the explicit
// ExitPlanMode exclusion must win. → NOT pending.
const UC49_EXITPLANMODE_PANE = [
  '╭──────────────────────────────────────────────╮',
  '│ Ready to code?                                 │',
  '│  ❯ 1. Yes, and auto-accept edits               │',
  '│    2. Yes, and manually approve edits          │',
  '│    3. No, keep planning                        │',
  '│    Type something                              │',
  '╰──────────────────────────────────────────────╯',
].join('\n');

// Ordinary working output (a tool-use turn mid-flight). No affordance, no cursor,
// no wizard strip. → NOT pending.
const UC49_WORKING_PANE = [
  '● Refactoring the SessionRow composable…',
  '  ⎿ Reading SessionsScreen.kt (476 lines)',
  '  ⎿ Updated android/.../SessionsScreen.kt',
  'esc to interrupt',
].join('\n');

// Prose that merely MENTIONS the affordance string (and "Submit") but has neither
// the option cursor nor a checkbox glyph — the co-occurrence guard must reject it
// so an ordinary line never trips the badge. → NOT pending.
const UC49_PROSE_MENTIONS_AFFORDANCE =
  'The CLI prints "Type something" as a hint, and I will Submit the PR when done.';

test('UC-49 looksLikePendingAskUserQuestion — a single-question sheet (affordance + ❯) is pending (AC1/AC9)', () => {
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC49_SINGLE_QUESTION_PANE), true);
});

test('UC-49 looksLikePendingAskUserQuestion — a multi-question wizard (affordance + Submit + checkbox) is pending (AC9)', () => {
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC49_MULTI_QUESTION_PANE), true);
});

test('UC-49 looksLikePendingAskUserQuestion — an ExitPlanMode sheet is NOT pending even with affordance + cursor (exclusion wins)', () => {
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC49_EXITPLANMODE_PANE), false);
});

test('UC-49 looksLikePendingAskUserQuestion — ordinary working output is NOT pending', () => {
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC49_WORKING_PANE), false);
});

test('UC-49 looksLikePendingAskUserQuestion — prose mentioning the affordance alone is NOT pending (co-occurrence guard)', () => {
  // "Type something" + "Submit" present, but no ❯ and no checkbox glyph → the
  // affordance alone must not trip the badge.
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC49_PROSE_MENTIONS_AFFORDANCE), false);
});

test('UC-49 looksLikePendingAskUserQuestion — empty / null / non-string input is NOT pending', () => {
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(''), false);
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(null), false);
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(undefined), false);
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(42), false);
  assert.strictEqual(helper.looksLikePendingAskUserQuestion({}), false);
});

test('UC-49 PENDING_QUESTION_CHROME stays pinned to the Claude build (lock-step with InputInjectionService)', () => {
  // The matcher is version-pinned; a TUI restyle is a one-line bump here. This
  // pins the documented pinned version so a silent drift is caught.
  assert.strictEqual(helper.PENDING_QUESTION_CHROME.pinnedClaudeVersion, '2.1.169');
});

// Mutual exclusion + the 3-line output contract. Composes the TWO real production
// seams (looksLikePendingAskUserQuestion + deriveWorking) exactly as
// conversationName() composes them, and pins the line-3 token mapping + the
// capture-failure (null ⇒ omit line 3 ⇒ server retains prior) policy.
test('UC-49 mutual exclusion — a pending pane forces working=idle even when the transcript is mid-turn (AC5)', () => {
  // The transcript alone says "working" (a turn is mid-flight)…
  const lines = [userLine('start the migration'), assistantTextLine('working on it')];
  assert.strictEqual(helper.deriveWorking(lines), true);
  // …but the VISIBLE pane shows a pending question.
  const pending = helper.looksLikePendingAskUserQuestion(UC49_SINGLE_QUESTION_PANE);
  assert.strictEqual(pending, true);
  // Production composition (conversationName): pending===true ⇒ working forced false.
  const working = pending === true ? false : helper.deriveWorking(lines);
  assert.strictEqual(working, false, 'AC5 — a pending question is never reported as working');
});

test('UC-49 line-3 token contract — true→"pending-question", false→"none", null→omitted (retain)', () => {
  const line3 = (p) => (p === null ? null : p ? 'pending-question' : 'none');
  // A real pending pane → "pending-question".
  assert.strictEqual(line3(helper.looksLikePendingAskUserQuestion(UC49_SINGLE_QUESTION_PANE)), 'pending-question');
  // A real non-pending pane → "none".
  assert.strictEqual(line3(helper.looksLikePendingAskUserQuestion(UC49_WORKING_PANE)), 'none');
  // capturePaneText() failure ⇒ pending=null ⇒ line 3 OMITTED (2-line output,
  // exactly the pre-UC-49 shape) so the server retains its prior pending value
  // (failure policy (b)) — no one-poll "?" flicker.
  assert.strictEqual(line3(null), null);
});

test('UC-49 — name + working + pending seams produce the expected 3-line tuple from one input (AC5)', () => {
  // A working transcript with a pending pane → (name, idle, pending-question):
  // working is suppressed by the pending precedence.
  const lines = [userLine('Refactor the SessionRow'), assistantTextLine('mid turn')];
  const name = helper.deriveConversationName(lines) || '';
  const pending = helper.looksLikePendingAskUserQuestion(UC49_SINGLE_QUESTION_PANE);
  const working = pending === true ? false : helper.deriveWorking(lines);
  assert.strictEqual(name, 'Refactor the SessionRow');
  assert.strictEqual(working ? 'working' : 'idle', 'idle');
  assert.strictEqual(pending ? 'pending-question' : 'none', 'pending-question');
});

// ════════════════════════════════════════════════════════════════════════════
// UC-50 — pane-signal pending-PROMPT parsing (parsePendingPrompt + its seams).
//
// UC-49 only needed a boolean "is a question pending?" for the sessions-list "?".
// UC-50 must deliver the STRUCTURED prompt (kind, questions[], plan, stable key)
// to the conversation view, because claude 2.1.169 never writes the blocking
// assistant turn to the transcript.
//
// The fixtures below are RECORDED from a REAL `claude 2.1.169` session
// (`tmux capture-pane -p`) live on the running ai-sandbox-2 backend on 2026-06-10
// — the recorded shapes the UC-50 proposal asked us to pin. They are the exact
// bytes the helper must parse in production (NOT the approximate, box-bordered
// UC-49 fixtures, which only ever exercised the boolean predicate).
// ════════════════════════════════════════════════════════════════════════════

// RECORDED — single-SELECT single question. NOTE: the tab line is ` ☐ Database`
// with NO "Submit" (single-select commits on Enter; only multiSelect adds a
// Submit tab). Options are numbered radio rows; the free-text "Type something."
// and the "Chat about this" escape are NOT real options.
const UC50_REAL_SINGLE_SELECT = [
  ' ☐ Database',
  '',
  'Which database should we use?',
  '',
  '❯ 1. PostgreSQL',
  '     Use PostgreSQL.',
  '  2. MySQL',
  '     Use MySQL.',
  '  3. SQLite',
  '     Use SQLite.',
  '  4. Type something.',
  '────────────────────────────────────────────────────────────────────────────────',
  '  5. Chat about this',
  '',
  'Enter to select · ↑/↓ to navigate · Esc to cancel',
].join('\n');

// RECORDED — multi-SELECT SINGLE question. The tab strip carries a Submit tab for
// the ONE question (`←  ☐ Toppings  ✔ Submit  →`), and each OPTION row carries a
// `[ ]` checkbox — that per-option checkbox (not the tab-strip glyph) is the true
// multiSelect signal. The free-text affordance is `[ ] Type something`.
const UC50_REAL_MULTI_SELECT = [
  '←  ☐ Toppings  ✔ Submit  →',
  '',
  'Pick toppings',
  '',
  '❯ 1. [ ] Cheese',
  '  Add cheese.',
  '  2. [ ] Mushroom',
  '  Add mushroom.',
  '  3. [ ] Onion',
  '  Add onion.',
  '  4. [ ] Type something',
  '     Submit',
  '────────────────────────────────────────────────────────────────────────────────',
  '  5. Chat about this',
  '',
  'Enter to select · ↑/↓ to navigate · Esc to cancel',
].join('\n');

// RECORDED — multi-QUESTION batch (≥2 questions). The wizard tab strip lists every
// question header plus a Submit review tab; only the FOCUSED question's options are
// on screen, so the helper recovers header-only items and the server marks the
// whole batch answerable=false (AC2).
const UC50_REAL_MULTI_QUESTION = [
  '←  ☐ Color  ☐ Size  ✔ Submit  →',
  '',
  'What is your favorite color?',
  '',
  '❯ 1. Red',
  '     The color red.',
  '  2. Green',
  '     The color green.',
  '  3. Blue',
  '     The color blue.',
  '  4. Type something.',
  '────────────────────────────────────────────────────────────────────────────────',
  '  5. Chat about this',
  '',
  'Enter to select · Tab/Arrow keys to navigate · Esc to cancel',
].join('\n');

// A pending ExitPlanMode plan-approval prompt (AC6). Different chrome — a plan
// approval with a "keep planning" reject option and no free-text affordance.
const UC50_PLAN_APPROVAL = [
  'Here is my plan:',
  '  1. Wire the pane signal',
  '  2. Add the DTOs',
  '',
  'Would you like to proceed?',
  '',
  '❯ 1. Yes',
  '  2. No, keep planning',
  '',
  'Enter to select · Esc to cancel',
].join('\n');

// ──────────────────────── predicates: mutual exclusivity (AC1/AC6) ────────────────────────

test('UC-50 looksLikePendingPlanApproval — a real plan-approval pane is pending-plan; an AskUserQuestion sheet is NOT', () => {
  assert.strictEqual(helper.looksLikePendingPlanApproval(UC50_PLAN_APPROVAL), true);
  // The two predicates are mutually exclusive on the SAME input: a plan prompt has
  // no "Type something"/"Chat about this" free-text affordance, so the question
  // predicate rejects it; an AskUserQuestion sheet has no plan marker.
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC50_PLAN_APPROVAL), false);
  assert.strictEqual(helper.looksLikePendingPlanApproval(UC50_REAL_SINGLE_SELECT), false);
  assert.strictEqual(helper.looksLikePendingPlanApproval(UC50_REAL_MULTI_QUESTION), false);
});

test('UC-50 looksLikePendingPlanApproval — empty / null / non-string is NOT pending', () => {
  assert.strictEqual(helper.looksLikePendingPlanApproval(''), false);
  assert.strictEqual(helper.looksLikePendingPlanApproval(null), false);
  assert.strictEqual(helper.looksLikePendingPlanApproval(undefined), false);
  assert.strictEqual(helper.looksLikePendingPlanApproval(42), false);
});

test('UC-50 looksLikePendingPlanApproval — "keep planning" in plain prose does NOT trip it (needs the option cursor)', () => {
  assert.strictEqual(
    helper.looksLikePendingPlanApproval('I will keep planning the migration before I touch anything.'),
    false,
  );
});

test('UC-50 predicates agree with the REAL recorded AskUserQuestion panes (single/multi-select/multi-question are all pending)', () => {
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC50_REAL_SINGLE_SELECT), true);
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC50_REAL_MULTI_SELECT), true);
  assert.strictEqual(helper.looksLikePendingAskUserQuestion(UC50_REAL_MULTI_QUESTION), true);
});

// ──────────────────────── parseWizardHeaders (AC2) ────────────────────────

test('UC-50 parseWizardHeaders — a multi-question strip yields each header, drops the Submit review tab', () => {
  assert.deepStrictEqual(helper.parseWizardHeaders(UC50_REAL_MULTI_QUESTION), ['Color', 'Size']);
});

test('UC-50 parseWizardHeaders — a single-SELECT pane (no Submit tab) yields no headers', () => {
  assert.deepStrictEqual(helper.parseWizardHeaders(UC50_REAL_SINGLE_SELECT), []);
});

// ──────────────────────── parseFocusedPrompt (AC1/AC3) ────────────────────────

test('UC-50 parseFocusedPrompt — recovers the focused question text from each real pane', () => {
  assert.strictEqual(helper.parseFocusedPrompt(UC50_REAL_SINGLE_SELECT), 'Which database should we use?');
  assert.strictEqual(helper.parseFocusedPrompt(UC50_REAL_MULTI_QUESTION), 'What is your favorite color?');
  assert.strictEqual(helper.parseFocusedPrompt(UC50_REAL_MULTI_SELECT), 'Pick toppings');
});

test('UC-50 parseFocusedPrompt — returns empty when there is no numbered option row', () => {
  assert.strictEqual(helper.parseFocusedPrompt('just some output\nno options here'), '');
});

// ──────────────────────── parseNumberedOptions (AC3) ────────────────────────

test('UC-50 parseNumberedOptions — single-select radio rows: real options + descriptions, affordance rows dropped', () => {
  const opts = helper.parseNumberedOptions(UC50_REAL_SINGLE_SELECT);
  // "Type something." (free-text) and "Chat about this" (escape) are NOT real options.
  assert.deepStrictEqual(
    opts.map((o) => o.label),
    ['PostgreSQL', 'MySQL', 'SQLite'],
  );
  assert.strictEqual(opts[0].description, 'Use PostgreSQL.');
  assert.strictEqual(opts[2].description, 'Use SQLite.');
});

test('UC-50 [BUG2b/2c] parseNumberedOptions — multi-select rows: labels stripped of the [ ] checkbox, free-text affordance dropped', () => {
  // RED until the developer fix lands (BUG 2b/2c reported to team-lead): the parser
  // must strip a leading `[ ] `/`[x] ` checkbox marker from the label and STILL drop
  // the "[ ] Type something" free-text row. Today it yields labels like "[ ] Cheese"
  // and counts "[ ] Type something" as a real 4th option (optionCount off-by-one →
  // wrong injection walk).
  const opts = helper.parseNumberedOptions(UC50_REAL_MULTI_SELECT);
  assert.deepStrictEqual(
    opts.map((o) => o.label),
    ['Cheese', 'Mushroom', 'Onion'],
  );
});

// ──────────────────────── parsePendingPrompt — structured payload ────────────────────────

test('UC-50 parsePendingPrompt — single-select question: kind=questions, full question + options recovered (AC3)', () => {
  const p = helper.parsePendingPrompt(UC50_REAL_SINGLE_SELECT);
  assert.strictEqual(p.kind, 'questions');
  assert.strictEqual(p.questions.length, 1);
  assert.strictEqual(p.questions[0].question, 'Which database should we use?');
  assert.deepStrictEqual(
    p.questions[0].options.map((o) => o.label),
    ['PostgreSQL', 'MySQL', 'SQLite'],
  );
  assert.ok(p.key && p.key.startsWith('pane-'));
});

test('UC-50 [BUG1] parsePendingPrompt — a single-SELECT question must be multiSelect=false', () => {
  // RED until BUG 1 fix: the tab-line ` ☐ Database` makes the current heuristic
  // (`headers.length===0 && /[☐☑]/.test(pane)`) report multiSelect=true for a
  // single-SELECT question. multiSelect must be decided from per-OPTION checkboxes,
  // which this pane has none of (numbered radio rows).
  const p = helper.parsePendingPrompt(UC50_REAL_SINGLE_SELECT);
  assert.strictEqual(p.questions[0].multiSelect, false);
});

test('UC-50 [BUG1b] parsePendingPrompt — a single question recovers its header (AC3 "fully recovered")', () => {
  // RED until BUG 1b fix: the header "Database" lives in the ` ☐ Database` tab line,
  // which parseWizardHeaders ignores (it requires "Submit"), so header is currently "".
  const p = helper.parsePendingPrompt(UC50_REAL_SINGLE_SELECT);
  assert.strictEqual(p.questions[0].header, 'Database');
});

test('UC-50 [BUG2a] parsePendingPrompt — a multi-SELECT single question must be multiSelect=true', () => {
  // RED until BUG 2a fix: the Submit tab makes headers=["Toppings"] (length 1 → single
  // branch), and the current heuristic then computes multiSelect=false. The per-option
  // `[ ]` checkboxes are the real signal → must be multiSelect=true.
  const p = helper.parsePendingPrompt(UC50_REAL_MULTI_SELECT);
  assert.strictEqual(p.questions.length, 1);
  assert.strictEqual(p.questions[0].multiSelect, true);
});

test('UC-50 parsePendingPrompt — a multi-QUESTION batch yields >1 header-only items (drives answerable=false, AC2)', () => {
  const p = helper.parsePendingPrompt(UC50_REAL_MULTI_QUESTION);
  assert.strictEqual(p.kind, 'questions');
  assert.strictEqual(p.questions.length, 2);
  assert.deepStrictEqual(
    p.questions.map((q) => q.header),
    ['Color', 'Size'],
  );
});

test('UC-50 parsePendingPrompt — a plan-approval pane yields kind=plan (AC6)', () => {
  const p = helper.parsePendingPrompt(UC50_PLAN_APPROVAL);
  assert.strictEqual(p.kind, 'plan');
  assert.deepStrictEqual(p.questions, []);
  assert.ok(p.key && p.key.startsWith('pane-'));
});

test('UC-50 parsePendingPrompt — a non-pending pane (ordinary working output) returns null', () => {
  assert.strictEqual(helper.parsePendingPrompt(UC49_WORKING_PANE), null);
});

test('UC-50 parsePendingPrompt — prose that merely MENTIONS the affordance returns null (negative, co-occurrence guard)', () => {
  assert.strictEqual(helper.parsePendingPrompt(UC49_PROSE_MENTIONS_AFFORDANCE), null);
  assert.strictEqual(
    helper.parsePendingPrompt('The docs say to "Type something" then press Enter to select an option.'),
    null,
  );
});

test('UC-50 parsePendingPrompt — empty / null / non-string input returns null (robustness)', () => {
  assert.strictEqual(helper.parsePendingPrompt(''), null);
  assert.strictEqual(helper.parsePendingPrompt(null), null);
  assert.strictEqual(helper.parsePendingPrompt(undefined), null);
  assert.strictEqual(helper.parsePendingPrompt(42), null);
});

// ──────────────────────── stripVolatile + fnv1a + key stability (settle/emit-once) ────────────────────────

test('UC-50 stripVolatile — drops spinner / nav-hint / status lines and blank lines', () => {
  const stripped = helper.stripVolatile(UC50_REAL_SINGLE_SELECT);
  assert.ok(!/Enter to select/.test(stripped), 'nav hint line stripped');
  assert.ok(!/^\s*$/m.test(stripped) || stripped.split('\n').every((l) => l.trim() !== ''), 'no blank lines');
  // The substantive prompt lines survive.
  assert.ok(/Which database should we use\?/.test(stripped));
  assert.ok(/PostgreSQL/.test(stripped));
});

test('UC-50 fnv1a — deterministic, stable, 32-bit hex; differs for different input', () => {
  assert.strictEqual(helper.fnv1a('hello'), helper.fnv1a('hello'));
  assert.notStrictEqual(helper.fnv1a('hello'), helper.fnv1a('hellp'));
  assert.match(helper.fnv1a('anything'), /^[0-9a-f]{1,8}$/);
});

test('UC-50 prompt key is STABLE across polls while blocked — only volatile lines change (drives the settle)', () => {
  // Two captures of the SAME pending prompt, differing ONLY in the volatile spinner /
  // nav-hint lines, must hash to the SAME key — so streamLoop's "stable across one
  // extra poll" settle converges and the prompt is emitted exactly once per key.
  const poll1 = UC50_REAL_SINGLE_SELECT.replace(
    'Enter to select · ↑/↓ to navigate · Esc to cancel',
    '✻ Brewed for 3s',
  );
  const poll2 = UC50_REAL_SINGLE_SELECT.replace(
    'Enter to select · ↑/↓ to navigate · Esc to cancel',
    '✻ Brewed for 7s (esc to interrupt)',
  );
  const k1 = helper.parsePendingPrompt(poll1).key;
  const k2 = helper.parsePendingPrompt(poll2).key;
  assert.strictEqual(k1, k2, 'volatile-only differences must NOT change the key');
});

test('UC-50 prompt key CHANGES when the actual prompt changes (re-emit on a new question)', () => {
  const kDb = helper.parsePendingPrompt(UC50_REAL_SINGLE_SELECT).key;
  const kColor = helper.parsePendingPrompt(UC50_REAL_MULTI_QUESTION).key;
  assert.notStrictEqual(kDb, kColor, 'a different prompt must yield a different key');
});

test('UC-50 CTRL constants are the exact control-kind tokens the server splits on (lock-step)', () => {
  assert.strictEqual(helper.CTRL_PENDING_QUESTION, 'pending-question');
  assert.strictEqual(helper.CTRL_PENDING_CLEAR, 'pending-clear');
});

// ════════════════════════════════════════════════════════════════════════════
// UC-55 — per-tab parse (parseFocusedTab + --parse-pane).
//
// UC-50 recovers a multi-QUESTION wizard header-only (one capture only shows the
// FOCUSED tab). UC-55 makes the whole batch in-app answerable: the server steps the
// live pane through every tab and calls --parse-pane once per tab; parseFocusedTab
// must recover THAT tab's full options (not header-only). The two fixtures below are
// the SAME 2-question wizard captured on each tab — UC50_REAL_MULTI_QUESTION is tab 0
// (Color focused); UC55_REAL_MULTI_QUESTION_TAB_SIZE is tab 1 (Size focused, recorded
// live by stepping Right). The tab strip is identical across tabs; only the focused
// option block differs — which is exactly why per-tab stepping is required.
// ════════════════════════════════════════════════════════════════════════════

const UC55_REAL_MULTI_QUESTION_TAB_SIZE = [
  '←  ☐ Color  ☐ Size  ✔ Submit  →',
  '',
  'Pick a size',
  '',
  '❯ 1. Small',
  '     A small size.',
  '  2. Large',
  '     A large size.',
  '  3. Type something.',
  '────────────────────────────────────────────────────────────────────────────────',
  '  4. Chat about this',
  '',
  'Enter to select · Tab/Arrow keys to navigate · Esc to cancel',
].join('\n');

test('UC-55 parseFocusedTab — tab 0 (Color focused) recovers that tab\'s FULL options, not header-only', () => {
  const t = helper.parseFocusedTab(UC50_REAL_MULTI_QUESTION);
  assert.strictEqual(t.question, 'What is your favorite color?');
  assert.strictEqual(t.multiSelect, false);
  assert.deepStrictEqual(
    t.options.map((o) => o.label),
    ['Red', 'Green', 'Blue'],
  );
});

test('UC-55 parseFocusedTab — tab 1 (Size focused) recovers the OTHER tab\'s options after the server steps Right', () => {
  // The flagship recovery: the non-focused tab UC-50 could only see header-only is
  // now fully recovered from its own focused capture. This is what flips the whole
  // multi-question batch answerable=true (AC2/AC5/AC10).
  const t = helper.parseFocusedTab(UC55_REAL_MULTI_QUESTION_TAB_SIZE);
  assert.strictEqual(t.question, 'Pick a size');
  assert.strictEqual(t.multiSelect, false);
  assert.deepStrictEqual(
    t.options.map((o) => o.label),
    ['Small', 'Large'],
  );
});

test('UC-55 parseFocusedTab — a multi-SELECT tab is recovered with multiSelect=true (per-option checkboxes)', () => {
  const t = helper.parseFocusedTab(UC50_REAL_MULTI_SELECT);
  assert.strictEqual(t.multiSelect, true);
  assert.deepStrictEqual(
    t.options.map((o) => o.label),
    ['Cheese', 'Mushroom', 'Onion'],
  );
});

test('UC-55 parseFocusedTab — empty / null / non-string input returns null (robustness)', () => {
  assert.strictEqual(helper.parseFocusedTab(''), null);
  assert.strictEqual(helper.parseFocusedTab(null), null);
  assert.strictEqual(helper.parseFocusedTab(undefined), null);
  assert.strictEqual(helper.parseFocusedTab(42), null);
});

// ════════════════════════════════════════════════════════════════════════════
// UC-69 — firstQuestionText: the notification BODY (line 4) seam.
//
// firstQuestionText(parsed) takes the structured prompt from parsePendingPrompt
// and yields the FIRST question's text (AC3) for the local push-notification
// body: the focused question's prompt, falling back to its header when the prompt
// line was not recovered (a header-only wizard item). Sanitised + codepoint-
// capped. Returns '' when there is no usable text, so conversationName OMITS line
// 4 and the server RETAINS its prior body (failure policy (b)).
// ════════════════════════════════════════════════════════════════════════════

test('UC-69 firstQuestionText — a single-question pane yields the focused question text (AC3, pending+text → emit)', () => {
  const parsed = helper.parsePendingPrompt(UC50_REAL_SINGLE_SELECT);
  assert.strictEqual(helper.firstQuestionText(parsed), 'Which database should we use?');
});

test('UC-69 firstQuestionText — a multi-QUESTION batch uses the FIRST question, header-only (AC3)', () => {
  // parsePendingPrompt yields header-only items for a multi-question batch (only the
  // focused tab's options are recovered), so the body falls back to the FIRST
  // question's header ("Color") rather than its full prompt — still the first
  // question's identity (AC3), and never an empty body for a multi-question wizard.
  const parsed = helper.parsePendingPrompt(UC50_REAL_MULTI_QUESTION);
  assert.deepStrictEqual(
    parsed.questions.map((q) => q.header),
    ['Color', 'Size'],
  );
  assert.strictEqual(helper.firstQuestionText(parsed), 'Color');
});

test('UC-69 firstQuestionText — falls back to the header when the first item is header-only', () => {
  // A header-only item (no recovered prompt line) still yields a non-empty body via
  // its header, so a multi-question wizard never ships an empty notification body.
  const parsed = { kind: 'questions', questions: [{ header: 'Color', question: '' }] };
  assert.strictEqual(helper.firstQuestionText(parsed), 'Color');
});

test('UC-69 firstQuestionText — a plan-approval prompt has no question text → "" (omit line 4)', () => {
  // kind=plan carries questions=[] → no body. conversationName omits line 4, so the
  // server retains its prior text rather than clobbering it (failure policy (b)).
  const parsed = helper.parsePendingPrompt(UC50_PLAN_APPROVAL);
  assert.strictEqual(parsed.kind, 'plan');
  assert.strictEqual(helper.firstQuestionText(parsed), '');
});

test('UC-69 firstQuestionText — not-pending / null / malformed input yields "" (no line 4)', () => {
  // A non-pending pane parses to null → no body (server retains / shows none).
  assert.strictEqual(helper.firstQuestionText(helper.parsePendingPrompt(UC49_WORKING_PANE)), '');
  assert.strictEqual(helper.firstQuestionText(null), '');
  assert.strictEqual(helper.firstQuestionText(undefined), '');
  assert.strictEqual(helper.firstQuestionText({ kind: 'questions', questions: [] }), '');
  assert.strictEqual(helper.firstQuestionText({ kind: 'questions' }), '');
  assert.strictEqual(helper.firstQuestionText({}), '');
});

test('UC-69 firstQuestionText — body is sanitised to one line and codepoint-capped', () => {
  // A pathological multi-line / over-long prompt must collapse to a single line and
  // cap at CONVERSATION_NAME_MAX_CP (defence in depth; the server caps again).
  const longLine = 'q'.repeat(helper.CONVERSATION_NAME_MAX_CP + 50);
  const parsed = { kind: 'questions', questions: [{ question: 'line one\n   line two   \t' + longLine }] };
  const body = helper.firstQuestionText(parsed);
  assert.ok(!body.includes('\n'), 'no embedded newlines');
  assert.ok(!body.includes('\t'), 'no embedded tabs');
  assert.ok(
    [...body].length <= helper.CONVERSATION_NAME_MAX_CP,
    'body is capped at CONVERSATION_NAME_MAX_CP codepoints',
  );
});
