package com.reqws.goland.vcs

import java.nio.file.Path
import java.text.Normalizer

/** Stable filesystem identities used only for comparing user-owned VCS mappings. */
internal object VcsPathIdentity {
  fun lexical(path: Path): String = normalize(path.toAbsolutePath().normalize().toString())

  fun canonical(path: Path): String? = try {
    lexical(path.toRealPath())
  } catch (_: Exception) {
    null
  }

  fun mappingIdentities(projectRoot: Path, directory: String): Set<String> {
    val path = try {
      if (directory.isEmpty()) {
        projectRoot
      } else {
        Path.of(directory).let { candidate ->
          if (candidate.isAbsolute) candidate else projectRoot.resolve(candidate)
        }
      }
    } catch (_: Exception) {
      return emptySet()
    }
    return buildSet {
      add(lexical(path))
      canonical(path)?.let(::add)
    }
  }

  fun repositoryIdentities(path: Path, liveCanonicalPath: Path): Set<String> = buildSet {
    add(lexical(path))
    add(lexical(liveCanonicalPath))
  }

  fun isWithin(projectRoot: Path, identity: String): Boolean = try {
    Path.of(identity).startsWith(Path.of(lexical(projectRoot)))
  } catch (_: Exception) {
    false
  }

  private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
}
