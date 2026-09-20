import { useEffect, useState, type ReactNode } from 'react';
import { CheckCircle2, ChevronRight, Circle, Folder, LoaderCircle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { toReqwsError, type ReqwsErrorPayload } from '../../shared/errors';
import { type GoLandProject, type GoLandSelection } from '../../shared/goland-workspace';
import type { WorkspaceDetail } from '../../shared/types';
import { ErrorNotice } from './ErrorNotice';

export function GoLandLoadingSection({ workspace, busy, available, children }: {
  workspace: WorkspaceDetail;
  busy: boolean;
  available: boolean;
  children?: ReactNode;
}): React.JSX.Element {
  const { t } = useTranslation();
  const [saved, setSaved] = useState<GoLandProject | null>(null);
  const [selection, setSelection] = useState<GoLandSelection>({ mode: 'all' });
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ReqwsErrorPayload | null>(null);
  const [canRetrySave, setCanRetrySave] = useState(false);
  const [savedNotice, setSavedNotice] = useState(false);
  const ready = workspace.status === 'ready';
  const members = new Set(workspace.repositories.map((repository) => repository.catalogRepositoryId));
  const normalized: GoLandSelection = selection.mode === 'all' ? selection : {
    mode: 'selected', repositoryIds: selection.repositoryIds.filter((id) => members.has(id)),
  };
  const dirty = JSON.stringify(selection) !== JSON.stringify(saved?.selection ?? { mode: 'all' });
  const disabled = !ready || busy || loading || pending;
  const errorBlocksSave = !!error && !canRetrySave;

  useEffect(() => {
    let live = true;
    if (ready) {
      window.reqws.goLandWorkspaces.read(workspace.id).then((state) => {
        if (!live) return;
        setSaved(state.project);
        setSelection(state.project?.selection ?? { mode: 'all' });
      }).catch((failure: unknown) => {
        if (live) setError(toReqwsError(failure).toPayload());
      }).finally(() => { if (live) setLoading(false); });
    }
    return () => { live = false; };
  }, [workspace.id, ready]);

  async function reload(): Promise<void> {
    setPending(true);
    setError(null);
    setCanRetrySave(false);
    try {
      const state = await window.reqws.goLandWorkspaces.read(workspace.id);
      setSaved(state.project);
      setSelection(state.project?.selection ?? { mode: 'all' });
      setSavedNotice(false);
      setLoading(false);
    } catch (failure) { setError(toReqwsError(failure).toPayload()); }
    finally { setPending(false); }
  }

  async function save(open: boolean): Promise<void> {
    setPending(true);
    setError(null);
    setCanRetrySave(false);
    setSavedNotice(false);
    let selectionSaved = false;
    try {
      const state = saved
        ? await window.reqws.goLandWorkspaces.save({ workspaceId: workspace.id, selection: normalized, expectedBindingId: saved.bindingId, expectedRevision: saved.revision })
        : await window.reqws.goLandWorkspaces.prepare({ workspaceId: workspace.id, selection: normalized });
      setSaved(state.project);
      setSelection(state.project!.selection);
      setSavedNotice(true);
      selectionSaved = true;
      if (open) await window.reqws.editors.openGoLand(workspace.id);
    } catch (failure) {
      const payload = toReqwsError(failure).toPayload();
      setError(payload);
      setCanRetrySave(saved !== null && !selectionSaved && payload.code === 'GOLAND_WRITE_FAILED');
    }
    finally { setPending(false); }
  }

  function change(value: GoLandSelection): void { setSelection(value); setSavedNotice(false); }
  const selectedCount = selection.mode === 'all' ? members.size : normalized.mode === 'selected' ? normalized.repositoryIds.length : 0;
  const statusText = pending ? t('golandLoading.saving')
    : loading && ready ? t('golandLoading.loading')
      : dirty ? t('golandLoading.unsaved')
        : savedNotice || saved ? t('golandLoading.saved') : t('golandLoading.all');
  const StatusIcon = pending || (loading && ready) ? LoaderCircle
    : dirty || !saved ? Circle : CheckCircle2;

  return (
    <section className="goland-loading" aria-labelledby="goland-loading-title" aria-busy={(ready && loading) || pending}>
      <div className="goland-loading-content">
        <h3 id="goland-loading-title">{t('golandLoading.title')}</h3>
        <p className="goland-loading-description">{t('golandLoading.description')}</p>
        <fieldset className="goland-loading-fieldset" disabled={disabled}>
          <legend className="sr-only">{t('golandLoading.mode')}</legend>
          <div className="goland-loading-modes">
            <label className={selection.mode === 'all' ? 'selected' : ''}>
              <input checked={selection.mode === 'all'} name="goland-loading-mode" onChange={() => change({ mode: 'all' })} type="radio" />
              <span>{t('golandLoading.all')}</span>
            </label>
            <label className={selection.mode === 'selected' ? 'selected' : ''}>
              <input checked={selection.mode === 'selected'} name="goland-loading-mode" onChange={() => change({ mode: 'selected', repositoryIds: [...members] })} type="radio" />
              <span>{t('golandLoading.selected')}</span>
            </label>
          </div>
          {selection.mode === 'all' ? (
            <p className="goland-loading-all-note">{t('golandLoading.allSummary')}</p>
          ) : (
            <div className="goland-loading-repositories">
              {workspace.repositories.map((repository) => (
                <label className={`goland-loading-repository ${selection.repositoryIds.includes(repository.catalogRepositoryId) ? 'selected' : ''}`} key={repository.catalogRepositoryId}>
                  <input aria-label={repository.name} checked={selection.repositoryIds.includes(repository.catalogRepositoryId)} onChange={(event) => change({
                    mode: 'selected', repositoryIds: event.target.checked
                      ? [...selection.repositoryIds, repository.catalogRepositoryId]
                      : selection.repositoryIds.filter((id) => id !== repository.catalogRepositoryId),
                  })} type="checkbox" />
                  <Folder aria-hidden="true" size={18} />
                  <span className="goland-loading-repository-copy">
                    <span className="goland-loading-repository-name">{repository.name}</span>
                    <span className="goland-loading-repository-path" title={repository.relativePath}>{repository.relativePath}</span>
                  </span>
                </label>
              ))}
              {workspace.repositories.length === 0 && <p className="goland-loading-all-note">{t('workspaceDetail.repositories.empty')}</p>}
            </div>
          )}
        </fieldset>
        <div className="goland-loading-summary">
          <p>{t('golandLoading.count', { count: selectedCount, total: members.size })}</p>
          <p className="muted">{t('golandLoading.membershipSummary')}</p>
        </div>
        {error && <ErrorNotice error={error} />}
        <details className="workspace-disclosure">
          <summary>
            <ChevronRight aria-hidden="true" size={17} />
            <span><strong>{t('golandLoading.rulesTitle')}</strong><span className="workspace-disclosure-hint">{t('golandLoading.rulesSummary')}</span></span>
          </summary>
          <div className="workspace-disclosure-content muted">{t('golandLoading.membershipNotice')}</div>
        </details>
        {children}
      </div>
      <footer className="goland-loading-footer">
        <div className="goland-loading-footer-main">
          <p className={`goland-loading-status ${dirty ? 'unsaved' : 'saved'}`} aria-live="polite" role="status">
            <StatusIcon aria-hidden="true" className={pending || (loading && ready) ? 'spinning' : ''} size={13} />
            <span>{statusText}</span>
          </p>
          <div className="goland-loading-actions">
            <button className="button" disabled={disabled || errorBlocksSave} onClick={() => { void save(false); }} type="button">{t('golandLoading.save')}</button>
            <button className="button primary" disabled={disabled || errorBlocksSave || !available} onClick={() => { void save(true); }} type="button">{t('golandLoading.saveAndOpen')}</button>
          </div>
        </div>
        <div className="goland-loading-footer-secondary">
          <div className="goland-loading-links">
            <button className="text-button" disabled={!ready || busy || pending} onClick={() => { void reload(); }} type="button">{t('golandLoading.reload')}</button>
            {dirty && <button className="text-button muted" disabled={disabled} onClick={() => change(saved?.selection ?? { mode: 'all' })} type="button">{t('common.cancel')}</button>}
          </div>
          <p className="muted">{t('golandLoading.confirmSync')}</p>
        </div>
      </footer>
    </section>
  );
}
