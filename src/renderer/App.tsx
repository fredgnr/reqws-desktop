import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  AppSettings,
  CreateRepositoryInput,
  CreateWorkspaceInput,
  OperationProgress,
  Repository,
  RepositoryListItem,
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
import { WorkspacesPage } from './pages/WorkspacesPage';
import { toDisplayError } from './error-utils';

const api = window.reqws;

type Confirmation =
  | { kind: 'remove-repository'; repository: RepositoryListItem }
  | { kind: 'remove-workspace-repository'; repository: WorkspaceRepository }
  | { kind: 'forget-workspace' };

function operationTitle(progress: Pick<OperationProgress, 'kind'>): string {
  const titles: Record<OperationProgress['kind'], string> = {
    'create-workspace': '正在创建 Workspace',
    'add-repository': '正在增加 Repository',
    'remove-repository': '正在移除 Repository',
    'test-repository': '正在测试 Repository',
    'sync-workspace': '正在同步 Workspace',
  };
  return titles[progress.kind];
}

function toOperationView(progress: OperationProgress): OperationView {
  return {
    title: operationTitle(progress),
    message: progress.message,
    repositoryName: progress.repositoryName,
    current: progress.current,
    total: progress.total,
    done: progress.stage === 'done',
    error: progress.error,
  };
}

export function App(): React.JSX.Element {
  const [page, setPage] = useState<PageName>('workspaces');
  const [repositories, setRepositories] = useState<RepositoryListItem[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [availability, setAvailability] = useState<SystemAvailability | null>(null);
  const [settings, setSettings] = useState<AppSettings>({});
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
  const [activeOperation, setActiveOperation] = useState<OperationView | null>(null);
  const [busy, setBusy] = useState(false);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const toast = useCallback((message: string, tone: ToastMessage['tone'] = 'success'): void => {
    const id = Date.now() + Math.random();
    setToasts((current) => [...current, { id, message, tone }]);
    window.setTimeout(() => setToasts((current) => current.filter((item) => item.id !== id)), 3200);
  }, []);

  const loadData = useCallback(async (showRefresh = false): Promise<void> => {
    if (showRefresh) setRefreshing(true);
    try {
      const [nextRepositories, nextWorkspaces, nextAvailability, nextSettings] = await Promise.all([
        api.repositories.list(),
        api.workspaces.list(),
        api.editors.getAvailability(),
        api.workspaces.getSettings(),
      ]);
      setRepositories(nextRepositories);
      setWorkspaces(nextWorkspaces);
      setAvailability(nextAvailability);
      setSettings(nextSettings);
    } catch (error) {
      const normalized = toDisplayError(error);
      toast(`${normalized.code} · ${normalized.message}`, 'error');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [toast]);

  useEffect(() => {
    queueMicrotask(() => void loadData());
    return api.operations.onProgress((progress) => setActiveOperation(toOperationView(progress)));
  }, [loadData]);

  const currentRepository = repositoryDialog === 'new' ? undefined : repositoryDialog ?? undefined;
  const operationInProgress = busy || Boolean(activeOperation && !activeOperation.error && !activeOperation.done);

  const runEditorAction = async (action: () => Promise<void>, success: string): Promise<void> => {
    try {
      await action();
      toast(success);
    } catch (error) {
      const normalized = toDisplayError(error);
      toast(`${normalized.code} · ${normalized.message}`, 'error');
    }
  };

  const openDetails = async (id: string): Promise<void> => {
    if (detailLoading) return;
    setDetailLoading(true);
    try {
      setDetail(await api.workspaces.get(id));
    } catch (error) {
      const normalized = toDisplayError(error);
      toast(`${normalized.code} · ${normalized.message}`, 'error');
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
      toast(currentRepository ? `${input.name} 已更新` : `${input.name} 已录入`);
    } catch (error) {
      const normalized = toDisplayError(error);
      toast(`${normalized.code} · ${normalized.message}`, 'error');
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
      const normalized = toDisplayError(error);
      toast(`${normalized.code} · ${normalized.message}`, 'error');
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
      toast(result.success ? `${repository.name} 连接成功` : `${repository.name} 连接失败，仍可编辑和保存`, result.success ? 'success' : 'error');
    } catch (error) {
      const normalized = toDisplayError(error);
      toast(`${normalized.code} · ${normalized.message}`, 'error');
    } finally {
      setTestingId(null);
    }
  };

  const createWorkspace = async (input: CreateWorkspaceInput): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setActiveOperation({ title: '正在创建 Workspace', message: '正在校验输入…', current: 0, total: Math.max(1, input.repositoryIds.length + 2) });
    try {
      await api.workspaces.create(input);
      setCreateWorkspaceOpen(false);
      await loadData();
      setActiveOperation((current) => current ? { ...current, message: 'Workspace 创建完成', current: current.total, done: true } : null);
      toast(`${input.name} 已创建`);
    } catch (error) {
      const normalized = toDisplayError(error);
      setActiveOperation((current) => ({
        title: current?.title ?? '创建 Workspace 失败',
        message: normalized.message,
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
    title: string,
    action: () => Promise<WorkspaceDetail>,
    success: string,
  ): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setActiveOperation({ title, message: '准备操作…', current: 0, total: 1 });
    try {
      const nextDetail = await action();
      setDetail(nextDetail);
      await loadData();
      setActiveOperation((current) => current ? { ...current, message: success, current: current.total, done: true } : null);
      toast(success);
    } catch (error) {
      const normalized = toDisplayError(error);
      setActiveOperation((current) => ({ title: current?.title ?? title, message: normalized.message, current: current?.current ?? 0, total: current?.total ?? 1, error: normalized }));
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
        toast(`${confirmation.repository.name} 已从仓库目录删除；本地 clone 保留`);
      } catch (error) {
        const normalized = toDisplayError(error);
        toast(`${normalized.code} · ${normalized.message}`, 'error');
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
        '正在移除 Repository',
        () => api.workspaces.removeRepository({ workspaceId: detail.id, catalogRepositoryId: repository.catalogRepositoryId }),
        `${repository.name} 已移除，本地目录仍保留`,
      );
      return;
    }
    setBusy(true);
    try {
      await api.workspaces.forget(detail.id);
      setConfirmation(null);
      setDetail(null);
      await loadData();
      toast(`${detail.name} 已从 ReqWS 索引遗忘；磁盘内容保留`);
    } catch (error) {
      const normalized = toDisplayError(error);
      toast(`${normalized.code} · ${normalized.message}`, 'error');
    } finally {
      setBusy(false);
    }
  };

  const confirmationCopy = useMemo(() => {
    if (!confirmation) return null;
    if (confirmation.kind === 'remove-repository') {
      const references = confirmation.repository.referencedBy;
      return {
        title: `删除 ${confirmation.repository.name}？`,
        description: references.length > 0
          ? `该目录项仍被 ${references.join('、')} 引用。删除只影响仓库目录，已有 Workspace 快照和本地 clone 不变。`
          : '删除只影响仓库目录，不会删除任何本地 clone。',
        label: '删除目录项',
      };
    }
    if (confirmation.kind === 'remove-workspace-repository') return {
      title: `从 Workspace 移除 ${confirmation.repository.name}？`,
      description: '将更新 manifest 与 .code-workspace，但本地 repo 目录会完整保留，可稍后在 Finder 中手动处理。',
      label: '移除并保留目录',
    };
    return {
      title: `遗忘 ${detail?.name ?? 'Workspace'}？`,
      description: '仅从 ReqWS 全局索引移除；代码目录、manifest 与 .code-workspace 文件均保留。',
      label: '遗忘 Workspace',
    };
  }, [confirmation, detail?.name]);

  return (
    <>
      <AppShell
        onNavigate={setPage}
        onPrimary={() => page === 'workspaces' ? setCreateWorkspaceOpen(true) : (setTestResult(null), setRepositoryDialog('new'))}
        onRefresh={() => void loadData(true)}
        page={page}
        primaryDisabled={page === 'workspaces' && (!availability?.git.available || operationInProgress)}
        refreshing={refreshing}
        repositoryCount={repositories.length}
        workspaceCount={workspaces.length}
      >
        {page === 'workspaces' ? (
          <WorkspacesPage
            availability={availability}
            loading={loading}
            onCreate={() => setCreateWorkspaceOpen(true)}
            onDetails={(id) => void openDetails(id)}
            onOpenCursor={(id) => void runEditorAction(() => api.editors.openCursor(id), '已请求 Cursor 打开 Workspace')}
            onOpenVSCode={(id) => void runEditorAction(() => api.editors.openVSCode(id), '已请求 VS Code 打开 Workspace')}
            onSearch={setWorkspaceSearch}
            repositoryCount={repositories.length}
            search={workspaceSearch}
            workspaces={workspaces}
          />
        ) : (
          <RepositoriesPage
            gitAvailable={availability?.git.available ?? false}
            loading={loading}
            onCreate={() => { setTestResult(null); setRepositoryDialog('new'); }}
            onEdit={(repository) => { setTestResult(null); setRepositoryDialog(repository); }}
            onSearch={setRepositorySearch}
            onTest={(repository) => void testRepositoryRow(repository)}
            repositories={repositories}
            search={repositorySearch}
            testingId={testingId}
          />
        )}
      </AppShell>

      {createWorkspaceOpen && (
        <CreateWorkspaceDialog
          busy={busy}
          initialWorkspaceFileDirectory={settings.lastWorkspaceFileDirectory}
          initialWorkspaceParentDirectory={settings.lastWorkspaceParentDirectory}
          onClose={() => !busy && setCreateWorkspaceOpen(false)}
          onCreate={createWorkspace}
          onPickDirectory={(kind, suggestedPath) => api.dialogs.selectDirectory({
            title: kind === 'root' ? '选择 Workspace 代码目录的父目录' : '选择 .code-workspace 文件目录',
            defaultPath: suggestedPath || undefined,
            createDirectory: true,
          })}
          repositories={repositories}
        />
      )}

      {repositoryDialog && (
        <RepositoryDialog
          busy={busy}
          gitAvailable={availability?.git.available ?? false}
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
          onAddRepository={(repositoryId) => void mutateWorkspace('正在增加 Repository', () => api.workspaces.addRepository({ workspaceId: detail.id, repositoryId }), 'Repository 已加入 Workspace')}
          onClose={() => !busy && setDetail(null)}
          onForget={() => setConfirmation({ kind: 'forget-workspace' })}
          onOpenCursor={() => void runEditorAction(() => api.editors.openCursor(detail.id), '已请求 Cursor 打开 Workspace')}
          onOpenCursorRoot={() => void runEditorAction(() => api.editors.openCursorRoot(detail.id), '已请求 Cursor 打开代码根目录')}
          onOpenVSCode={() => void runEditorAction(() => api.editors.openVSCode(detail.id), '已请求 VS Code 打开 Workspace')}
          onRemoveRepository={(repository) => setConfirmation({ kind: 'remove-workspace-repository', repository })}
          onRevealFinder={() => void runEditorAction(() => api.editors.revealInFinder(detail.id), '已在 Finder 中显示代码目录')}
          onSync={() => void mutateWorkspace('正在同步 Workspace', () => api.workspaces.sync(detail.id), 'Workspace 文件已同步')}
          repositories={repositories}
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

      {activeOperation && (
        <OperationDialog
          onClose={() => {
            if (activeOperation.error || activeOperation.done || (activeOperation.total > 0 && activeOperation.current >= activeOperation.total)) setActiveOperation(null);
          }}
          operation={activeOperation}
        />
      )}
      {detailLoading && <div aria-live="polite" className="sr-only">正在加载 Workspace 详情</div>}
      <ToastRegion dismiss={(id) => setToasts((current) => current.filter((toastItem) => toastItem.id !== id))} toasts={toasts} />
    </>
  );
}
