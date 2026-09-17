import { useEffect, useRef, useState } from 'react';
import { MicrophoneIcon, CircleIcon, BluetoothIcon } from '@phosphor-icons/react';
import { BottomSheet, MobileScroll, useMobileDevice } from './mobile';
type Phase = 'idle' | 'listening' | 'finishing' | 'typing' | 'sent';
const sample = 'I’m sending this from my phone.';

// Preserve the approved painted rim; resize only its straight edges and center.
function GlassFrame() {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const el = canvas.current;
    if (!el) return;
    const artwork = new Image();
    let disposed = false;
    function draw() {
      if (!el || disposed || !artwork.naturalWidth) return;
      const w = el.clientWidth, h = el.clientHeight, dpr = window.devicePixelRatio || 1;
      el.width = Math.round(w * dpr); el.height = Math.round(h * dpr);
      const ctx = el.getContext('2d');
      if (!ctx) return;
      // Source boundaries exclude transparent margins in the 1561 x 1007 PNG.
      const sx = [22, 162, 1400, 1540], sy = [98, 238, 750, 890];
      const corner = Math.min(28, w / 2, h / 2);
      const dx = [0, Math.round(corner * dpr), el.width - Math.round(corner * dpr), el.width];
      const dy = [0, Math.round(corner * dpr), el.height - Math.round(corner * dpr), el.height];
      ctx.clearRect(0, 0, el.width, el.height);
      for (let row = 0; row < 3; row++) for (let col = 0; col < 3; col++) {
        if (dx[col + 1] <= dx[col] || dy[row + 1] <= dy[row]) continue;
        ctx.drawImage(artwork, sx[col], sy[row], sx[col + 1] - sx[col], sy[row + 1] - sy[row],
          dx[col], dy[row], dx[col + 1] - dx[col], dy[row + 1] - dy[row]);
      }
    }
    artwork.onload = draw; artwork.src = '/assets/murmr/glass-panel.png';
    const observer = new ResizeObserver(draw); observer.observe(el);
    window.addEventListener('resize', draw);
    return () => { disposed = true; observer.disconnect(); window.removeEventListener('resize', draw); artwork.onload = null; };
  }, []);
  return <canvas ref={canvas} className="glass-frame" aria-hidden="true" />;
}

function Waveform({ active, analyser, demo }: { active: boolean; analyser: AnalyserNode | null; demo: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const values = useRef(new Array<number>(61).fill(0));
  useEffect(() => {
    const el = canvas.current, ctx = el?.getContext('2d');
    if (!el || !ctx) return;
    let frame = 0, last = 0;
    const audio = new Uint8Array(analyser?.fftSize ?? 256);
    const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
    function draw(time: number) {
      if (!el || !ctx) return;
      const box = el.getBoundingClientRect(), dpr = Math.min(devicePixelRatio, 2);
      if (el.width !== Math.round(box.width * dpr) || el.height !== Math.round(box.height * dpr)) { el.width = Math.round(box.width * dpr); el.height = Math.round(box.height * dpr); }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const w = box.width, h = box.height;
      ctx.clearRect(0, 0, w, h);
      if (time - last > (reduced ? 150 : 35)) {
        let amplitude = 0;
        if (active && analyser) { analyser.getByteTimeDomainData(audio); amplitude = Math.min(1, Math.sqrt(audio.reduce((s, v) => s + ((v - 128) / 128) ** 2, 0) / audio.length) * 5); }
        else if (active && demo) amplitude = (0.16 + Math.abs(Math.sin(time / 213) * Math.sin(time / 591)) * 0.8) * (0.4 + Math.abs(Math.sin(time / 1400)) * 0.6);
        values.current.shift(); values.current.push(amplitude); last = time;
        if (!active) values.current = values.current.map(v => v * 0.8);
      }
      ctx.lineWidth = 0.5; ctx.strokeStyle = 'rgba(100,204,188,0.09)';
      for (let x = 0; x < w; x += 12) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke(); }
      for (let y = 0; y < h; y += 12) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }
      const gap = w / 61;
      values.current.forEach((v, i) => { const height = Math.max(2, v * h * 0.88); ctx.fillStyle = active ? '#80f4df' : '#648f89'; ctx.shadowColor = '#57dbc3'; ctx.shadowBlur = active ? 5 : 0; ctx.fillRect(i * gap + 1, (h - height) / 2, Math.max(1.5, gap * 0.56), height); });
      ctx.shadowBlur = 0; frame = requestAnimationFrame(draw);
    }
    frame = requestAnimationFrame(draw); return () => cancelAnimationFrame(frame);
  }, [active, analyser, demo]);
  return <canvas ref={canvas} className="waveform" role="img" aria-label={active ? 'Input amplitude waveform' : 'Quiet input waveform'} />;
}

export default function Prototype() {
  const { setDeviceId, device } = useMobileDevice();
  const [phase, setPhase] = useState<Phase>('idle');
  const [connected, setConnected] = useState(true), [sheet, setSheet] = useState(false);
  const [input, setInput] = useState<'demo' | 'microphone'>('demo');
  const [transcript, setTranscript] = useState(sample), [notice, setNotice] = useState('');
  const [analyser, setAnalyser] = useState<AnalyserNode | null>(null);
  const [permissionPending, setPermissionPending] = useState(false);
  const held = useRef(false), audioContext = useRef<AudioContext | null>(null), stream = useRef<MediaStream | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null), requestId = useRef(0), transcriptBefore = useRef(sample);
  const [delivered, setDelivered] = useState(0);
  const [lastMacro, setLastMacro] = useState<number | null>(null);
  useEffect(() => { if (lastMacro === null) return; const t = setTimeout(() => setLastMacro(null), 220); return () => clearTimeout(t); }, [lastMacro]);
  const macros = [{label: 'ENTER', symbol: '↵'}, {label: 'TAB', symbol: '⇥'}, {label: 'ESC', symbol: 'esc'}, {label: 'PASTE', symbol: 'V'}];

  const active = phase === 'listening';
  const busy = phase === 'finishing' || phase === 'typing';
  function haptic(pattern: number | number[]) { navigator.vibrate?.(pattern); }
  useEffect(() => { document.title = 'murmr · Voice instrument'; setDeviceId('pixel-10'); }, []);
  function stopAudio() { stream.current?.getTracks().forEach(t => t.stop()); stream.current = null; void audioContext.current?.close(); audioContext.current = null; setAnalyser(null); }
  function clearTimer() { if (timer.current) clearTimeout(timer.current); timer.current = null; }
  function start() {
    if (!connected || held.current || busy || permissionPending) return;
    held.current = true; clearTimer(); setNotice(''); transcriptBefore.current = transcript; setPhase('listening');
    if (input === 'demo') { setTranscript(''); haptic(12); }
    else { stream.current?.getAudioTracks().forEach(t => { t.enabled = true; }); void audioContext.current?.resume().then(() => { if (held.current) haptic(12); }); }
  }
  function finish(cancel = false) {
    if (!held.current) return;
    held.current = false;
    if (input === 'microphone') { stopAudio(); setInput('demo'); }
    if (cancel) { setPhase('idle'); setTranscript(transcriptBefore.current); setNotice('Cancelled'); return; }
    if (input === 'demo') {
      setTranscript(sample); setDelivered(0); setPhase('finishing');
      timer.current = setTimeout(() => {
        setPhase('typing');
        // Demo transport completion callbacks only. Native implementation must
        // advance this counter from actual HID send progress, never elapsed time.
        let sent = 0;
        const demoSend = () => {
          sent++; setDelivered(sent);
          if (sent < sample.length) timer.current = setTimeout(demoSend, 65);
          else { setPhase('sent'); haptic([12, 70, 12]); timer.current = setTimeout(() => setPhase('idle'), 1800); }
        };
        timer.current = setTimeout(demoSend, 65);
      }, 600);
    }
    else { setPhase('idle'); setNotice('Microphone stopped. Audio was not recorded.'); }
  }
  useEffect(() => {
    if (!active || input !== 'demo') return;
    let index = 0; const words = sample.split(' ');
    const interval = setInterval(() => { index++; setTranscript(words.slice(0, index).join(' ')); }, 240);
    return () => clearInterval(interval);
  }, [active, input]);
  useEffect(() => {
    const keydown = (e: KeyboardEvent) => {
      if (sheet || e.repeat || (e.code !== 'Space' && e.code !== 'Escape')) return;
      if (e.code === 'Escape') { finish(true); return; }
      if ((e.target as HTMLElement)?.closest('button') && !(e.target as HTMLElement).closest('.talk-button')) return;
      e.preventDefault(); start();
    };
    const keyup = (e: KeyboardEvent) => { if (e.code === 'Space') { e.preventDefault(); finish(); } };
    const blur = () => { finish(true); stopAudio(); if (input === 'microphone') setInput('demo'); };
    const visibility = () => { if (document.hidden) blur(); };
    window.addEventListener('keydown', keydown); window.addEventListener('keyup', keyup); window.addEventListener('blur', blur); document.addEventListener('visibilitychange', visibility);
    return () => { window.removeEventListener('keydown', keydown); window.removeEventListener('keyup', keyup); window.removeEventListener('blur', blur); document.removeEventListener('visibilitychange', visibility); };
  });
  useEffect(() => () => { requestId.current++; clearTimer(); stream.current?.getTracks().forEach(t => t.stop()); void audioContext.current?.close(); }, []);
  async function enableMicrophone() {
    const id = ++requestId.current; setPermissionPending(true); setNotice('');
    try {
      const media = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (id !== requestId.current) { media.getTracks().forEach(t => t.stop()); return; }
      const context = new AudioContext(), node = context.createAnalyser(); node.fftSize = 256; context.createMediaStreamSource(media).connect(node);
      media.getAudioTracks().forEach(t => { t.enabled = false; });
      stream.current = media; audioContext.current = context; setAnalyser(node); setInput('microphone'); setPhase('idle'); setSheet(false); setNotice('Hold to see your microphone levels. No speech recognition.');
    } catch { setNotice('Microphone unavailable. Demo input is still available.'); }
    finally { if (id === requestId.current) setPermissionPending(false); }
  }
  function changeConnection() { requestId.current++; setPermissionPending(false); finish(true); clearTimer(); stopAudio(); setInput('demo'); setPhase('idle'); setConnected(!connected); setSheet(false); setNotice(''); }
  const status = !connected ? 'Offline' : active ? 'Listening' : phase === 'finishing' ? 'Finishing' : phase === 'typing' ? 'Typing · demo' : phase === 'sent' ? 'Typed · demo' : 'Ready';
  return <>
    <MobileScroll className="app-screen instrument-scroll">
      <main className="instrument" data-phase={phase} data-platform={device.platform} data-testid="instrument">
        <header className="instrument-header">
          <div className="brand" role="img" aria-label="murmr">
            <svg className="brand-mark" viewBox="54 88 1185 603" aria-hidden="true"><image href="/assets/murmr/logo.png" width="1334" height="1179" /></svg>
            <svg className="brand-wordmark" viewBox="98 838 1076 193" aria-hidden="true"><image href="/assets/murmr/logo.png" width="1334" height="1179" /></svg>
          </div>
          <button className="connection" onClick={() => setSheet(true)} aria-label="Manage computer connection" data-testid="connection"><CircleIcon weight="fill" className={connected ? 'led on' : 'led'} size={14} /><span><span className="host">NAM-RYZEN</span><span className="connection-state">{connected ? 'Connected' : 'Disconnected'}</span></span></button>
        </header>
        <section className="glass transcript-panel" aria-label="Transcript"><GlassFrame />
          <div className="panel-heading"><span>TRANSCRIPT</span><span className="input-status" role="status"><CircleIcon size={8} weight="fill" />{status}</span></div>
          <div className="transcript-text" aria-live={phase === 'typing' ? 'off' : 'polite'} aria-atomic="true">
            {phase === 'typing' ? <><span className="delivered-text">{transcript.slice(0, delivered)}</span><span className="delivery-caret" aria-hidden="true" /><span className="pending-text">{transcript.slice(delivered)}</span></> : transcript || <span className="placeholder">Listening…</span>}
            {active && <span className="caret" aria-hidden="true">|</span>}
          </div>
          {phase === 'typing' && <p className="notice">Simulated sending · {delivered} / {transcript.length} characters</p>}
          {notice && <p className="notice" role="status">{notice}</p>}
        </section>
        <section className="glass input-panel" aria-label="Audio input"><GlassFrame /><Waveform active={active} analyser={analyser} demo={input === 'demo'} /></section>
        <div className="control-deck">
          <div className="macro-cluster">
          {macros.map((macro, index) => <button key={macro.label} type="button" className={`macro-key macro-${index} ${lastMacro === index ? 'flashed' : ''}`} data-scroll-drag="ignore" aria-label={`Demo macro: ${macro.label}`} disabled={!connected || active || busy || permissionPending}
            onClick={() => { setLastMacro(index); haptic(12); setNotice(`${macro.label} macro · demo only, no keystrokes sent`); }}>
            <img className="macro-art macro-idle" src="/assets/murmr/macro-idle.png" alt="" draggable="false" />
            <img className="macro-art macro-pressed" src="/assets/murmr/macro-pressed.png" alt="" draggable="false" />
            <span className="macro-label"><span className="macro-symbol" aria-hidden="true">{macro.symbol}</span><span>{macro.label}</span></span>
          </button>)}
          <button type="button" data-testid="talk-button" data-scroll-drag="ignore" className="talk-button" aria-label={active ? 'Release to type' : 'Hold to talk'} aria-pressed={active} disabled={!connected || busy || permissionPending}
            onPointerDown={e => { if (e.button !== 0) return; e.preventDefault(); e.currentTarget.setPointerCapture(e.pointerId); start(); }} onPointerUp={() => finish()} onPointerCancel={() => finish(true)} onLostPointerCapture={() => finish(true)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.repeat) { e.preventDefault(); start(); } }} onKeyUp={e => { if (e.key === 'Enter') { e.preventDefault(); finish(); } }}>
            <img className="button-art idle-art" src="/assets/murmr/talk-idle.png" alt="" draggable="false" /><img className="button-art active-art" src="/assets/murmr/talk-active.png" alt="" draggable="false" />
            <span className="button-content"><span className={`mic-glyph ${connected && !busy && !permissionPending ? 'ready' : ''}`}><span className="mic-ready-light" /><MicrophoneIcon size={52} weight="regular" /></span><span>HOLD TO TALK</span></span>
          </button>
          </div>
          <p className="instructions">Hold to talk, release to type</p><span className="shortcut-hint">Space here · Volume Down in the Android app</span><button className="demo-settings" onClick={() => setSheet(true)}>Interactive demo · {input === 'demo' ? 'Sample audio' : 'Microphone input'}</button>
        </div>
      </main>
    </MobileScroll>
    <BottomSheet open={sheet} onOpenChange={open => { if (open) finish(true); if (!open) { requestId.current++; setPermissionPending(false); } setSheet(open); }} title="Connection & input" description="This is a browser prototype. Computer delivery and transcript text are simulated." snap={0.65}>
      <div className="settings-content"><div className="settings-host"><BluetoothIcon size={24} /><div><strong>NAM-RYZEN</strong><p>{connected ? 'Connected in this demo' : 'Disconnected in this demo'}</p></div></div>
        <button className="settings-action" onClick={changeConnection}>{connected ? 'Disconnect' : 'Connect'}</button><h3>Waveform input</h3><p>Sample audio works immediately. Microphone mode shows your actual sound levels locally; it does not record, upload, or transcribe audio.</p>
        <button className="settings-action primary" disabled={permissionPending || !connected} onClick={enableMicrophone}>{permissionPending ? 'Waiting for microphone permission…' : 'Use microphone for waveform'}</button>
        <button className="settings-action" onClick={() => { requestId.current++; setPermissionPending(false); stopAudio(); setInput('demo'); setPhase('idle'); setNotice(''); setSheet(false); }}>Use sample audio</button>
        {notice && <p role="status" className="settings-notice">{notice}</p>}<p className="settings-help">Hold the button, Space, or Enter to talk. Release to finish. Escape cancels. Volume Down remains an Android app shortcut; browsers cannot reliably capture it.</p>
      </div>
    </BottomSheet>
  </>;
}
