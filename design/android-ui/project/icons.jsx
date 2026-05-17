// Material Symbols-style line icons, drawn as compact SVG paths.
// Single file, sized via `size` prop, colored via currentColor.

const Icon = ({ d, size = 24, stroke = false, sw = 2, style = {}, viewBox = '0 0 24 24' }) => (
  <svg width={size} height={size} viewBox={viewBox} style={{ display: 'block', flexShrink: 0, ...style }}
       fill={stroke ? 'none' : 'currentColor'} stroke={stroke ? 'currentColor' : 'none'}
       strokeWidth={stroke ? sw : 0} strokeLinecap="round" strokeLinejoin="round">
    {typeof d === 'string' ? <path d={d}/> : d}
  </svg>
);

// Glyphs (filled, simplified material)
const IcAdd       = (p) => <Icon {...p} d="M11 13H5v-2h6V5h2v6h6v2h-6v6h-2v-6z"/>;
const IcClose     = (p) => <Icon {...p} d="M6.4 19L5 17.6L10.6 12L5 6.4L6.4 5L12 10.6L17.6 5L19 6.4L13.4 12L19 17.6L17.6 19L12 13.4L6.4 19z"/>;
const IcMore      = (p) => <Icon {...p} d="M12 20a2 2 0 110-4 2 2 0 010 4zm0-6a2 2 0 110-4 2 2 0 010 4zm0-6a2 2 0 110-4 2 2 0 010 4z"/>;
const IcArrowBack = (p) => <Icon {...p} d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/>;
const IcQr        = (p) => <Icon {...p} d="M3 11h8V3H3v8zm2-6h4v4H5V5zm-2 16h8v-8H3v8zm2-6h4v4H5v-4zm8-12v8h8V3h-8zm6 6h-4V5h4v4zm0 8h2v4h-4v-2h2v-2zm-6-2h2v2h-2v-2zm2 2h2v2h-2v-2zm-2 2h2v2h-2v-2zm2 2h2v2h-2v-2zm2-2h2v2h-2v-2zm0-2h2v2h-2v-2zm2 2h2v2h-2v-2z"/>;
const IcLock      = (p) => <Icon {...p} d="M6 22q-.825 0-1.413-.588T4 20V10q0-.825.588-1.413T6 8h1V6q0-2.075 1.463-3.538T12 1q2.075 0 3.538 1.463T17 6v2h1q.825 0 1.413.588T20 10v10q0 .825-.588 1.413T18 22H6zm0-2h12V10H6v10zm6-3q.825 0 1.413-.588T14 15q0-.825-.588-1.413T12 13q-.825 0-1.413.588T10 15q0 .825.588 1.413T12 17zM9 8h6V6q0-1.25-.875-2.125T12 3q-1.25 0-2.125.875T9 6v2z"/>;
const IcShield    = (p) => <Icon {...p} d="M12 22q-3.475-.875-5.738-3.988T4 11.1V5l8-3 8 3v6.1q0 3.8-2.263 6.913T12 22z"/>;
const IcDelete    = (p) => <Icon {...p} d="M7 21q-.825 0-1.413-.588T5 19V6H4V4h5V3h6v1h5v2h-1v13q0 .825-.588 1.413T17 21H7zM9 17h2V8H9v9zm4 0h2V8h-2v9z"/>;
const IcTerminal  = (p) => <Icon {...p} d="M4 18V6h16v12H4zm5.5-3l3-3l-3-3l-.65.65L11.2 12l-2.35 2.35l.65.65zm3.5.5h4v-1h-4v1z"/>;
const IcSettings  = (p) => <Icon {...p} d="M9.25 22l-.4-3.2q-.325-.125-.612-.3t-.563-.375L4.7 19.375l-2.75-4.75l2.575-1.95Q4.5 12.5 4.5 12.337v-.675q0-.162.025-.337L1.95 9.375l2.75-4.75l2.975 1.25q.275-.2.575-.375t.6-.3l.4-3.2h5.5l.4 3.2q.325.125.613.3t.562.375l2.975-1.25l2.75 4.75l-2.575 1.95q.025.175.025.337v.675q0 .163-.05.338l2.575 1.95l-2.75 4.75l-2.95-1.25q-.275.2-.575.375t-.6.3l-.4 3.2h-5.5zm2.8-6.5q1.45 0 2.475-1.025T15.55 12q0-1.45-1.025-2.475T12.05 8.5q-1.475 0-2.488 1.025T8.55 12q0 1.45 1.013 2.475T12.05 15.5z"/>;
const IcCheck     = (p) => <Icon {...p} d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/>;
const IcWarning   = (p) => <Icon {...p} d="M12 5.99L19.53 19H4.47L12 5.99M12 2L1 21h22L12 2zm1 14h-2v2h2v-2zm0-6h-2v5h2v-5z"/>;
const IcRefresh   = (p) => <Icon {...p} d="M17.65 6.35A7.958 7.958 0 0012 4a8 8 0 100 16 7.94 7.94 0 007.21-4.58l-2.05-.59A6 6 0 116 12h3l-4-4-4 4h3a8 8 0 0013.65 5.65L19.74 17 17.65 6.35z"/>;
const IcFlashOn   = (p) => <Icon {...p} d="M7 2v11h3v9l7-12h-4l3-8z"/>;
const IcKeyboard  = (p) => <Icon {...p} d="M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z"/>;
const IcPlay      = (p) => <Icon {...p} d="M8 5v14l11-7L8 5z"/>;
const IcPause     = (p) => <Icon {...p} d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>;
const IcDock      = (p) => <Icon {...p} d="M3 5h18v3H3V5zm0 5h18v9H3v-9z"/>;
const IcCopy      = (p) => <Icon {...p} d="M16 1H4a2 2 0 00-2 2v14h2V3h12V1zm3 4H8a2 2 0 00-2 2v14a2 2 0 002 2h11a2 2 0 002-2V7a2 2 0 00-2-2zm0 16H8V7h11v14z"/>;
const IcChevR     = (p) => <Icon {...p} d="M9.29 6.71a.996.996 0 000 1.41L13.17 12l-3.88 3.88a.996.996 0 101.41 1.41l4.59-4.59a.996.996 0 000-1.41L10.7 6.7c-.38-.38-1.02-.38-1.41.01z"/>;
const IcDot       = (p) => <Icon {...p} d="M12 8a4 4 0 100 8 4 4 0 000-8z"/>;
const IcSplit     = (p) => <Icon {...p} d="M3 5h8v14H3V5zm10 0h8v6h-8V5zm0 8h8v6h-8v-6z"/>;
const IcDisconn   = (p) => <Icon {...p} d="M3.27 1L2 2.27l3.06 3.06A8.93 8.93 0 003 12c0 4.96 4.04 9 9 9 2.5 0 4.77-1.02 6.4-2.67L20.73 22 22 20.73 3.27 1zM12 19c-3.86 0-7-3.14-7-7 0-1.93.79-3.68 2.06-4.94l9.88 9.88A6.95 6.95 0 0112 19zm6.4-2.67A8.93 8.93 0 0021 12c0-4.96-4.04-9-9-9-1.62 0-3.13.43-4.44 1.18l1.46 1.46A6.94 6.94 0 0112 5c3.86 0 7 3.14 7 7 0 1.32-.37 2.55-1.01 3.61l1.41 1.72z"/>;
const IcCircleDot = (p) => <Icon {...p} d="M12 2a10 10 0 100 20 10 10 0 000-20zm0 14a4 4 0 110-8 4 4 0 010 8z"/>;

Object.assign(window, {
  Icon,
  IcAdd, IcClose, IcMore, IcArrowBack, IcQr, IcLock, IcShield, IcDelete,
  IcTerminal, IcSettings, IcCheck, IcWarning, IcRefresh, IcFlashOn,
  IcKeyboard, IcPlay, IcPause, IcDock, IcCopy, IcChevR, IcDot, IcSplit, IcDisconn, IcCircleDot,
});
