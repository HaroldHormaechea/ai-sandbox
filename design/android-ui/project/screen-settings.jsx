// Screen: Settings — server pin, cert info, sandbox audit.
const ScreenSettings = ({ goto, onForceRevoke }) => (
  <div style={{ display: 'flex', flexDirection: 'column', flex: 1, overflow: 'hidden' }}>
    <M3TopBar
      title="Settings"
      leading={<M3IconButton onClick={() => goto('sessions')}><IcArrowBack size={22}/></M3IconButton>}
    />
    <div className="scroll" style={{ flex: 1, overflow: 'auto', padding: '0 20px 24px' }}>
      <Section label="Server">
        <SettingsRow
          icon={<IcDock size={20}/>}
          headline="ops.lan:12410"
          supporting="Single-port, mutual TLS"
          trailing={<StatusPill status="running">connected</StatusPill>}
        />
        <SettingsRow
          icon={<IcShield size={20}/>}
          headline="Server pin"
          supporting={<span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>sha256/9F:3A:7C:18:…:E1</span>}
          trailing={<M3IconButton size={36}><IcCopy size={18}/></M3IconButton>}
        />
      </Section>

      <Section label="Client identity">
        <CertCard />
      </Section>

      <Section label="WebSocket">
        <SettingsRow icon={<IcDot size={20}/>} headline="Subprotocol" supporting={<span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>ai-sandbox.v1</span>} />
        <SettingsRow icon={<IcRefresh size={20}/>} headline="Ping interval" supporting="30 s · auto-reconnect on lock" />
        <SettingsRow icon={<IcCircleDot size={20}/>} headline="Per-client tmux" supporting={<span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>u_42 · 124×38</span>} />
      </Section>

      <Section label="Diagnostics">
        <SettingsRow
          icon={<IcWarning size={20}/>} danger
          headline="Simulate cert revoke"
          supporting="Triggers the re-scan prompt (UC04-7)"
          onClick={onForceRevoke}
        />
      </Section>

      <div style={{ marginTop: 32, textAlign: 'center', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--on-surface-muted)' }}>
        ai-sandbox-android 0.1.0 · minSdk 29<br/>
        sideload · no telemetry · no SDK
      </div>
    </div>
  </div>
);

const Section = ({ label, children }) => (
  <div style={{ marginTop: 20 }}>
    <div style={{ padding: '8px 4px', fontSize: 11, letterSpacing: 1, textTransform: 'uppercase', color: 'var(--on-surface-muted)' }}>{label}</div>
    <div style={{ background: 'var(--surface-low)', borderRadius: 16, overflow: 'hidden' }}>
      {children}
    </div>
  </div>
);

const SettingsRow = ({ icon, headline, supporting, trailing, danger = false, onClick }) => (
  <div onClick={onClick} style={{
    display: 'flex', alignItems: 'center', gap: 14,
    padding: '14px 16px', minHeight: 64, boxSizing: 'border-box',
    cursor: onClick ? 'pointer' : 'default',
    borderBottom: '1px solid var(--outline-variant)',
  }}>
    <div style={{
      width: 36, height: 36, borderRadius: 12,
      background: danger ? 'var(--error-container)' : 'var(--surface-high)',
      color: danger ? 'var(--error)' : 'var(--on-surface-variant)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
    }}>
      {icon}
    </div>
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ fontSize: 15, fontWeight: 500, color: danger ? 'var(--error)' : 'var(--on-surface)' }}>{headline}</div>
      {supporting && <div style={{ marginTop: 2, fontSize: 13, color: 'var(--on-surface-variant)' }}>{supporting}</div>}
    </div>
    {trailing && <div style={{ flexShrink: 0 }}>{trailing}</div>}
  </div>
);

const CertCard = () => (
  <div style={{ padding: '14px 16px' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
      <div style={{
        width: 40, height: 40, borderRadius: 12,
        background: 'var(--accent-container)', color: 'var(--on-accent-container)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}><IcLock size={22}/></div>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 15, fontWeight: 500 }}>Pixel-8-Tao</div>
        <div style={{ fontSize: 12, color: 'var(--on-surface-muted)', fontFamily: 'var(--font-mono)' }}>stored in Android KeyStore · non-exportable</div>
      </div>
    </div>
    <div style={{ marginTop: 14, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, fontFamily: 'var(--font-mono)', fontSize: 12 }}>
      <KV k="ISSUED" v="2026-05-14" />
      <KV k="EXPIRES" v="2026-11-12" />
      <KV k="SERIAL" v="0x4f::a1" />
      <KV k="KEY" v="P-256 EC" />
    </div>
    <div style={{ marginTop: 12, padding: '10px 12px', background: 'var(--surface-high)', borderRadius: 10, fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--on-surface-variant)', wordBreak: 'break-all' }}>
      fpr  sha256/3B:7E:F1:24:90:CC:48:1A:E2:0B:7F:55:36:9D:84:A7:CE:11:62:F8:33:4D:09:E6:71:5A:8C:D2:1F:46:B8:90
    </div>
  </div>
);

const KV = ({ k, v }) => (
  <div>
    <div style={{ color: 'var(--on-surface-muted)', fontSize: 10, letterSpacing: 0.6 }}>{k}</div>
    <div style={{ color: 'var(--on-surface)' }}>{v}</div>
  </div>
);

window.ScreenSettings = ScreenSettings;
