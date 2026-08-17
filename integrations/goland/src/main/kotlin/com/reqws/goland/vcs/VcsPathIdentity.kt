package com.reqws.goland.vcs

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer

internal object VcsPathIdentity {
  fun lexical(path: Path): String = normalize(path.toAbsolutePath().normalize().toString())

  fun canonical(path: Path): String? = try {
    lexical(path.toRealPath())
  } catch (_: Exception) {
    null
  }

  /** Textual equality with macOS Unicode normalization, without collapsing dot segments. */
  fun sameStoredDirectory(first: String, second: String): Boolean =
    normalize(first) == normalize(second)

  fun mappingLexical(projectRoot: Path, directory: String): String? {
    if (directory.isEmpty()) return null
    return try {
      val raw = Path.of(directory)
      lexical(if (raw.isAbsolute) raw else projectRoot.resolve(raw))
    } catch (_: Exception) {
      null
    }
  }

  fun mappingCanonical(projectRoot: Path, directory: String): String? {
    if (directory.isEmpty()) return null
    return try {
      val raw = Path.of(directory)
      canonical(if (raw.isAbsolute) raw else projectRoot.resolve(raw))
    } catch (_: Exception) {
      null
    }
  }

  fun resolveOwned(projectRoot: Path, relativeDirectory: String): OwnedPath? {
    if (relativeDirectory.isBlank()) return null
    return try {
      val relative = Path.of(relativeDirectory)
      val normalizedRelative = relative.normalize()
      if (
        relative.isAbsolute ||
        relative.any { it.toString() == ".." || it.toString() == "." } ||
        normalize(relative.toString()) != normalize(normalizedRelative.toString())
      ) {
        return null
      }
      val normalizedRoot = projectRoot.toAbsolutePath().normalize()
      val resolved = normalizedRoot.resolve(normalizedRelative).normalize()
      if (resolved == normalizedRoot || !resolved.startsWith(normalizedRoot)) return null
      if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
        val canonical = resolved.toRealPath()
        // ReqWS records canonical relative paths. An existing alias is not proof of ownership,
        // even when the link currently remains inside the workspace.
        if (
          !canonical.startsWith(normalizedRoot) ||
          lexical(canonical) != lexical(resolved)
        ) {
          return null
        }
      }
      OwnedPath(
        relativeDirectory = normalize(normalizedRoot.relativize(resolved).toString()),
        directory = resolved.toString(),
        lexicalIdentity = lexical(resolved),
      )
    } catch (_: Exception) {
      null
    }
  }

  private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
}

internal data class OwnedPath(
  val relativeDirectory: String,
  val directory: String,
  val lexicalIdentity: String,
)
