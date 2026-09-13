import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CreateRepositoryInput,
  CreateWorkspaceInput,
  OperationProgress,
  OperationRollbackReason,
  Repository,
  RepositoryListItem,
  ResolvedGlobalSettings,
  SystemAvailability,
  TestRepositoryResult,
  WorkspaceDetail,
  WorkspaceRepository,
  WorkspaceSummary,
} from '../shared/types';
import { AppShell, type PageName } from './components/AppShell';
import { ConfirmDialog } from './components/ConfirmDialog';
import { CreateWorkspaceDialog } from './components/CreateWorkspaceDialog';
import { OperationDialog, type OperationView } from './components/OperationDialog';
import { RepositoryDialog } from './components/RepositoryDialog';
import { ToastRegion, type ToastMessage } from './components/Toast';
import { WorkspaceDetailDrawer } from './components/WorkspaceDetailDrawer';
import { RepositoriesPage } from './pages/RepositoriesPage';
import { SettingsPage } from './pages/settings/SettingsPage';
import { WorkspacesPage } from './pages/WorkspacesPage';
import {
  errorMessageKey,
  toDisplayError,
  type DisplayError,
} from './error-utils';

const api = window.reqws;

type Confirmation =
  | { kind: 'remove-repository'; repository: RepositoryListItem }
  | { kind: 'remove-workspace-repository'; repository: WorkspaceRepository }
  | { kind: 'forget-workspace' };

const rollbackReasonMessageKeys: Record<OperationRollbackReason, string> = {
  CLEANING_STAGING: 'operation.rollbackReasons.CLEANING_STAGING',
  RETAINING_PUBLISHED_ARTIFACTS:
    'operation.rollbackReasons.RETAINING_PUBLISHED_ARTIFACTS',
};

interface ActiveOperation extends Omit<OperationProgress, 'error'> {
  error?: DisplayError;
}

export function App({
  initialSettings = null,
}: {
  initialSettings?: ResolvedGlobalSettings | null;
}): React.JSX.Element {
  const { i18n, t } = useTranslation();
  const [page, setPage] = useState<PageName>('workspaces');
  const [repositories, setRepositories] = useState<RepositoryListItem[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [availability, setAvailability] = useState<SystemAvailability | null>(null);
  const [settings, setSettings] = useState<ResolvedGlobalSettings | null>(initialSettings);
  const [settingsLoading, setSettingsLoading] = useState(initialSettings === null);
  const [workspaceSearch, setWorkspaceSearch] = useState('');
  const [repositorySearch, setRepositorySearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [createWorkspaceOpen, setCreateWorkspaceOpen] = useState(false);
  const [repositoryDialog, setRepositoryDialog] = useState<Repository | 'new' | null>(null);
  const [testResult, setTestResult] = useState<TestRepositoryResult | null>(null);
  const [detail, setDetail] = useState<WorkspaceDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [activeOperation, setActiveOperation] = useState<ActiveOperation | null>(null);
  const [busy, setBusy] = useState(false);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [launchingWorkspaceIds, setLaunchingWorkspaceIds] = useState<
    ReadonlySet<string>
  >(new Set());
  const editorActionsInFlight = useRef(new Set<string>());
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const toast = useCallback((
    messageKey: string,
    values: Record<string, string | number> = {},
    tone: ToastMessage['tone'] = 'success',
    errorCode?: string,
  ): void => {
    const id = Date.now() + Math.random();
    setToasts((current) => [
      ...current,
      {
        id,
        messageKey,
        values,
        tone,
        ...(errorCode ? { errorCode } : {}),
      },
    ]);
    window.setTimeout(() => setToasts((current) => current.filter((item) => item.id !== id)), 3200);
  }, []);

  const toastText = useCallback((
    message: string,
    tone: ToastMessage['tone'] = 'success',
  ): void => {
    const id = Date.now() + Math.random();
    setToasts((current) => [...current, { id, message, tone }]);
    window.setTimeout(() => setToasts((current) => current.filter((item) => item.id !== id)), 3200);
  }, []);

  const toastError = useCallback((error: unknown): void => {
    const normalized = toDisplayError(error);
    const key = errorMessageKey(normalized.code);
    toast(i18n.exists(key) ? key : 'errors.fallback', {}, 'error', normalized.code);
  }, [i18n, toast]);

  const loadData = useCallback(async (showRefresh = false): Promise<void> => {
    if (showRefresh) setRefreshing(true);
    try {
      const [nextRepositories, nextWorkspaces, nextAvailability] = await Promise.all([
        api.repositories.list(),
        api.workspaces.list(),
        api.editors.getAvailability(),
      ]);
      setRepositories(nextRepositories);
      setWorkspaces(nextWorkspaces);
      setAvailability(nextAvailability);
    } catch (error) {
      toastError(error);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [toastError]);

  useEffect(() => {
    queueMicrotask(() => void loadData());
    return api.operations.onProgress(setActiveOperation);
  }, [loadData]);

  useEffect(() => {
    if (initialSettings) return;
    let active = true;
    void api.settings.get()
      .then(async (nextSettings) => {
        if (!active) return;
        setSettings(nextSettings);
        await i18n.changeLanguage(nextSettings.effectiveLocale);
      })
      .catch(toastError)
      .finally(() => {
        if (active) setSettingsLoading(false);
      });
    return () => { active = false; };
  }, [i18n, initialSettings, toastError]);

  const handleSettingsSaved = useCallback((nextSettings: ResolvedGlobalSettings): void => {
    setSettings(nextSettings);
  }, []);

  let operationView: OperationView | null = null;
  if (activeOperation) {
    const errorKey = activeOperation.error
      ? errorMessageKey(activeOperation.error.code)
      : null;
    const rollbackReasonKey = activeOperation.rollbackReason
      ? rollbackReasonMessageKeys[activeOperation.rollbackReason]
      : undefined;
    operationView = {
      title: t(`operation.titles.${activeOperation.kind}`),
      message: errorKey && i18n.exists(errorKey)
        ? t(errorKey)
        : rollbackReasonKey && i18n.exists(rollbackReasonKey)
          ? t(rollbackReasonKey)
          : t(`operation.stages.${activeOperation.stage}`),
      repositoryName: activeOperation.repositoryName,
      current: activeOperation.current,
      total: activeOperation.total,
      done: activeOperation.stage === 'done',
      error: activeOperation.error,
    };
  }

  const currentRepository = repositoryDialog === 'new' ? undefined : repositoryDialog ?? undefined;
  const operationInProgress = busy || Boolean(
    activeOperation && activeOperation.stage !== 'error' && activeOperation.stage !== 'done',
  );
  const workspaceCreationUnavailable = settingsLoading
    || !availability?.git.available
    || operationInProgress;

  const runEditorAction = async (
    workspaceId: string,
    action: () => Promise<void>,
    success: string,
  ): Promise<void> => {
    if (editorActionsInFlight.current.has(workspaceId)) return;
    editorActionsInFlight.current.add(workspaceId);
    setLaunchingWorkspaceIds(new Set(editorActionsInFlight.current));
    try {
      await action();
      toast(success);
    } catch (error) {
      toastError(error);
    } finally {
      editorActionsInFlight.current.delete(workspaceId);
      setLaunchingWorkspaceIds(new Set(editorActionsInFlight.current));
    }
  };

  const openDetails = async (id: string): Promise<void> => {
    if (detailLoading) return;
    setDetailLoading(true);
    try {
      setDetail(await api.workspaces.get(id));
    } catch (error) {
      toastError(error);
    } finally {
      setDetailLoading(false);
    }
  };

  const saveRepository = async (input: CreateRepositoryInput): Promise<void> => {
    if (busy) return;
    setBusy(true);
    try {
      if (currentRepository) await api.repositories.update({ ...input, id: currentRepository.id });
      else await api.repositories.create(input);
      setRepositoryDialog(null);
      setTestResult(null);
      await loadData();
      toast(currentRepository ? 'app.toasts.repositoryUpdated' : 'app.toasts.repositoryCreated', { name: input.name });
    } catch (error) {
      toastError(error);
      throw error;
    } finally {
      setBusy(false);
    }
  };

  const testRepository = async (input: CreateRepositoryInput): Promise<void> => {
    if (busy) return;
    setBusy(true);
    try {
      setTestResult(await api.repositories.testConnection({ url: input.url }));
    } catch (error) {
      toastError(error);
      throw error;
    } finally {
      setBusy(false);
    }
  };

  const testRepositoryRow = async (repository: Repository): Promise<void> => {
    if (testingId) return;
    setTestingId(repository.id);
    try {
      const result = await api.repositories.testConnection({ url: repository.url });
      toast(
        result.success ? 'app.toasts.repositoryTestSucceeded' : 'app.toasts.repositoryTestFailed',
        { name: repository.name },
        result.success ? 'success' : 'error',
      );
    } catch (error) {
      toastError(error);
    } finally {
      setTestingId(null);
    }
  };

  const createWorkspace = async (input: CreateWorkspaceInput): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setActiveOperation({
      operationId: `renderer-${Date.now()}`,
      kind: 'create-workspace',
      stage: 'validating',
      message: '',
      current: 0,
      total: Math.max(1, input.repositoryIds.length + 2),
    });
    try {
      await api.workspaces.create(input);
      setCreateWorkspaceOpen(false);
      await loadData();
      setActiveOperation((current) => current ? { ...current, stage: 'done', current: current.total } : null);
      toast('app.toasts.workspaceCreated', { name: input.name });
    } catch (error) {
      const normalized = toDisplayError(error);
      setActiveOperation((current) => ({
        operationId: current?.operationId ?? `renderer-${Date.now()}`,
        kind: current?.kind ?? 'create-workspace',
        stage: 'error',
        message: '',
        current: current?.current ?? 0,
        total: current?.total ?? 1,
        error: normalized,
      }));
      throw error;
    } finally {
      setBusy(false);
    }
  };

  const mutateWorkspace = async (
    kind: Extract<OperationProgress['kind'], 'add-repository' | 'remove-repository' | 'sync-workspace'>,
    action: () => Promise<WorkspaceDetail>,
    successKey: string,
    successValues: Record<string, string | number> = {},
  ): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setActiveOperation({
      operationId: `renderer-${Date.now()}`,
      kind,
      stage: 'validating',
      message: '',
      current: 0,
      total: 1,
    });
    try {
      const nextDetail = await action();
      setDetail(nextDetail);
      await loadData();
      setActiveOperation((current) => current ? { ...current, stage: 'done', current: current.total } : null);
      toast(successKey, successValues);
    } catch (error) {
      const normalized = toDisplayError(error);
      setActiveOperation((current) => ({
        operationId: current?.operationId ?? `renderer-${Date.now()}`,
        kind: current?.kind ?? kind,
        stage: 'error',
        message: '',
        current: current?.current ?? 0,
        total: current?.total ?? 1,
        error: normalized,
      }));
    } finally {
      setBusy(false);
    }
  };

  const confirmAction = async (): Promise<void> => {
    if (!confirmation || busy) return;
    if (confirmation.kind === 'remove-repository') {
      setBusy(true);
      try {
        await api.repositories.remove(confirmation.repository.id, confirmation.repository.workspaceUsageCount > 0);
        setConfirmation(null);
        setRepositoryDialog(null);
        await loadData();
        toast('app.toasts.repositoryRecordRemoved', { name: confirmation.repository.name });
      } catch (error) {
        toastError(error);
      } finally {
        setBusy(false);
      }
      return;
    }
    if (!detail) return;
    if (confirmation.kind === 'remove-workspace-repository') {
      const repository = confirmation.repository;
      setConfirmation(null);
      await mutateWorkspace(
        'remove-repository',
        () => api.workspaces.removeRepository({ workspaceId: detail.id, catalogRepositoryId: repository.catalogRepositoryId }),
        'app.toasts.workspaceRepositoryRemoved',
        { name: repository.name },
      );
      return;
    }
    setBusy(true);
    try {
      await api.workspaces.forget(detail.id);
      setConfirmation(null);
      setDetail(null);
      await loadData();
      toast('app.toasts.workspaceRecordRemoved', { name: detail.name });
    } catch (error) {
      toastError(error);
    } finally {
      setBusy(false);
    }
  };

  const confirmationCopy = useMemo(() => {
    if (!confirmation) return null;
    if (confirmation.kind === 'remove-repository') {
      const references = confirmation.repository.referencedBy;
      const formattedReferences = new Intl.ListFormat(
        i18n.resolvedLanguage ?? i18n.language,
      ).format(references);
      return {
        title: t('app.confirmations.removeRepository.title', {
          name: confirmation.repository.name,
        }),
        description: references.length > 0
          ? t('app.confirmations.removeRepository.referenced', {
              name: confirmation.repository.name,
              workspaces: formattedReferences,
            })
          : t('app.confirmations.removeRepository.unreferenced'),
        label: t('app.confirmations.removeRepository.action'),
      };
    }
    if (confirmation.kind === 'remove-workspace-repository') return {
      title: t('app.confirmations.removeWorkspaceRepository.title', {
        name: confirmation.repository.name,
      }),
      description: t('app.confirmations.removeWorkspaceRepository.description'),
      label: t('app.confirmations.removeWorkspaceRepository.action'),
    };
    return {
      title: t('app.confirmations.forgetWorkspace.title', {
        name: detail?.name ?? t('navigation.workspaces'),
      }),
      description: t('app.confirmations.forgetWorkspace.description'),
      label: t('app.confirmations.forgetWorkspace.action'),
    };
  }, [confirmation, detail?.name, i18n.language, i18n.resolvedLanguage, t]);

  return (
    <>
      <AppShell
        onNavigate={setPage}
        onPrimary={() => {
          if (page === 'workspaces') {
            if (!workspaceCreationUnavailable) setCreateWorkspaceOpen(true);
          } else {
            setTestResult(null);
            setRepositoryDialog('new');
          }
        }}
        onRefresh={() => void loadData(true)}
        page={page}
        primaryDisabled={page === 'workspaces' && workspaceCreationUnavailable}
        refreshing={refreshing}
        repositoryCount={repositories.length}
        workspaceCount={workspaces.length}
      >
        {page === 'workspaces' ? (
          <WorkspacesPage
            availability={availability}
            loading={loading || settingsLoading}
            onCreate={() => {
              if (!workspaceCreationUnavailable) setCreateWorkspaceOpen(true);
            }}
            onDetails={(id) => void openDetails(id)}
            onOpenCursor={(id) => void runEditorAction(id, () => api.editors.openCursor(id), 'app.toasts.openedCursor')}
            onOpenGoLand={(id) => void runEditorAction(id, () => api.editors.openGoLand(id), 'app.toasts.openedGoLand')}
            onOpenVSCode={(id) => void runEditorAction(id, () => api.editors.openVSCode(id), 'app.toasts.openedVSCode')}
            onSearch={setWorkspaceSearch}
            repositoryCount={repositories.length}
            search={workspaceSearch}
            launchingWorkspaceIds={launchingWorkspaceIds}
            workspaces={workspaces}
          />
        ) : page === 'repositories' ? (
          <RepositoriesPage
            gitAvailable={availability?.git.available ?? null}
            loading={loading}
            onCreate={() => { setTestResult(null); setRepositoryDialog('new'); }}
            onEdit={(repository) => { setTestResult(null); setRepositoryDialog(repository); }}
            onSearch={setRepositorySearch}
            onTest={(repository) => void testRepositoryRow(repository)}
            repositories={repositories}
            search={repositorySearch}
            testingId={testingId}
          />
        ) : (
          <SettingsPage
            loading={settingsLoading}
            onSaved={handleSettingsSaved}
            onToast={toastText}
            settings={settings}
          />
        )}
      </AppShell>

      {createWorkspaceOpen && (
        <CreateWorkspaceDialog
          busy={busy}
          initialWorkspaceFileDirectory={settings?.workspaceFileDirectory ?? undefined}
          initialWorkspaceParentDirectory={settings?.workspaceParentDirectory ?? undefined}
          invalidDirectoryFields={settings?.invalidDirectoryFields}
          onClose={() => !busy && setCreateWorkspaceOpen(false)}
          onCreate={createWorkspace}
          onPickDirectory={(kind, suggestedPath) => api.dialogs.selectDirectory({
            title: t(kind === 'root'
              ? 'app.directoryDialogs.workspaceParent'
              : 'app.directoryDialogs.workspaceFile'),
            defaultPath: suggestedPath || undefined,
            createDirectory: true,
          })}
          repositories={repositories}
        />
      )}

      {repositoryDialog && (
        <RepositoryDialog
          busy={busy}
          gitAvailable={availability?.git.available ?? null}
          onClose={() => !busy && (setRepositoryDialog(null), setTestResult(null))}
          onDelete={currentRepository ? () => {
            const item = repositories.find((repository) => repository.id === currentRepository.id);
            if (item) setConfirmation({ kind: 'remove-repository', repository: item });
          } : undefined}
          onSave={saveRepository}
          onTest={testRepository}
          repository={currentRepository}
          testResult={testResult}
        />
      )}

      {detail && (
        <WorkspaceDetailDrawer
          availability={availability}
          busy={busy}
          onAddRepository={(repositoryId) => void mutateWorkspace(
            'add-repository',
            () => api.workspaces.addRepository({ workspaceId: detail.id, repositoryId }),
            'app.toasts.repositoryAdded',
          )}
          onClose={() => !busy && setDetail(null)}
          onForget={() => setConfirmation({ kind: 'forget-workspace' })}
          onOpenCursor={() => void runEditorAction(detail.id, () => api.editors.openCursor(detail.id), 'app.toasts.openedCursor')}
          onOpenCursorRoot={() => void runEditorAction(detail.id, () => api.editors.openCursorRoot(detail.id), 'app.toasts.openedCursorRoot')}
          onOpenGoLand={() => void runEditorAction(detail.id, () => api.editors.openGoLand(detail.id), 'app.toasts.openedGoLand')}
          onOpenVSCode={() => void runEditorAction(detail.id, () => api.editors.openVSCode(detail.id), 'app.toasts.openedVSCode')}
          onRemoveRepository={(repository) => setConfirmation({ kind: 'remove-workspace-repository', repository })}
          onRevealFinder={() => void runEditorAction(detail.id, () => api.editors.revealInFinder(detail.id), 'app.toasts.revealedFinder')}
          onSync={() => void mutateWorkspace(
            'sync-workspace',
            () => api.workspaces.sync(detail.id),
            'app.toasts.workspaceSynced',
          )}
          repositories={repositories}
          editorLaunching={launchingWorkspaceIds.has(detail.id)}
          workspace={detail}
        />
      )}

      {confirmation && confirmationCopy && (
        <ConfirmDialog
          busy={busy}
          confirmLabel={confirmationCopy.label}
          danger
          description={confirmationCopy.description}
          onCancel={() => !busy && setConfirmation(null)}
          onConfirm={() => void confirmAction()}
          title={confirmationCopy.title}
        />
      )}

      {activeOperation && operationView && (
        <OperationDialog
          onClose={() => {
            if (
              activeOperation.stage === 'error'
              || activeOperation.stage === 'done'
              || (activeOperation.total > 0 && activeOperation.current >= activeOperation.total)
            ) setActiveOperation(null);
          }}
          operation={operationView}
        />
      )}
      {detailLoading && <div aria-live="polite" className="sr-only">{t('app.loadingWorkspaceDetail')}</div>}
      <ToastRegion dismiss={(id) => setToasts((current) => current.filter((toastItem) => toastItem.id !== id))} toasts={toasts} />
    </>
  );
}
