// Screen: QR Onboarding (UC04-1) — full-bleed camera preview with reticle.
const ScreenOnboarding = ({ goto }) => {
  const [stage, setStage] = React.useState('scan'); // scan | imported | ready
  React.useEffect(() => {
    if (stage === 'scan') {
      const t = setTimeout(() => setStage('imported'), 2200);
      return () => clearTimeout(t);
    }
    if (stage === 'imported') {
      const t = setTimeout(() => setStage('ready'), 1400);
      return () => clearTimeout(t);
    }
  }, [stage]);

  return (
    <div style={{ position: 'relative', flex: 1, background: '#000', color: '#fff', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      {/* Top bar */}
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, display: 'flex', alignItems: 'center', padding: 12, gap: 8, zIndex: 5 }}>
        <M3IconButton onClick={() => goto('sessions')} style={{ background: 'rgba(0,0,0,0.45)', backdropFilter: 'blur(8px)' }}>
          <IcClose size={22} />
        </M3IconButton>
        <div style={{ flex: 1 }} />
        <M3IconButton style={{ background: 'rgba(0,0,0,0.45)', backdropFilter: 'blur(8px)' }}>
          <IcFlashOn size={22} />
        </M3IconButton>
      </div>

      {/* Fake camera viewfinder — diagonal stripe */}
      <div style={{
        position: 'absolute', inset: 0,
        backgroundImage: `
          linear-gradient(120deg, #0d0f12 0%, #1a1d22 35%, #0f1216 65%, #1a1d22 100%),
          repeating-linear-gradient(45deg, rgba(255,255,255,0.02) 0 8px, transparent 8px 16px)
        `,
        backgroundBlendMode: 'overlay',
      }}/>

      {/* Reticle */}
      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', position: 'relative', zIndex: 1 }}>
        <div style={{ position: 'relative', width: 260, height: 260 }}>
          {/* Fake QR pattern (only visible during 'scan') */}
          {stage === 'scan' && (
            <div style={{
              position: 'absolute', inset: 22,
              background: '#fff',
              padding: 14,
              borderRadius: 4,
              opacity: 0.92,
            }}>
              <FakeQR />
            </div>
          )}
          {stage === 'imported' && (
            <div style={{
              position: 'absolute', inset: 22, background: 'rgba(20,22,18,0.85)',
              border: '2px solid var(--success)', borderRadius: 4,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: 'var(--success)',
            }}>
              <IcCheck size={56} />
            </div>
          )}
          {/* Reticle corners */}
          {['tl','tr','bl','br'].map(corner => (
            <div key={corner} style={{
              position: 'absolute',
              ...({tl:{top:0,left:0}, tr:{top:0,right:0}, bl:{bottom:0,left:0}, br:{bottom:0,right:0}}[corner]),
              width: 36, height: 36,
              borderColor: stage === 'imported' ? 'var(--success)' : '#fff',
              borderStyle: 'solid',
              borderWidth: 0,
              borderTopWidth: corner.startsWith('t') ? 3 : 0,
              borderBottomWidth: corner.startsWith('b') ? 3 : 0,
              borderLeftWidth: corner.endsWith('l') ? 3 : 0,
              borderRightWidth: corner.endsWith('r') ? 3 : 0,
              borderTopLeftRadius: corner === 'tl' ? 8 : 0,
              borderTopRightRadius: corner === 'tr' ? 8 : 0,
              borderBottomLeftRadius: corner === 'bl' ? 8 : 0,
              borderBottomRightRadius: corner === 'br' ? 8 : 0,
              transition: 'border-color 240ms',
            }}/>
          ))}
          {/* Scan line */}
          {stage === 'scan' && (
            <div style={{
              position: 'absolute', left: 22, right: 22, top: 22, height: 216,
              overflow: 'hidden', borderRadius: 4, pointerEvents: 'none',
            }}>
              <div style={{
                position: 'absolute', left: 0, right: 0, height: 2,
                background: 'linear-gradient(90deg, transparent, var(--accent), transparent)',
                boxShadow: '0 0 12px var(--accent)',
                animation: 'scanLine 1.8s ease-in-out infinite alternate',
              }}/>
            </div>
          )}
        </div>
      </div>

      {/* Bottom text panel */}
      <div style={{ position: 'relative', zIndex: 2, padding: '24px 28px 28px', background: 'linear-gradient(to top, rgba(0,0,0,0.7), transparent)' }}>
        {stage === 'scan' && (
          <>
            <div style={{ fontSize: 22, fontWeight: 500, letterSpacing: -0.2 }}>Scan onboarding QR</div>
            <div style={{ marginTop: 8, fontSize: 14, lineHeight: 1.5, color: 'rgba(255,255,255,0.75)' }}>
              Generated on the ai-sandbox server with{' '}
              <span style={{ fontFamily: 'var(--font-mono)' }}>sandboxctl client invite</span>. One-time use, expires in 10&nbsp;min.
            </div>
          </>
        )}
        {stage === 'imported' && (
          <div style={{ animation: 'slideUp 220ms ease' }}>
            <div style={{ fontSize: 22, fontWeight: 500, color: 'var(--success)' }}>Identity imported</div>
            <div style={{ marginTop: 10, fontSize: 13, fontFamily: 'var(--font-mono)', color: 'rgba(255,255,255,0.7)', lineHeight: 1.6 }}>
              server  ops.lan:12410<br/>
              pin     sha256/9F:3A:…:E1<br/>
              cert    CN=Pixel-8-Tao, exp 2026-11-12<br/>
              key     stored in Android KeyStore
            </div>
          </div>
        )}
        {stage === 'ready' && (
          <div style={{ animation: 'slideUp 220ms ease' }}>
            <div style={{ fontSize: 22, fontWeight: 500 }}>Ready</div>
            <div style={{ marginTop: 6, fontSize: 14, color: 'rgba(255,255,255,0.75)' }}>Establishing mTLS to ops.lan…</div>
            <M3Button full style={{ marginTop: 18 }} onClick={() => goto('sessions')}>Continue</M3Button>
          </div>
        )}
      </div>
    </div>
  );
};

const FakeQR = () => {
  // Deterministic 21x21 pattern (visual only — not a real QR)
  const seed = 'ai-sandbox onboarding qr v1 placeholder bits 0xC9A3FF';
  const cells = [];
  for (let i = 0; i < 21 * 21; i++) {
    cells.push(((seed.charCodeAt(i % seed.length) + i * 31) >> ((i % 5) + 1)) & 1);
  }
  // Force finder patterns at corners
  const setFinder = (cx, cy) => {
    for (let y = 0; y < 7; y++) for (let x = 0; x < 7; x++) {
      const on = (x === 0 || x === 6 || y === 0 || y === 6 || (x >= 2 && x <= 4 && y >= 2 && y <= 4)) ? 1 : 0;
      cells[(cy+y) * 21 + (cx+x)] = on;
    }
  };
  setFinder(0, 0); setFinder(14, 0); setFinder(0, 14);
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(21, 1fr)', width: '100%', height: '100%', gap: 0 }}>
      {cells.map((c, i) => (
        <div key={i} style={{ background: c ? '#0a0a0a' : 'transparent', aspectRatio: '1' }} />
      ))}
    </div>
  );
};

window.ScreenOnboarding = ScreenOnboarding;
