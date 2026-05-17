// Shared M3-flavored primitives: buttons, chips, dividers, sheets.
// Dark-only; colors come from CSS variables in index.html.

const M3Surface = ({ tone = 'low', children, style, ...rest }) => {
  const bg = {
    base:    'var(--surface)',
    low:     'var(--surface-low)',
    high:    'var(--surface-high)',
    highest: 'var(--surface-highest)',
  }[tone] || tone;
  return (
    <div style={{ background: bg, ...style }} {...rest}>{children}</div>
  );
};

const M3Button = ({ variant = 'filled', children, leading, trailing, onClick, style = {}, full = false, disabled = false }) => {
  const base = {
    height: 40,
    padding: '0 24px',
    borderRadius: 100,
    fontFamily: 'var(--font-sans)',
    fontSize: 14,
    fontWeight: 500,
    letterSpacing: 0.1,
    cursor: disabled ? 'not-allowed' : 'pointer',
    border: 'none',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    width: full ? '100%' : 'auto',
    opacity: disabled ? 0.4 : 1,
    transition: 'background 120ms, transform 80ms',
    userSelect: 'none',
  };
  const variants = {
    filled:  { background: 'var(--accent)',           color: 'var(--on-accent)' },
    tonal:   { background: 'var(--accent-container)', color: 'var(--on-accent-container)' },
    outline: { background: 'transparent', color: 'var(--accent)', border: '1px solid var(--outline)' },
    text:    { background: 'transparent', color: 'var(--accent)', padding: '0 12px' },
    error:   { background: 'var(--error-container)',  color: 'var(--error)' },
  };
  return (
    <button onClick={disabled ? undefined : onClick} style={{ ...base, ...variants[variant], ...style }}
            onMouseDown={e => e.currentTarget.style.transform = 'scale(0.97)'}
            onMouseUp={e => e.currentTarget.style.transform = 'scale(1)'}
            onMouseLeave={e => e.currentTarget.style.transform = 'scale(1)'}>
      {leading}
      <span>{children}</span>
      {trailing}
    </button>
  );
};

const M3IconButton = ({ children, onClick, style = {}, size = 40, badge, tone = 'plain' }) => {
  const bg = tone === 'tonal' ? 'var(--surface-high)' : 'transparent';
  return (
    <button onClick={onClick} aria-label=""
      style={{
        width: size, height: size, borderRadius: '50%',
        background: bg, border: 'none', color: 'var(--on-surface)',
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        cursor: 'pointer', position: 'relative', ...style,
      }}>
      {children}
      {badge && (
        <span style={{
          position: 'absolute', top: 6, right: 6, minWidth: 8, height: 8,
          background: 'var(--error)', borderRadius: 100,
        }}/>
      )}
    </button>
  );
};

const M3Chip = ({ children, leading, selected = false, onClick, style = {} }) => (
  <button onClick={onClick} style={{
    height: 32, padding: leading ? '0 12px 0 8px' : '0 12px',
    border: `1px solid ${selected ? 'transparent' : 'var(--outline-variant)'}`,
    background: selected ? 'var(--accent-container)' : 'transparent',
    color: selected ? 'var(--on-accent-container)' : 'var(--on-surface-variant)',
    borderRadius: 8, fontFamily: 'var(--font-sans)', fontSize: 13, fontWeight: 500,
    display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer',
    ...style,
  }}>
    {leading}
    {children}
  </button>
);

const Divider = ({ style = {} }) => (
  <div style={{ height: 1, background: 'var(--outline-variant)', ...style }} />
);

// Floating Action Button (M3 Expressive — rounded square, primary container)
const M3FAB = ({ children, onClick, label, extended = true, style = {} }) => (
  <button onClick={onClick} style={{
    height: 56, minWidth: extended ? 0 : 56,
    padding: extended ? '0 20px 0 16px' : 0,
    background: 'var(--accent)', color: 'var(--on-accent)',
    border: 'none', borderRadius: 16,
    fontFamily: 'var(--font-sans)', fontSize: 14, fontWeight: 600, letterSpacing: 0.1,
    display: 'inline-flex', alignItems: 'center', gap: 8, cursor: 'pointer',
    boxShadow: '0 8px 24px rgba(0,0,0,0.35), 0 1px 0 rgba(255,255,255,0.08) inset',
    ...style,
  }}>
    {children}
    {extended && label}
  </button>
);

// M3 top app bar (custom — replaces the one in android-frame.jsx)
const M3TopBar = ({ title, leading, trailing, large = false, subtitle }) => (
  <div style={{
    padding: large ? '8px 4px 16px' : '4px 4px',
    background: 'transparent',
    flexShrink: 0,
  }}>
    <div style={{ height: 64, display: 'flex', alignItems: 'center', gap: 4, padding: '0 4px' }}>
      <div style={{ width: 48, height: 48, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {leading}
      </div>
      {!large && (
        <div style={{ flex: 1, fontSize: 22, fontWeight: 500, color: 'var(--on-surface)', letterSpacing: -0.2 }}>{title}</div>
      )}
      {large && <div style={{ flex: 1 }} />}
      <div style={{ display: 'flex', alignItems: 'center' }}>{trailing}</div>
    </div>
    {large && (
      <div style={{ padding: '8px 20px 4px' }}>
        <div style={{ fontSize: 32, fontWeight: 500, color: 'var(--on-surface)', letterSpacing: -0.5, lineHeight: 1.1 }}>{title}</div>
        {subtitle && <div style={{ marginTop: 4, fontSize: 14, color: 'var(--on-surface-variant)' }}>{subtitle}</div>}
      </div>
    )}
  </div>
);

// Status pill — small dot + label
const StatusPill = ({ status = 'running', children }) => {
  const map = {
    running:  { dot: 'var(--success)',  label: children || 'running' },
    starting: { dot: 'var(--warning)',  label: children || 'starting' },
    stopped:  { dot: 'var(--on-surface-muted)', label: children || 'stopped' },
    error:    { dot: 'var(--error)',    label: children || 'error' },
  };
  const s = map[status] || map.running;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '4px 10px 4px 8px',
      background: 'var(--surface-high)', borderRadius: 100,
      fontSize: 12, color: 'var(--on-surface-variant)', fontFamily: 'var(--font-mono)',
    }}>
      <span style={{ width: 7, height: 7, borderRadius: '50%', background: s.dot, boxShadow: `0 0 8px ${s.dot}66` }} />
      {s.label}
    </span>
  );
};

// Bottom sheet (modal). Slides up.
const M3Sheet = ({ open, onClose, children, height = 'auto', dismissable = true }) => {
  if (!open) return null;
  return (
    <div onClick={dismissable ? onClose : undefined} style={{
      position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)',
      display: 'flex', alignItems: 'flex-end', zIndex: 50,
      animation: 'sheetFade 200ms ease',
    }}>
      <div onClick={e => e.stopPropagation()} style={{
        width: '100%', background: 'var(--surface)', height,
        borderRadius: '28px 28px 0 0',
        padding: '12px 0 0', boxSizing: 'border-box',
        animation: 'sheetUp 240ms cubic-bezier(.2,.8,.2,1)',
      }}>
        {dismissable && (
          <div style={{ height: 4, width: 32, background: 'var(--outline-variant)', borderRadius: 2, margin: '0 auto 16px' }} />
        )}
        {children}
      </div>
    </div>
  );
};

// Dialog (centered, M3)
const M3Dialog = ({ open, icon, title, body, actions, onClose }) => {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{
      position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: 24, zIndex: 50,
    }}>
      <div onClick={e => e.stopPropagation()} style={{
        background: 'var(--surface-high)', borderRadius: 28,
        padding: '24px 24px 18px', width: '100%', maxWidth: 320,
        animation: 'dialogIn 200ms cubic-bezier(.2,.8,.2,1)',
      }}>
        {icon && (
          <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 16, color: 'var(--accent)' }}>{icon}</div>
        )}
        <div style={{ fontSize: 24, fontWeight: 500, color: 'var(--on-surface)', textAlign: icon ? 'center' : 'left', letterSpacing: -0.2 }}>{title}</div>
        <div style={{ marginTop: 16, fontSize: 14, lineHeight: 1.5, color: 'var(--on-surface-variant)', textAlign: icon ? 'center' : 'left' }}>{body}</div>
        <div style={{ marginTop: 24, display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>{actions}</div>
      </div>
    </div>
  );
};

// Helper: animation keyframes injected once
(function injectAnims() {
  if (document.getElementById('__anim_kf')) return;
  const s = document.createElement('style');
  s.id = '__anim_kf';
  s.textContent = `
    @keyframes sheetFade { from { opacity: 0; } to { opacity: 1; } }
    @keyframes sheetUp { from { transform: translateY(100%); } to { transform: translateY(0); } }
    @keyframes dialogIn { from { opacity: 0; transform: scale(0.94); } to { opacity: 1; transform: scale(1); } }
    @keyframes blink { 0%, 50% { opacity: 1; } 51%, 100% { opacity: 0; } }
    @keyframes pulse { 0%, 100% { transform: scale(1); opacity: 1; } 50% { transform: scale(1.04); opacity: 0.85; } }
    @keyframes spin { to { transform: rotate(360deg); } }
    @keyframes scanLine { 0% { transform: translateY(0); } 100% { transform: translateY(100%); } }
    @keyframes slideUp { from { transform: translateY(8px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
    @keyframes logIn { from { opacity: 0; } to { opacity: 1; } }
  `;
  document.head.appendChild(s);
})();

Object.assign(window, {
  M3Surface, M3Button, M3IconButton, M3Chip, Divider,
  M3FAB, M3TopBar, StatusPill, M3Sheet, M3Dialog,
});
