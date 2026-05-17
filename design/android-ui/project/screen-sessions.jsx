// Screen: Sessions list (UC04-2) — home with running ai-sandbox-N
const SESSIONS_SEED = [
  { n: 1, name: 'ai-sandbox-1', status: 'running',  attached: 2, image: 'aisandbox:ubuntu-24.04', uptime: '3d 14h', cpu: 12, mem: '482 MB' },
  { n: 4, name: 'ai-sandbox-4', status: 'running',  attached: 1, image: 'aisandbox:archlinux',    uptime: '6h 02m',  cpu: 38, mem: '1.1 GB' },
  { n: 7, name: 'ai-sandbox-7', status: 'starting', attached: 0, image: 'aisandbox:debian-trixie',uptime: '00m 08s', cpu: 0,  mem: '64 MB'  },
  { n: 9, name: 'ai-sandbox-9', status: 'running',  attached: 0, image: 'aisandbox:nixos-24.05',  uptime: '17h 22m', cpu: 4,  mem: '298 MB' },
  { n: 12,name: 'ai-sandbox-12',status: 'stopped',  attached: 0, image: 'aisandbox:ubuntu-24.04', uptime: '—',       cpu: 0,  mem: '0 MB'   },
];

const ScreenSessions = ({ goto, sessions, onDeleteAsk, onNew, density = 'cards' }) => {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, position: 'relative', overflow: 'hidden' }}>
      <M3TopBar
        large
        title="ai-sandbox"
        subtitle={<><span style={{ color: 'var(--success)' }}>●</span>&nbsp; ops.lan:12410 · mTLS</>}
        leading={<M3IconButton><IcShield size={22} /></M3IconButton>}
        trailing={<>
          <M3IconButton onClick={() => goto('settings')}><IcSettings size={22} /></M3IconButton>
          <M3IconButton><IcMore size={22} /></M3IconButton>
        </>}
      />

      {/* Filter chips */}
      <div style={{ display: 'flex', gap: 8, padding: '0 20px 12px', flexShrink: 0 }}>
        <M3Chip selected leading={<IcDot size={16} />}>All · {sessions.length}</M3Chip>
        <M3Chip>Running · {sessions.filter(s => s.status === 'running').length}</M3Chip>
        <M3Chip>Stopped</M3Chip>
      </div>

      {/* Session list */}
      <div className="scroll" style={{ flex: 1, overflow: 'auto', padding: density === 'compact' ? '4px 0 96px' : '4px 16px 96px' }}>
        {sessions.length === 0 && <EmptyState onNew={onNew} />}
        {sessions.map(s => (
          <SessionRow key={s.n} s={s}
            density={density}
            onOpen={() => goto('terminal', { sessionN: s.n })}
            onDelete={() => onDeleteAsk(s)} />
        ))}
      </div>

      {/* Extended FAB */}
      <div style={{ position: 'absolute', right: 16, bottom: 16 }}>
        <M3FAB onClick={onNew} label="New session"><IcAdd size={22} /></M3FAB>
      </div>
    </div>
  );
};

const SessionRow = ({ s, density, onOpen, onDelete }) => {
  if (density === 'compact') {
    return (
      <div onClick={s.status !== 'starting' ? onOpen : undefined} style={{
        display: 'grid', gridTemplateColumns: '40px 1fr auto auto',
        alignItems: 'center', gap: 12, padding: '10px 20px',
        cursor: s.status !== 'starting' ? 'pointer' : 'default',
        borderBottom: '1px solid var(--outline-variant)',
      }}>
        <Avatar n={s.n} status={s.status} />
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 15, color: 'var(--on-surface)', fontFamily: 'var(--font-mono)' }}>{s.name}</div>
          <div style={{ fontSize: 12, color: 'var(--on-surface-muted)', fontFamily: 'var(--font-mono)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.image}</div>
        </div>
        <div style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--on-surface-variant)', textAlign: 'right' }}>{s.uptime}</div>
        <M3IconButton onClick={(e) => { e.stopPropagation(); onDelete(); }} size={36}><IcMore size={20}/></M3IconButton>
      </div>
    );
  }
  if (density === 'rows') {
    return (
      <div onClick={s.status !== 'starting' ? onOpen : undefined} style={{
        display: 'flex', alignItems: 'center', gap: 14, padding: '14px 20px',
        cursor: s.status !== 'starting' ? 'pointer' : 'default',
      }}>
        <Avatar n={s.n} status={s.status} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 16, fontWeight: 500, color: 'var(--on-surface)' }}>{s.name}</span>
            <StatusPill status={s.status} />
          </div>
          <div style={{ marginTop: 2, fontSize: 12, color: 'var(--on-surface-muted)', fontFamily: 'var(--font-mono)' }}>
            {s.image} · up {s.uptime}{s.attached > 0 ? ` · ${s.attached} attached` : ''}
          </div>
        </div>
        <M3IconButton onClick={(e) => { e.stopPropagation(); onDelete(); }}><IcMore size={20}/></M3IconButton>
      </div>
    );
  }
  // cards (default)
  return (
    <div onClick={s.status !== 'starting' ? onOpen : undefined} style={{
      background: 'var(--surface-low)', borderRadius: 20,
      padding: 16, marginBottom: 10,
      cursor: s.status !== 'starting' ? 'pointer' : 'default',
      border: '1px solid var(--outline-variant)',
      display: 'flex', flexDirection: 'column', gap: 10,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <Avatar n={s.n} status={s.status} large />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <span style={{ fontSize: 18, fontWeight: 500, color: 'var(--on-surface)' }}>{s.name}</span>
            <StatusPill status={s.status} />
          </div>
          <div style={{ marginTop: 4, fontSize: 12, color: 'var(--on-surface-muted)', fontFamily: 'var(--font-mono)' }}>{s.image}</div>
        </div>
        <M3IconButton onClick={(e) => { e.stopPropagation(); onDelete(); }} size={36}><IcMore size={20}/></M3IconButton>
      </div>
      {/* Metrics row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, paddingTop: 6, borderTop: '1px solid var(--outline-variant)' }}>
        <Metric label="uptime" value={s.uptime} />
        <Metric label="cpu" value={`${s.cpu}%`} />
        <Metric label="mem" value={s.mem} />
      </div>
    </div>
  );
};

const Avatar = ({ n, status, large = false }) => (
  <div style={{
    width: large ? 44 : 36, height: large ? 44 : 36,
    borderRadius: 12,
    background: status === 'stopped' ? 'var(--surface-high)' : 'var(--accent-container)',
    color: status === 'stopped' ? 'var(--on-surface-muted)' : 'var(--on-accent-container)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontFamily: 'var(--font-mono)', fontSize: large ? 18 : 15, fontWeight: 600,
    flexShrink: 0,
    border: status === 'starting' ? '2px solid var(--warning)' : 'none',
  }}>
    {String(n).padStart(2, '0')}
  </div>
);

const Metric = ({ label, value }) => (
  <div>
    <div style={{ fontSize: 10, letterSpacing: 1, textTransform: 'uppercase', color: 'var(--on-surface-muted)' }}>{label}</div>
    <div style={{ marginTop: 2, fontSize: 14, fontFamily: 'var(--font-mono)', color: 'var(--on-surface)' }}>{value}</div>
  </div>
);

const EmptyState = ({ onNew }) => (
  <div style={{ padding: '64px 32px', textAlign: 'center' }}>
    <div style={{
      width: 72, height: 72, borderRadius: 24, background: 'var(--surface-low)',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      color: 'var(--on-surface-muted)', marginBottom: 20,
    }}>
      <IcTerminal size={36} />
    </div>
    <div style={{ fontSize: 20, fontWeight: 500 }}>No sessions yet</div>
    <div style={{ marginTop: 6, fontSize: 14, color: 'var(--on-surface-variant)' }}>Spawn a Docker sandbox to start.</div>
    <M3Button style={{ marginTop: 20 }} onClick={onNew} leading={<IcAdd size={18}/>}>New session</M3Button>
  </div>
);

window.ScreenSessions = ScreenSessions;
window.SESSIONS_SEED = SESSIONS_SEED;
