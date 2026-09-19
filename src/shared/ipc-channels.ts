export const IPC_CHANNELS = {
  goLandWorkspaces: {
    read: 'goland-workspaces:read',
    prepare: 'goland-workspaces:prepare',
    save: 'goland-workspaces:save',
  },
  updates: {
    getState: 'updates:get-state',
    check: 'updates:check',
    download: 'updates:download',
    install: 'updates:install',
    stateChanged: 'updates:state-changed',
  },
  repositories: {
    list: 'repositories:list',
    create: 'repositories:create',
    update: 'repositories:update',
    remove: 'repositories:remove',
    test: 'repositories:test',
  },
  workspaces: {
    list: 'workspaces:list',
    get: 'workspaces:get',
    create: 'workspaces:create',
    addRepository: 'workspaces:add-repository',
    removeRepository: 'workspaces:remove-repository',
    sync: 'workspaces:sync',
    forget: 'workspaces:forget',
  },
  settings: {
    get: 'settings:get',
    save: 'settings:save',
  },
  dialogs: {
    selectDirectory: 'dialogs:select-directory',
  },
  editors: {
    availability: 'editors:availability',
    openVSCode: 'editors:open-vscode',
    openCursor: 'editors:open-cursor',
    openCursorRoot: 'editors:open-cursor-root',
    openGoLand: 'editors:open-goland',
    revealInFinder: 'editors:reveal-in-finder',
  },
  operationProgress: 'operation:progress',
} as const;

export type IpcResult<T> =
  | { ok: true; value: T }
  | { ok: false; error: import('./errors').ReqwsErrorPayload };
