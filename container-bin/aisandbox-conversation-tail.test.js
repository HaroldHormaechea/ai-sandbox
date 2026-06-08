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
