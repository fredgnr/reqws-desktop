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
}

@Remote("com.reqws.goland.loading.contract.GoLandProject", plugin = "com.reqws.workspace")
interface ReqwsRemoteProject {
  fun getRevision(): Long
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
