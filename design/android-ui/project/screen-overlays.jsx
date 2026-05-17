// Overlays (sheets / dialogs) and the foreground-service notification screen.

const NewSessionSheet = ({ open, onClose, onCreate }) => {
  const [name, setName] = React.useState('');
  return (
    <M3Sheet open={open} onClose={onClose}>
      <div style={{ padding: '0 24px 24px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ fontSize: 22, fontWeight: 500, letterSpacing: -0.2 }}>New session</div>
          <M3IconButton onClick={onClose}><IcClose size={20}/></M3IconButton>
        </div>
        <div style={{ marginTop: 4, fontSize: 13, color: 'var(--on-surface-variant)' }}>
          Server spawns a fresh Docker container with mTLS already attached. The next free slot is&nbsp;
          <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--on-surface)' }}>ai-sandbox-13</span>.
        </div>

        <Field label="Label (optional)">
          <TextInput value={name} onChange={setName} placeholder="e.g. release-build, scratch" />
          <div style={{ marginTop: 8, fontSize: 12, color: 'var(--on-surface-muted)', lineHeight: 1.5 }}>
            Image and hardware specs are fixed by the server profile — not selectable from the client.
          </div>
        </Field>

        <div style={{ display: 'flex', gap: 8, marginTop: 24, justifyContent: 'flex-end' }}>
          <M3Button variant="text" onClick={onClose}>Cancel</M3Button>
          <M3Button onClick={() => onCreate({ name })}>Spawn</M3Button>
        </div>
      </div>
    </M3Sheet>
  );
};

const Field = ({ label, children }) => (
  <div style={{ marginTop: 20 }}>
    <div style={{ fontSize: 12, letterSpacing: 0.6, textTransform: 'uppercase', color: 'var(--on-surface-muted)' }}>{label}</div>
    {children}
  </div>
);

const TextInput = ({ value, onChange, placeholder }) => (
  <input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
    style={{
      marginTop: 8, width: '100%', height: 48,
      background: 'var(--surface-low)', border: '1px solid var(--outline-variant)',
      borderRadius: 12, padding: '0 14px',
      color: 'var(--on-surface)', fontFamily: 'var(--font-mono)', fontSize: 14,
      outline: 'none',
    }}
    onFocus={e => e.target.style.borderColor = 'var(--accent)'}
    onBlur={e => e.target.style.borderColor = 'var(--outline-variant)'}
  />
);

const KVStat = ({ label, value }) => (
  <div style={{ background: 'var(--surface-low)', borderRadius: 12, padding: '10px 14px' }}>
    <div style={{ fontSize: 11, letterSpacing: 0.6, color: 'var(--on-surface-muted)' }}>{label}</div>
    <div style={{ fontFamily: 'var(--font-mono)', fontSize: 14, color: 'var(--on-surface)' }}>{value}</div>
  </div>
);

// ── Delete confirmation ──────────────────────────────────────────
const DeleteDialog = ({ open, session, onClose, onConfirm }) => (
  <M3Dialog
    open={open}
    onClose={onClose}
    icon={<IcDelete size={28}/>}
    title={`Delete ${session?.name || ''}?`}
    body={
      <>
        The container and tmux session are destroyed on the server.
        {session?.attached > 0 && <>
          {' '}<span style={{ color: 'var(--warning)' }}>{session.attached} client{session.attached > 1 ? 's are' : ' is'} currently attached</span> and will be disconnected.
        </>}
      </>
    }
    actions={
      <>
        <M3Button variant="text" onClick={onClose}>Cancel</M3Button>
        <M3Button variant="error" onClick={onConfirm}>Delete</M3Button>
      </>
    }
  />
);

// ── Cert-revoked / re-scan prompt ────────────────────────────────
const CertRevokedDialog = ({ open, onClose, onRescan }) => (
  <M3Dialog
    open={open}
    onClose={onClose}
    icon={
      <div style={{ width: 56, height: 56, borderRadius: 18, background: 'var(--error-container)', color: 'var(--error)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <IcShield size={28}/>
      </div>
    }
    title="Identity revoked"
    body={
      <>
        The server tore down this device's certificate. All active sessions disconnected.{' '}
        Ask an admin to issue a new QR with <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--on-surface)' }}>sandboxctl client invite</span>, then re-scan to continue.
      </>
    }
    actions={
      <>
        <M3Button variant="text" onClick={onClose}>Later</M3Button>
        <M3Button onClick={onRescan} leading={<IcQr size={16}/>}>Scan new QR</M3Button>
      </>
    }
  />
);

// ── Foreground-service notification screen ───────────────────────
// Renders a system-level notification shade with our app's persistent entry.
const ScreenNotification = ({ goto, sessionN = 4, onDisconnect }) => (
  <div style={{ flex: 1, position: 'relative', background: '#0a0a0c', overflow: 'hidden' }}>
    {/* Faked blurred wallpaper underneath */}
    <div style={{
      position: 'absolute', inset: 0,
      background: `
        radial-gradient(circle at 30% 20%, color-mix(in oklab, var(--accent) 25%, transparent), transparent 50%),
        radial-gradient(circle at 70% 70%, color-mix(in oklab, var(--accent) 18%, transparent), transparent 55%),
        #0a0a0c
      `,
      filter: 'blur(40px) saturate(120%)',
    }}/>
    <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)' }} />

    {/* QS chips row */}
    <div style={{ position: 'relative', padding: '20px 16px 0', display: 'flex', justifyContent: 'space-between', color: 'rgba(255,255,255,0.7)', fontSize: 12, fontFamily: 'var(--font-mono)' }}>
      <span>Tue 17 May · 14:32</span>
      <span>87%</span>
    </div>
    <div style={{ position: 'relative', display: 'flex', gap: 8, padding: '12px 16px 20px', flexWrap: 'wrap' }}>
      {['Wi-Fi','Bluetooth','Do not disturb','Flashlight'].map(t => (
        <div key={t} style={{
          padding: '10px 16px', borderRadius: 100,
          background: 'rgba(255,255,255,0.08)', color: 'rgba(255,255,255,0.85)',
          fontSize: 13, fontFamily: 'var(--font-sans)',
        }}>{t}</div>
      ))}
    </div>

    {/* Section label */}
    <div style={{ position: 'relative', padding: '0 24px 8px', color: 'rgba(255,255,255,0.55)', fontSize: 12, fontFamily: 'var(--font-sans)', letterSpacing: 0.4 }}>
      ONGOING
    </div>

    {/* Our notification */}
    <div style={{ position: 'relative', margin: '0 12px 12px', borderRadius: 24, overflow: 'hidden', background: 'rgba(36,34,40,0.92)', backdropFilter: 'blur(20px)' }}>
      <div style={{ padding: '14px 18px 12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <div style={{ width: 22, height: 22, borderRadius: 6, background: 'var(--accent)', color: 'var(--on-accent)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: 'var(--font-mono)', fontSize: 12, fontWeight: 700 }}>
            S
          </div>
          <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.7)' }}>ai-sandbox</span>
          <span style={{ width: 3, height: 3, borderRadius: '50%', background: 'rgba(255,255,255,0.4)' }}/>
          <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.5)', fontFamily: 'var(--font-mono)' }}>now</span>
          <div style={{ flex: 1 }}/>
          <span style={{
            padding: '3px 8px', borderRadius: 100, background: 'rgba(138,214,165,0.18)',
            color: 'var(--success)', fontSize: 10, fontFamily: 'var(--font-mono)', letterSpacing: 0.5,
          }}>FOREGROUND · dataSync</span>
        </div>
        <div style={{ fontSize: 14, color: '#fff', fontWeight: 500 }}>
          Attached to ai-sandbox-{sessionN}
        </div>
        <div style={{ marginTop: 2, fontSize: 13, color: 'rgba(255,255,255,0.7)', fontFamily: 'var(--font-mono)' }}>
          wss://ops.lan:12410 · 124×38 · idle 4s
        </div>
      </div>
      <div style={{ display: 'flex', borderTop: '1px solid rgba(255,255,255,0.08)' }}>
        <NotifAction label="Open" onClick={() => goto('terminal', { sessionN })} />
        <NotifAction label="Disconnect" tone="error" onClick={onDisconnect} />
      </div>
    </div>

    {/* Hint */}
    <div style={{ position: 'relative', textAlign: 'center', marginTop: 32, color: 'rgba(255,255,255,0.5)', fontSize: 12, fontFamily: 'var(--font-sans)' }}>
      Swipe up to dismiss · Lock screen presents the same entry
    </div>
  </div>
);

const NotifAction = ({ label, tone, onClick }) => (
  <button onClick={onClick} style={{
    flex: 1, height: 48, background: 'transparent', border: 'none',
    color: tone === 'error' ? 'var(--error)' : 'var(--accent)',
    fontSize: 14, fontFamily: 'var(--font-sans)', fontWeight: 500,
    cursor: 'pointer', letterSpacing: 0.1,
  }}>{label}</button>
);

Object.assign(window, { NewSessionSheet, DeleteDialog, CertRevokedDialog, ScreenNotification });
