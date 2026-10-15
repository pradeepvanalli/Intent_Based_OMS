import { useState } from 'react';
import { useLocation } from 'react-router-dom';
import { api, MOCK } from '../api/client';
import { copilotApi } from '../api/copilot';
import { hasPermission, useSession } from '../auth/session';
import { Button } from '../components/Button';
import { IconExternal } from '../components/Icons';
import { Dialog } from '../components/Overlay';
import { DISCUSSIONS_URL, NEW_BUG_URL, NEW_FEATURE_URL, REPO_URL, SHARE_USE_URL } from '../lib/links';
import { toast } from '../lib/toast';
import type { ProviderMap } from './aiStatus';
import { buildDiagnostics } from './diagnostics';
import { IconFeedback } from './launchIcons';
import { restartTour } from './tourSteps';

export const UI_VERSION = '0.1.0';

/** Topbar button opening the feedback panel: GitHub issue/discussion links plus a copyable diagnostics block. */
export function FeedbackButton() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant="ghost" iconOnly icon={<IconFeedback />} onClick={() => setOpen(true)} title="Feedback: report a bug, suggest a feature, share how you'd use it">Feedback</Button>
      <FeedbackPanel open={open} onClose={() => setOpen(false)} />
    </>
  );
}

export function FeedbackPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { principal } = useSession();
  const location = useLocation();
  const [diag, setDiag] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const gather = async () => {
    setBusy(true);
    try {
      const [info, providers, copilot] = await Promise.all([
        api.get<unknown>('/actuator/info', { silent: true }).catch(() => null),
        hasPermission(principal, 'decision.read') ? api.get<ProviderMap>('/api/v1/decisions/providers', { silent: true }).catch(() => null) : Promise.resolve(null),
        copilotApi.status().catch(() => null),
      ]);
      const text = buildDiagnostics({ uiVersion: UI_VERSION, mock: MOCK, appInfo: info, tenant: principal?.tenantId, user: principal?.id, roles: principal?.roles, providers, copilot, userAgent: navigator.userAgent, path: location.pathname, now: new Date().toISOString() });
      setDiag(text);
      try { await navigator.clipboard.writeText(text); toast.ok('Copied', 'Diagnostic info is on the clipboard'); } catch { /* shown below for manual copy */ }
    } finally { setBusy(false); }
  };
  return (
    <Dialog open={open} onClose={onClose} title="Feedback" width={520} description={<>Intent OMS is open source. Bugs and ideas go to GitHub; the diagnostic block below helps us reproduce without any order data.</>}
      footer={<><Button variant="ghost" onClick={() => { onClose(); restartTour(); }}>Restart tour</Button><span className="grow" /><Button onClick={onClose}>Close</Button></>}>
      <div className="col gap3">
        <div className="grid grid-3" style={{ gap: 'var(--s2)' }}>
          <a className="btn" href={NEW_BUG_URL} target="_blank" rel="noreferrer">Report a bug <IconExternal size={13} /></a>
          <a className="btn" href={NEW_FEATURE_URL} target="_blank" rel="noreferrer">Suggest a feature <IconExternal size={13} /></a>
          <a className="btn" href={SHARE_USE_URL} target="_blank" rel="noreferrer">Share how you'd use it <IconExternal size={13} /></a>
        </div>
        <div className="row gap2" style={{ alignItems: 'center' }}>
          <Button size="sm" onClick={gather} loading={busy}>Copy diagnostic info</Button>
          <span className="small muted">version, tenant, provider status, browser — paste it into the issue</span>
        </div>
        {diag && <pre className="code" style={{ maxHeight: 180 }} aria-label="Diagnostic info">{diag}</pre>}
        <div className="small muted">Repository: <a href={REPO_URL} target="_blank" rel="noreferrer">{REPO_URL.replace('https://', '')}</a> · <a href={DISCUSSIONS_URL} target="_blank" rel="noreferrer">Discussions</a></div>
      </div>
    </Dialog>
  );
}
