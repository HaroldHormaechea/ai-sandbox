// Screen: Terminal (UC04-3) — the meat. Mock build/log stream.
const ScreenTerminal = ({ goto, sessionN = 4, modBarMode = 'docked', onCertRevoked }) => {
  const [showSelectionMenu, setShowSelectionMenu] = React.useState(false);
  const [splitMode, setSplitMode] = React.useState(false); // 2-pane
  const [showFnRow, setShowFnRow] = React.useState(false);
  const [tmuxPrefixArmed, setTmuxPrefixArmed] = React.useState(false);
  const [floatingExpanded, setFloatingExpanded] = React.useState(false);
  const logRef = React.useRef(null);

  // Auto-stream logs in
  const [streamed, setStreamed] = React.useState(8);
  React.useEffect(() => {
    if (streamed >= BUILD_LOG.length) return;
    const t = setTimeout(() => setStreamed(s => Math.min(BUILD_LOG.length, s + 1)), 320 + Math.random() * 320);
    return () => clearTimeout(t);
  }, [streamed]);

  React.useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [streamed]);

  // tmux prefix auto-disarm
  React.useEffect(() => {
    if (!tmuxPrefixArmed) return;
    const t = setTimeout(() => setTmuxPrefixArmed(false), 1800);
    return () => clearTimeout(t);
  }, [tmuxPrefixArmed]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, background: '#0a0a0c', position: 'relative', overflow: 'hidden' }}>
      {/* Top bar — compact, mono */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 4, padding: '4px 4px 4px 0',
        background: 'var(--surface-low)', borderBottom: '1px solid var(--outline-variant)',
        flexShrink: 0,
      }}>
        <M3IconButton onClick={() => goto('sessions')}><IcArrowBack size={22}/></M3IconButton>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: 15, color: 'var(--on-surface)' }}>
              ai-sandbox-{sessionN}
            </span>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--success)', boxShadow: '0 0 6px var(--success)' }}/>
          </div>
          <div style={{ fontSize: 11, fontFamily: 'var(--font-mono)', color: 'var(--on-surface-muted)' }}>
            wss · subproto ai-sandbox.v1 · 124×38 · tmux u_42
          </div>
        </div>
        <M3IconButton onClick={() => setSplitMode(s => !s)} style={{ color: splitMode ? 'var(--accent)' : 'var(--on-surface)' }}>
          <IcSplit size={20}/>
        </M3IconButton>
        <M3IconButton onClick={onCertRevoked}><IcMore size={22}/></M3IconButton>
      </div>

      {/* Tmux prefix banner */}
      {tmuxPrefixArmed && (
        <div style={{
          padding: '6px 16px', background: 'var(--accent-container)', color: 'var(--on-accent-container)',
          fontSize: 12, fontFamily: 'var(--font-mono)', textAlign: 'center', flexShrink: 0,
          animation: 'slideUp 120ms ease',
        }}>
          tmux prefix armed — next key sends C-b·{'<key>'}
        </div>
      )}

      {/* Terminal body */}
      <div style={{ flex: 1, display: 'flex', flexDirection: splitMode ? 'column' : 'row', overflow: 'hidden', minHeight: 0 }}>
        <TerminalPane
          streamed={streamed}
          showSelectionMenu={showSelectionMenu}
          onLongPress={() => setShowSelectionMenu(true)}
          onDismissSelection={() => setShowSelectionMenu(false)}
          logRef={logRef}
          paneLabel={splitMode ? '0' : null}
        />
        {splitMode && (
          <>
            <div style={{
              height: 6, background: 'var(--surface-high)', position: 'relative',
              cursor: 'row-resize', display: 'flex', alignItems: 'center', justifyContent: 'center',
              borderTop: '1px solid var(--outline-variant)', borderBottom: '1px solid var(--outline-variant)',
            }}>
              <div style={{ width: 32, height: 2, background: 'var(--outline)', borderRadius: 1 }} />
            </div>
            <SecondPane />
          </>
        )}
      </div>

      {/* Modifier bar — three variants */}
      {modBarMode === 'docked' && (
        <ModifierBar
          showFnRow={showFnRow}
          tmuxArmed={tmuxPrefixArmed}
          onTmuxPrefix={() => setTmuxPrefixArmed(a => !a)}
          onToggleFn={() => setShowFnRow(f => !f)}
        />
      )}
      {modBarMode === 'floating' && (
        <FloatingModBar expanded={floatingExpanded} setExpanded={setFloatingExpanded}
          tmuxArmed={tmuxPrefixArmed} onTmuxPrefix={() => setTmuxPrefixArmed(a => !a)} />
      )}
      {modBarMode === 'collapsible' && (
        <CollapsibleModBar
          tmuxArmed={tmuxPrefixArmed}
          onTmuxPrefix={() => setTmuxPrefixArmed(a => !a)}
        />
      )}

      {/* Long-press selection menu */}
      {showSelectionMenu && (
        <div style={{
          position: 'absolute', left: 24, top: 180,
          background: 'var(--surface-high)', borderRadius: 12,
          boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
          padding: 4, display: 'flex', gap: 2,
          animation: 'dialogIn 160ms ease',
          zIndex: 10,
        }}>
          {['Copy','Paste','Select all','Send to…'].map(label => (
            <button key={label} onClick={() => setShowSelectionMenu(false)} style={{
              padding: '8px 14px', background: 'transparent', border: 'none',
              color: 'var(--on-surface)', fontSize: 13, fontFamily: 'var(--font-sans)',
              borderRadius: 8, cursor: 'pointer',
            }}>{label}</button>
          ))}
        </div>
      )}
    </div>
  );
};

const TerminalPane = ({ streamed, onLongPress, paneLabel, logRef }) => {
  const lvlColor = {
    info: 'var(--on-surface)',
    warn: 'var(--warning)',
    good: 'var(--success)',
    err:  'var(--error)',
  };
  const pressTimer = React.useRef(null);
  const start = () => { pressTimer.current = setTimeout(onLongPress, 420); };
  const cancel = () => { clearTimeout(pressTimer.current); };

  return (
    <div onTouchStart={start} onTouchEnd={cancel} onMouseDown={start} onMouseUp={cancel} onMouseLeave={cancel}
      style={{
        flex: 1, position: 'relative', overflow: 'hidden', background: '#0a0a0c',
        borderLeft: paneLabel ? '2px solid var(--accent)' : 'none',
      }}>
      {paneLabel && (
        <div style={{
          position: 'absolute', top: 6, left: 8, padding: '2px 8px',
          background: 'var(--accent-container)', color: 'var(--on-accent-container)',
          borderRadius: 6, fontFamily: 'var(--font-mono)', fontSize: 10, zIndex: 2,
        }}>
          pane {paneLabel}
        </div>
      )}
      <div ref={logRef} className="scroll" style={{
        position: 'absolute', inset: 0, overflow: 'auto', padding: '10px 12px 14px',
        fontFamily: 'var(--font-mono)', fontSize: 12.5, lineHeight: 1.55,
        color: 'var(--on-surface)',
      }}>
        {BUILD_LOG.slice(0, streamed).map((l, i) => (
          <div key={i} style={{ display: 'flex', gap: 10, whiteSpace: 'pre-wrap', animation: i >= streamed - 2 ? 'logIn 160ms ease' : undefined }}>
            <span style={{ color: 'var(--on-surface-muted)', flexShrink: 0 }}>{l.t}</span>
            <span style={{ color: lvlColor[l.lvl], flex: 1, wordBreak: 'break-word' }}>{l.text}</span>
          </div>
        ))}
        {/* prompt line */}
        <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
          <span style={{ color: 'var(--success)' }}>root@ai-sbx-4</span>
          <span style={{ color: 'var(--on-surface-muted)' }}>:~#</span>
          <span style={{ width: 8, height: 16, background: 'var(--accent)', display: 'inline-block', animation: 'blink 1.05s steps(2) infinite' }} />
        </div>
      </div>
    </div>
  );
};

const SecondPane = () => (
  <div style={{ flex: 1, background: '#0a0a0c', padding: '10px 12px', overflow: 'hidden', fontFamily: 'var(--font-mono)', fontSize: 12.5, color: 'var(--on-surface)' }}>
    <div style={{ color: 'var(--on-surface-muted)' }}>$ htop</div>
    <div style={{ marginTop: 6 }}>
      <span style={{ color: 'var(--success)' }}>  1 </span>
      <span>[</span><span style={{ color: 'var(--success)' }}>||||||||||</span>
      <span style={{ color: 'var(--on-surface-muted)' }}>          </span>
      <span>] 38.2% </span>
    </div>
    <div>
      <span style={{ color: 'var(--success)' }}>  2 </span>
      <span>[</span><span style={{ color: 'var(--success)' }}>|||||</span>
      <span style={{ color: 'var(--on-surface-muted)' }}>               </span>
      <span>] 19.7% </span>
    </div>
    <div>
      <span style={{ color: 'var(--warning)' }}>Mem</span>
      <span>[</span><span style={{ color: 'var(--warning)' }}>|||||||||||</span>
      <span style={{ color: 'var(--on-surface-muted)' }}>         </span>
      <span>]  1.1G/4.0G</span>
    </div>
    <div style={{ marginTop: 8, color: 'var(--on-surface-muted)' }}>  PID USER     %CPU %MEM   COMMAND</div>
    <div>  812 root      24.1  3.0   gradle-daemon</div>
    <div>  219 root       8.2  1.2   tmux: server</div>
    <div>   84 root       1.4  0.6   bash</div>
  </div>
);

// ── Modifier bars ────────────────────────────────────────────────
const KEYS_ROW1 = ['Ctrl','Alt','Esc','Tab','↑','↓','←','→'];
const FN_ROW    = ['F1','F2','F3','F4','F5','F6','F7','F8','F9','F10'];

const ModBtn = ({ children, active, onClick, accent, w }) => (
  <button onClick={onClick} style={{
    flex: w || 1, minWidth: 38, height: 38,
    background: active ? 'var(--accent)' : (accent ? 'var(--accent-container)' : 'var(--surface-high)'),
    color: active ? 'var(--on-accent)' : (accent ? 'var(--on-accent-container)' : 'var(--on-surface)'),
    border: 'none', borderRadius: 8, fontFamily: 'var(--font-mono)', fontSize: 12,
    cursor: 'pointer', padding: '0 6px',
  }}>{children}</button>
);

const ModifierBar = ({ showFnRow, tmuxArmed, onTmuxPrefix, onToggleFn }) => (
  <div style={{ background: 'var(--surface-low)', borderTop: '1px solid var(--outline-variant)', padding: 6, flexShrink: 0 }}>
    {showFnRow && (
      <div style={{ display: 'flex', gap: 4, marginBottom: 4 }}>
        {FN_ROW.map(k => <ModBtn key={k}>{k}</ModBtn>)}
      </div>
    )}
    <div style={{ display: 'flex', gap: 4 }}>
      <ModBtn accent active={tmuxArmed} onClick={onTmuxPrefix} w={1.4}>⌘ tmux</ModBtn>
      {KEYS_ROW1.map(k => <ModBtn key={k}>{k}</ModBtn>)}
      <ModBtn onClick={onToggleFn} w={0.9}>Fn{showFnRow ? '▾' : '▴'}</ModBtn>
    </div>
  </div>
);

const FloatingModBar = ({ expanded, setExpanded, tmuxArmed, onTmuxPrefix }) => (
  <div style={{ position: 'absolute', right: 12, bottom: 18, zIndex: 5 }}>
    {expanded && (
      <div style={{
        position: 'absolute', right: 0, bottom: 56,
        background: 'var(--surface-high)', borderRadius: 16, padding: 8,
        boxShadow: '0 12px 28px rgba(0,0,0,0.55)',
        display: 'grid', gridTemplateColumns: 'repeat(3, 38px)', gap: 6,
        animation: 'slideUp 160ms ease',
      }}>
        {['Ctrl','Alt','Esc','Tab','↑','↓','←','→','Fn'].map(k => <ModBtn key={k}>{k}</ModBtn>)}
      </div>
    )}
    <button onClick={() => setExpanded(e => !e)} style={{
      width: 56, height: 56, borderRadius: 16,
      background: tmuxArmed ? 'var(--accent)' : 'var(--surface-high)',
      color: tmuxArmed ? 'var(--on-accent)' : 'var(--on-surface)',
      border: 'none', cursor: 'pointer',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      boxShadow: '0 8px 20px rgba(0,0,0,0.5)',
    }} onDoubleClick={onTmuxPrefix} title="Double-tap: tmux prefix">
      <IcKeyboard size={24}/>
    </button>
  </div>
);

const CollapsibleModBar = ({ tmuxArmed, onTmuxPrefix }) => {
  const [open, setOpen] = React.useState(false);
  return (
    <div style={{ background: 'var(--surface-low)', borderTop: '1px solid var(--outline-variant)', flexShrink: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: 6 }}>
        <button onClick={() => setOpen(o => !o)} style={{
          width: 38, height: 38, borderRadius: 8, background: 'var(--surface-high)',
          color: 'var(--on-surface)', border: 'none', cursor: 'pointer',
          fontSize: 18, fontFamily: 'var(--font-mono)',
        }}>{open ? '▾' : '▸'}</button>
        <ModBtn accent active={tmuxArmed} onClick={onTmuxPrefix} w={1.6}>⌘ tmux prefix</ModBtn>
        {!open && <>
          <ModBtn>Ctrl</ModBtn><ModBtn>Esc</ModBtn>
          <ModBtn>↑</ModBtn><ModBtn>↓</ModBtn>
        </>}
        {open && <ModBtn>Ctrl</ModBtn>}
      </div>
      {open && (
        <div style={{ padding: '0 6px 6px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          <div style={{ display: 'flex', gap: 4 }}>
            {['Alt','Esc','Tab','↑','↓','←','→'].map(k => <ModBtn key={k}>{k}</ModBtn>)}
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            {FN_ROW.map(k => <ModBtn key={k}>{k}</ModBtn>)}
          </div>
        </div>
      )}
    </div>
  );
};

window.ScreenTerminal = ScreenTerminal;
