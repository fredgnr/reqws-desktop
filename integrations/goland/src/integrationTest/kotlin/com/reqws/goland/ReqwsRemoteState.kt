package com.reqws.goland

import com.intellij.driver.client.Remote

// These proxies only read existing public getters through the test Driver plugin.
// No test listener, remote endpoint or mutation hook is included in the production ZIP.
@Remote("com.reqws.goland.project.ReqwsProjectService", plugin = "com.reqws.workspace")
interface ReqwsRemoteService {
  fun getState(): ReqwsRemoteState
}

@Remote("com.reqws.goland.project.ReqwsProjectState", plugin = "com.reqws.workspace")
interface ReqwsRemoteState {
  fun getLifecycle(): ReqwsRemoteLifecycle
  fun getSnapshot(): ReqwsRemoteSnapshot?
  fun getValidatedProjectionDigest(): String?
  fun getLastAppliedDigest(): String?
  fun getLastError(): ReqwsRemoteError?
}

@Remote("com.reqws.goland.project.ReqwsProjectError", plugin = "com.reqws.workspace")
interface ReqwsRemoteError {
  fun getCode(): String
}

@Remote("com.reqws.goland.project.ReqwsLifecycleState", plugin = "com.reqws.workspace")
interface ReqwsRemoteLifecycle {
  fun name(): String
}

@Remote("com.reqws.goland.manifest.ManifestSnapshot", plugin = "com.reqws.workspace")
interface ReqwsRemoteSnapshot {
  fun getLoading(): ReqwsRemoteLoading?
}

@Remote("com.reqws.goland.loading.contract.LoadingSnapshot", plugin = "com.reqws.workspace")
interface ReqwsRemoteLoading {
  fun getProject(): ReqwsRemoteProject
  fun getDigest(): String
  fun getLoadedIds(): Set<String>
}

@Remote("com.reqws.goland.loading.contract.GoLandProject", plugin = "com.reqws.workspace")
interface ReqwsRemoteProject {
  fun getRevision(): Long
  fun getWorkspaceId(): String
  fun getBindingId(): String
}

// Only this public read API is used. Trust is changed by real dialog clicks.
@Remote("com.intellij.ide.trustedProjects.TrustedProjects")
interface RemoteTrustedProjects {
  fun isProjectTrusted(project: com.intellij.driver.sdk.Project): Boolean
}

@Remote("com.intellij.openapi.roots.ProjectFileIndex")
interface RemoteProjectFileIndex {
  fun isInContent(file: com.intellij.driver.sdk.VirtualFile): Boolean
  fun isExcluded(file: com.intellij.driver.sdk.VirtualFile): Boolean
}

@Remote("com.intellij.openapi.vfs.LocalFileSystem")
interface RemoteLocalFileSystem {
  fun getInstance(): RemoteLocalFileSystem
  fun findFileByPath(path: String): com.intellij.driver.sdk.VirtualFile?
}
