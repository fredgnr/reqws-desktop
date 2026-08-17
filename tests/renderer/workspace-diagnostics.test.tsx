// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { WorkspaceDetailDrawer } from '../../src/renderer/components/WorkspaceDetailDrawer';
import i18n, { initializeI18n } from '../../src/renderer/i18n';
import type {
  EditorAvailability,
  Repository,
  WorkspaceDetail,
} from '../../src/shared/types';

const workspace: WorkspaceDetail = {
  schemaVersion: 1,
  id: 'ws-one',
  name: 'FEAT-123-payment-refund',
  featureBranch: 'feature/FEAT-123',
  rootPath: '/Users/rose/Developer/features/FEAT-123',
  workspaceFilePath: '/Users/rose/Developer/workspaces/FEAT-123.code-workspace',
  repositories: [],
  status: 'missing',
  missingArtifacts: ['workspace-root', 'manifest', 'workspace-file'],
  createdAt: '2026-08-12T00:00:00Z',
  updatedAt: '2026-08-12T00:00:00Z',
};

function renderDrawer(
  detail: WorkspaceDetail = workspace,
  availability: EditorAvailability | null = {
    git: { available: true, path: '/usr/bin/git' },
    vscode: { available: true, path: '/Applications/Visual Studio Code.app' },
    cursor: { available: true, path: '/Applications/Cursor.app' },
    goland: { available: true, path: '/Applications/GoLand.app' },
  },
  repositories: Repository[] = [],
): void {
  render(
    <WorkspaceDetailDrawer
      availability={availability}
      busy={false}
      editorLaunching={false}
      onAddRepository={vi.fn()}
      onClose={vi.fn()}
      onForget={vi.fn()}
      onOpenCursor={vi.fn()}
      onOpenCursorRoot={vi.fn()}
      onOpenGoLand={vi.fn()}
      onOpenVSCode={vi.fn()}
      onRemoveRepository={vi.fn()}
      onRevealFinder={vi.fn()}
      onSync={vi.fn()}
      repositories={repositories}
      workspace={detail}
    />,
  );
}

beforeAll(() => initializeI18n('zh-CN'));
beforeEach(() => i18n.changeLanguage('zh-CN'));
afterEach(cleanup);

describe('Workspace diagnostics', () => {
  it('localizes the exact missing artifacts in Chinese and English', async () => {
    renderDrawer();

    expect(screen.getByText(
      /^缺失：代码目录.*工作区清单.*\.code-workspace 文件。$/u,
    ))
      .toBeInTheDocument();

    await i18n.changeLanguage('en-US');

    expect(await screen.findByText(
      /^Missing: Code folder.*Workspace manifest.*\.code-workspace file\.$/u,
    ))
      .toBeInTheDocument();
  });

  it('keeps the generic warning for legacy details without structured artifacts', () => {
    const legacy = { ...workspace };
    delete legacy.missingArtifacts;
    renderDrawer(legacy);

    expect(screen.getByText(i18n.t('workspaceDetail.pathsMissing')))
      .toBeInTheDocument();
  });

  it('does not announce missing editors before availability is known', () => {
    renderDrawer({
      ...workspace,
      status: 'ready',
      missingArtifacts: [],
    }, null, [{
      id: 'repo-available',
      name: 'available-repository',
      url: 'git@example.com:team/available-repository.git',
      defaultBranch: 'main',
      createdAt: '2026-08-12T00:00:00Z',
      updatedAt: '2026-08-12T00:00:00Z',
    }]);

    for (const editor of ['VS Code', 'Cursor', 'GoLand']) {
      const button = screen.getByRole('button', { name: editor });
      expect(button).toBeDisabled();
      expect(button).not.toHaveAttribute('title');
      expect(button).not.toHaveAttribute('aria-describedby');
    }
    const addRepository = screen.getByRole('button', { name: /添加/u });
    expect(addRepository).toBeDisabled();
    expect(addRepository).not.toHaveAttribute('title');
    expect(screen.queryByText(/未找到/u)).not.toBeInTheDocument();
  });
});
