// App shell: workbench with centered Android frame, screen picker, tweaks.

const SCREENS = [
  { id: 'onboarding',   label: 'QR onboarding',  spec: 'UC04-1' },
  { id: 'sessions',     label: 'Sessions list',  spec: 'UC04-2' },
  { id: 'new-session',  label: 'New session',    spec: 'UC04-2a' },
  { id: 'delete',       label: 'Delete confirm', spec: 'UC04-2b' },
  { id: 'terminal',     label: 'Terminal',       spec: 'UC04-3' },
  { id: 'split',        label: 'Split / resize', spec: 'UC04-3c' },
  { id: 'notification', label: 'Notification',   spec: 'UC04-5' },
  { id: 'revoked',      label: 'Cert revoked',   spec: 'UC04-6' },
  { id: 'settings',     label: 'Settings',       spec: 'UC04-7' },
];

const ACCENT_PRESETS = {
  '#ece6ec': { accent: '#ece6ec', onAccent: '#1f1d22', container: '#2a262e', onContainer: '#efe9ef' },
  '#d0bcff': { accent: '#d0bcff', onAccent: '#1f1735', container: '#36275f', onContainer: '#eaddff' },
  '#9ddcb1': { accent: '#9ddcb1', onAccent: '#00391c', container: '#1f4d33', onContainer: '#bbf7d0' },
  '#a5c8ff': { accent: '#a5c8ff', onAccent: '#002b6c', container: '#1c3f7a', onContainer: '#d6e3ff' },
  '#ffb78c': { accent: '#ffb78c', onAccent: '#532200', container: '#6e3416', onContainer: '#ffdcc5' },
};

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "screen": "sessions",
  "modBar": "docked",
  "density": "cards",
  "accent": "#ece6ec",
  "showSpecRail": true,
  "showFrame": true
}/*EDITMODE-END*/;

function App() {
  const [t, setTweak] = window.useTweaks(TWEAK_DEFAULTS);

  // ── Apply accent variables ──
  React.useEffect(() => {
    const p = ACCENT_PRESETS[t.accent] || ACCENT_PRESETS['#ece6ec'];
    const root = document.documentElement;
    root.style.setProperty('--accent', p.accent);
    root.style.setProperty('--on-accent', p.onAccent);
    root.style.setProperty('--accent-container', p.container);
    root.style.setProperty('--on-accent-container', p.onContainer);
  }, [t.accent]);

  // ── Sessions state ──
  const [sessions, setSessions] = React.useState(window.SESSIONS_SEED);
  const [sheet, setSheet] = React.useState(null);     // 'new' | null
  const [dialog, setDialog] = React.useState(null);   // { kind: 'delete', session } | { kind:'revoked' } | null
  const [activeSessionN, setActiveSessionN] = React.useState(4);

  const goto = (screenId, opts = {}) => {
    if (opts.sessionN) setActiveSessionN(opts.sessionN);
    setTweak('screen', screenId);
  };

  // ── Map "screen" id → react element ──
  const renderScreen = () => {
    switch (t.screen) {
      case 'onboarding':
        return <ScreenOnboarding goto={goto} />;
      case 'sessions':
      case 'new-session':
      case 'delete':
        return <ScreenSessions
          goto={goto}
          sessions={sessions}
          density={t.density}
          onNew={() => { setTweak('screen', 'new-session'); setSheet('new'); }}
          onDeleteAsk={(s) => { setTweak('screen', 'delete'); setDialog({ kind: 'delete', session: s }); }}
        />;
      case 'terminal':
      case 'split':
        return <ScreenTerminal
          goto={goto}
          sessionN={activeSessionN}
          modBarMode={t.modBar}
          onCertRevoked={() => setDialog({ kind: 'revoked' })}
        />;
      case 'revoked':
        // Show terminal beneath, then immediately open dialog
        return <ScreenTerminal goto={goto} sessionN={activeSessionN} modBarMode={t.modBar} onCertRevoked={() => {}} />;
      case 'notification':
        return <ScreenNotification
          goto={(s, opts) => { goto(s, opts); }}
          sessionN={activeSessionN}
          onDisconnect={() => { setActiveSessionN(activeSessionN); goto('sessions'); }}
        />;
      case 'settings':
        return <ScreenSettings goto={goto} onForceRevoke={() => setDialog({ kind: 'revoked' })} />;
      default:
        return null;
    }
  };

  // Auto-open dialogs when screen demands
  React.useEffect(() => {
    if (t.screen === 'revoked') setDialog({ kind: 'revoked' });
    if (t.screen === 'new-session' && sheet == null) setSheet('new');
    if (t.screen === 'delete' && dialog == null) setDialog({ kind: 'delete', session: sessions[0] });
  }, [t.screen]);

  return (
    <div style={{ display: 'flex', width: '100%', height: '100%', position: 'relative' }}>
      {/* Workbench background */}
      <div style={{
        position: 'absolute', inset: 0,
        backgroundImage: `
          radial-gradient(circle at 20% 30%, color-mix(in oklab, var(--accent) 10%, transparent) 0%, transparent 35%),
          radial-gradient(circle at 80% 80%, color-mix(in oklab, var(--accent) 6%, transparent) 0%, transparent 40%),
          var(--bg-workbench)
        `,
      }}/>

      {/* Left rail — screen picker */}
      <ScreenPicker
        current={t.screen}
        setScreen={(id) => { setSheet(null); setDialog(null); setTweak('screen', id); }}
      />

      {/* Center — phone frame */}
      <div style={{ flex: 1, position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}>
        <Phone showFrame={t.showFrame}>
          <div data-screen-label={SCREENS.find(s => s.id === t.screen)?.label} style={{ display: 'flex', flexDirection: 'column', flex: 1, position: 'relative', background: 'var(--surface)', overflow: 'hidden' }}>
            {renderScreen()}

            {/* Sheets */}
            <NewSessionSheet
              open={sheet === 'new'}
              onClose={() => { setSheet(null); if (t.screen === 'new-session') setTweak('screen', 'sessions'); }}
              onCreate={(opts) => {
                const nextN = Math.max(...sessions.map(s => s.n)) + 1;
                setSessions(ss => [...ss, { n: nextN, name: opts.name || `ai-sandbox-${nextN}`, status: 'starting', attached: 0, image: 'server profile', uptime: '00m 00s', cpu: 0, mem: '—' }]);
                setSheet(null);
                setTweak('screen', 'sessions');
              }}
            />
            {dialog?.kind === 'delete' && (
              <DeleteDialog
                open
                session={dialog.session}
                onClose={() => { setDialog(null); if (t.screen === 'delete') setTweak('screen', 'sessions'); }}
                onConfirm={() => {
                  setSessions(ss => ss.filter(s => s.n !== dialog.session.n));
                  setDialog(null);
                  setTweak('screen', 'sessions');
                }}
              />
            )}
            {dialog?.kind === 'revoked' && (
              <CertRevokedDialog
                open
                onClose={() => { setDialog(null); if (t.screen === 'revoked') setTweak('screen', 'terminal'); }}
                onRescan={() => { setDialog(null); setTweak('screen', 'onboarding'); }}
              />
            )}
          </div>
        </Phone>
      </div>

      {/* Right rail — spec annotations */}
      {t.showSpecRail && (
        <SpecRail screenId={t.screen} />
      )}

      {/* Tweaks panel */}
      <TweaksPanel title="Tweaks">
        <TweakSection label="Screen">
          <TweakSelect label="Show"
            value={t.screen}
            options={SCREENS.map(s => ({ value: s.id, label: `${s.spec}  ·  ${s.label}` }))}
            onChange={(v) => { setSheet(null); setDialog(null); setTweak('screen', v); }} />
        </TweakSection>

        <TweakSection label="Variations">
          <TweakRadio label="Modifier bar"
            value={t.modBar}
            options={[
              { value: 'docked', label: 'Docked' },
              { value: 'floating', label: 'Float' },
              { value: 'collapsible', label: 'Collapse' },
            ]}
            onChange={(v) => setTweak('modBar', v)} />
          <TweakRadio label="List density"
            value={t.density}
            options={[
              { value: 'cards', label: 'Cards' },
              { value: 'rows', label: 'Rows' },
              { value: 'compact', label: 'Compact' },
            ]}
            onChange={(v) => setTweak('density', v)} />
          <TweakColor label="Accent"
            value={t.accent}
            options={['#ece6ec', '#d0bcff', '#9ddcb1', '#a5c8ff', '#ffb78c']}
            onChange={(v) => setTweak('accent', v)} />
        </TweakSection>

        <TweakSection label="Workbench">
          <TweakToggle label="Implementation notes" value={t.showSpecRail} onChange={(v) => setTweak('showSpecRail', v)} />
          <TweakToggle label="Phone frame" value={t.showFrame} onChange={(v) => setTweak('showFrame', v)} />
        </TweakSection>
      </TweaksPanel>
    </div>
  );
}

// ── Phone frame wrapper ───────────────────────────────────────────
const Phone = ({ children, showFrame }) => {
  // Scale to fit viewport
  const W = 412, H = 892;
  const wrapRef = React.useRef(null);
  const [scale, setScale] = React.useState(1);
  React.useEffect(() => {
    const recalc = () => {
      const parent = wrapRef.current?.parentElement;
      if (!parent) return;
      const pad = 48;
      const s = Math.min((parent.clientWidth - pad) / W, (parent.clientHeight - pad) / H, 1);
      setScale(s);
    };
    recalc();
    window.addEventListener('resize', recalc);
    return () => window.removeEventListener('resize', recalc);
  }, []);

  if (!showFrame) {
    return (
      <div ref={wrapRef} style={{
        width: W * scale, height: H * scale,
        position: 'relative',
      }}>
        <div style={{
          position: 'absolute', inset: 0, transformOrigin: '0 0',
          transform: `scale(${scale})`, width: W, height: H,
          background: 'var(--surface)', overflow: 'hidden', borderRadius: 12,
          boxShadow: '0 30px 80px rgba(0,0,0,0.45)',
          display: 'flex', flexDirection: 'column',
        }}>
          {children}
        </div>
      </div>
    );
  }

  return (
    <div ref={wrapRef} style={{ width: (W + 16) * scale, height: (H + 16) * scale, position: 'relative' }}>
      <div style={{
        position: 'absolute', inset: 0, transformOrigin: '0 0',
        transform: `scale(${scale})`,
        width: W + 16, height: H + 16,
      }}>
        <PhoneFrame>{children}</PhoneFrame>
      </div>
    </div>
  );
};

const PhoneFrame = ({ children }) => (
  <div style={{
    width: '100%', height: '100%',
    borderRadius: 56,
    padding: 8,
    background: 'linear-gradient(160deg, #2a2830, #16151a)',
    boxShadow: '0 50px 120px rgba(0,0,0,0.55), 0 0 0 1px rgba(255,255,255,0.04) inset',
  }}>
    <div style={{
      width: '100%', height: '100%',
      borderRadius: 48,
      background: 'var(--surface)',
      overflow: 'hidden',
      position: 'relative',
      display: 'flex', flexDirection: 'column',
      boxShadow: '0 0 0 1px rgba(0,0,0,0.4) inset',
    }}>
      {/* Status bar */}
      <PhoneStatusBar />
      {/* Camera punch hole */}
      <div style={{ position: 'absolute', top: 12, left: '50%', transform: 'translateX(-50%)', width: 22, height: 22, borderRadius: '50%', background: '#000', zIndex: 100 }}/>
      {/* Content */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {children}
      </div>
      {/* Gesture nav handle */}
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 28, flexShrink: 0, background: 'transparent' }}>
        <div style={{ width: 128, height: 4, borderRadius: 2, background: 'var(--on-surface)', opacity: 0.35 }} />
      </div>
    </div>
  </div>
);

const PhoneStatusBar = () => (
  <div style={{
    height: 36, padding: '0 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    fontFamily: 'var(--font-sans)', fontSize: 13, color: 'var(--on-surface)', flexShrink: 0,
    zIndex: 50,
  }}>
    <span style={{ fontWeight: 500 }}>14:32</span>
    <div style={{ display: 'flex', gap: 6, alignItems: 'center', opacity: 0.85 }}>
      {/* signal */}
      <svg width="16" height="12" viewBox="0 0 16 12" fill="currentColor"><path d="M0 10h2v2H0zm4-2h2v4H4zm4-3h2v7H8zm4-3h2v10h-2z"/></svg>
      {/* wifi */}
      <svg width="16" height="12" viewBox="0 0 16 12" fill="currentColor"><path d="M8 11.5l2-2-.6-.6a2 2 0 00-2.8 0L6 9.5l2 2zm-3.4-3.4l1.2-1.2a4 4 0 015.6 0l1.2 1.2.8-.8a5.5 5.5 0 00-7.6 0l-1.2-1.2zm-2.4-2.4l.8-.8a8 8 0 0111.2 0l.8.8L16 4.7a9 9 0 00-16 0l2.2 1z"/></svg>
      {/* battery */}
      <svg width="22" height="12" viewBox="0 0 22 12" fill="none" stroke="currentColor" strokeWidth="1"><rect x="0.5" y="1" width="18" height="10" rx="2"/><rect x="2" y="2.5" width="13" height="7" rx="1" fill="currentColor"/><rect x="20" y="4" width="1.5" height="4" rx="0.5" fill="currentColor"/></svg>
    </div>
  </div>
);

// ── Screen picker (left rail) ────────────────────────────────────
const ScreenPicker = ({ current, setScreen }) => (
  <div style={{
    width: 220, flexShrink: 0, padding: '20px 14px',
    borderRight: '1px solid var(--outline-variant)',
    background: 'rgba(0,0,0,0.25)',
    display: 'flex', flexDirection: 'column', gap: 4,
    overflow: 'auto', position: 'relative',
  }} className="scroll">
    <div style={{ padding: '0 8px 8px' }}>
      <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, letterSpacing: 1, color: 'var(--on-surface-muted)' }}>UC04 · Android</div>
      <div style={{ fontSize: 18, fontWeight: 500, marginTop: 2, letterSpacing: -0.2 }}>ai-sandbox client</div>
    </div>
    <Divider style={{ margin: '4px 0 8px' }} />
    {SCREENS.map(s => {
      const active = current === s.id;
      return (
        <button key={s.id} onClick={() => setScreen(s.id)} style={{
          textAlign: 'left', padding: '10px 12px', borderRadius: 10,
          background: active ? 'var(--accent-container)' : 'transparent',
          color: active ? 'var(--on-accent-container)' : 'var(--on-surface-variant)',
          border: 'none', cursor: 'pointer',
          display: 'flex', alignItems: 'center', gap: 10,
          fontFamily: 'var(--font-sans)',
        }}>
          <span style={{
            fontFamily: 'var(--font-mono)', fontSize: 10, padding: '2px 6px',
            background: active ? 'rgba(255,255,255,0.12)' : 'var(--surface-high)',
            borderRadius: 4, color: active ? 'var(--on-accent-container)' : 'var(--on-surface-muted)',
          }}>{s.spec}</span>
          <span style={{ fontSize: 13, fontWeight: active ? 500 : 400 }}>{s.label}</span>
        </button>
      );
    })}
    <div style={{ marginTop: 'auto', padding: '12px 8px 0', fontSize: 10, fontFamily: 'var(--font-mono)', color: 'var(--on-surface-muted)', lineHeight: 1.6 }}>
      Kotlin · Jetpack Compose<br/>
      minSdk 29 · sideload<br/>
      no SDK · no telemetry
    </div>
  </div>
);

// ── Spec rail (right side) ───────────────────────────────────────
const SPEC_NOTES = {
  onboarding: {
    title: 'QR onboarding',
    bullets: [
      'CameraX preview + ML Kit barcode scanner (no Play Services dep — bundled).',
      'QR payload: JSON-CBOR {url, pin (sha256/base64), pkcs12 (b64), pkcs12_pw}.',
      'Identity goes into AndroidKeyStore as a non-exportable EC P-256 key + cert chain.',
      'mTLS verifies server with explicit pin BEFORE chain validation.',
      'Single-use; server marks token consumed once handshake completes.',
    ],
  },
  sessions: {
    title: 'Sessions list',
    bullets: [
      'GET /v1/sessions over mTLS, polled on resume + 10s ticker while foregrounded.',
      'Sessions are server-owned; the app holds no state besides last-seen list.',
      'Card avatar shows zero-padded N; status dot mirrors Docker container state.',
      'Tap → opens terminal; long-press → context menu (same as overflow).',
      'FAB triggers POST /v1/sessions with selected image; row appears as `starting`.',
    ],
  },
  'new-session': {
    title: 'New session sheet',
    bullets: [
      'M3 modal bottom sheet, drag-handle, swipe-to-dismiss.',
      'Label is the only field — image and hardware specs are fixed by the server profile, not selectable from the client.',
      'POST /v1/sessions with {label}; server picks the next free N and applies its own profile.',
      'Spawn is optimistic: row appears `starting`, then polls real status.',
    ],
  },
  delete: {
    title: 'Delete confirmation',
    bullets: [
      'DELETE /v1/sessions/{n}; server tears down container + tmux sessions.',
      'Warn explicitly when other clients are attached — destructive action.',
      'No undo (server-side); confirmation is mandatory.',
      'Error color uses M3 error container; destructive button is primary action.',
    ],
  },
  terminal: {
    title: 'Terminal',
    bullets: [
      'WebSocket: wss://host:12410/v1/sessions/{n}/stream, subprotocol ai-sandbox.v1.',
      'Per-client tmux session (u_<deviceId>) — independent sizing, no fighting over rows/cols.',
      'Foreground service (dataSync) keeps the socket alive across lock + task-switch.',
      'Long-press → selection menu (Copy/Paste/Select all/Send to). Two-finger scroll = scroll wheel.',
      'Rotation → JSON control frame {type:"resize", cols, rows} on the same WS.',
    ],
  },
  split: {
    title: 'Split / resize pane',
    bullets: [
      'tmux split-window -v on the per-client session — server-side, not client-rendered.',
      'Drag handle adjusts pane height; debounced resize control frame on release.',
      'Active pane is outlined with the accent color. Tap a pane to focus.',
      'Long-press handle: snap to 50/50, or close pane.',
    ],
  },
  notification: {
    title: 'Foreground notification',
    bullets: [
      'startForeground(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC).',
      'Ongoing (cannot be swiped) while a session is attached.',
      'Title shows session N; subtext shows ws state + idle time.',
      'Actions: Open (PendingIntent → MainActivity), Disconnect (broadcasts DISCONNECT → service stops).',
      'Survives doze; if killed, START_STICKY restarts and re-attaches.',
    ],
  },
  revoked: {
    title: 'Cert revoked',
    bullets: [
      'Server emits close frame with code 4401 + reason "revoked" within ≤1s of revocation.',
      'Client invalidates KeyStore entry, marks identity as expired.',
      'Sessions are dropped; user is taken back through the QR flow.',
      'No retry / no exponential backoff — re-enrollment is the only path.',
    ],
  },
  settings: {
    title: 'Settings',
    bullets: [
      'Surface server URL, pin, cert fingerprint — copyable for support.',
      'Diagnostics offers a "simulate revoke" affordance for testing the UC04-6 flow.',
      'No analytics toggle — there is no analytics. State this explicitly in the footer.',
      'Reset → clears KeyStore entry + cached sessions; returns to QR onboarding.',
    ],
  },
};

const SpecRail = ({ screenId }) => {
  const note = SPEC_NOTES[screenId] || SPEC_NOTES.sessions;
  return (
    <div className="scroll" style={{
      width: 280, flexShrink: 0, padding: '24px 22px',
      borderLeft: '1px solid var(--outline-variant)',
      background: 'rgba(0,0,0,0.25)',
      overflow: 'auto', display: 'flex', flexDirection: 'column', gap: 16,
    }}>
      <div>
        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, letterSpacing: 1, color: 'var(--on-surface-muted)' }}>
          IMPL NOTE · {SCREENS.find(s => s.id === screenId)?.spec || ''}
        </div>
        <div style={{ marginTop: 4, fontSize: 20, fontWeight: 500, color: 'var(--on-surface)', letterSpacing: -0.2 }}>
          {note.title}
        </div>
      </div>
      <ul style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 12 }}>
        {note.bullets.map((b, i) => (
          <li key={i} style={{ display: 'flex', gap: 10, alignItems: 'flex-start', fontSize: 13, lineHeight: 1.55, color: 'var(--on-surface-variant)' }}>
            <span style={{
              flexShrink: 0, marginTop: 6,
              width: 6, height: 6, borderRadius: '50%', background: 'var(--accent)',
            }}/>
            <span>{b}</span>
          </li>
        ))}
      </ul>
      <div style={{ marginTop: 'auto', padding: '12px 0 0', borderTop: '1px solid var(--outline-variant)', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--on-surface-muted)', lineHeight: 1.6 }}>
        Single TLS port · mTLS pinned<br/>
        WS subproto: ai-sandbox.v1<br/>
        ≤1s revoke teardown
      </div>
    </div>
  );
};

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
