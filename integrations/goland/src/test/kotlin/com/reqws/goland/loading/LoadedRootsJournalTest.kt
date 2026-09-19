package com.reqws.goland.loading

import com.reqws.goland.loading.model.LoadedRootsJournal
import com.reqws.goland.loading.model.LoadedRootsCodec
import com.reqws.goland.loading.model.rootsJournalFile
import com.reqws.goland.persistence.VerifiedAtomicStateFileException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class LoadedRootsJournalTest {
  @Rule @JvmField val temporary = TemporaryFolder()
  private fun shell(): Path = temporary.newFolder().toPath().toRealPath().also { Files.createDirectory(it.resolve(".idea")) }
  private fun value(shell: Path) = LoadedRootsJournal("ws_1", "95dc7c6a-0eaa-4c96-824a-e117316a1db3", shell.parent.toString(), shell.toString(), "shell-key", "idea-key", "ReqWS-test", shell.resolve(".idea/reqws/ReqWS-test.iml").toString(), emptyList(), emptyList(), emptyList())

  @Test fun ignoresOldLedgerAndRoundTripsOnlyTheNewFormat() {
    val shell = shell()
    Files.writeString(shell.resolve(".idea/reqws-managed-project-model.json"), "not authority")
    val file = rootsJournalFile(shell)
    assertNull(file.read())
    val state = value(shell)
    file.writeAndVerify(state)
    assertEquals(state, file.read())
    assertEquals("not authority", Files.readString(shell.resolve(".idea/reqws-managed-project-model.json")))
  }

  @Test fun rejectsUnknownOrMalformedLedgerFields() {
    val state = value(shell())
    val text = LoadedRootsCodec.encode(state).toString(Charsets.UTF_8)
    for (invalid in listOf(text.replace("\"formatVersion\":1", "\"formatVersion\":2"), text.replaceFirst("{", "{\"extra\":true,"), "{}")) {
      assertThrows(Exception::class.java) { LoadedRootsCodec.decode(invalid.toByteArray()) }
    }
  }

  @Test fun directoryLockExcludesIndependentHandlesAndReleasesAfterFailure() {
    val shell = shell()
    rootsJournalFile(shell).withStableParent { first ->
      first.tryAcquireExclusiveDirectoryLock()!!.use {
        rootsJournalFile(shell).withStableParent { second -> assertNull(second.tryAcquireExclusiveDirectoryLock()) }
      }
    }
    rootsJournalFile(shell).withStableParent { it.tryAcquireExclusiveDirectoryLock()!!.close() }
  }

  @Test fun differentJvmCannotBypassTheDirectoryInodeLock() {
    val shell = shell()
    rootsJournalFile(shell).withStableParent { first -> first.tryAcquireExclusiveDirectoryLock()!!.use {
      val outputFile = temporary.newFile("lock-probe.txt").toPath()
      val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Djna.boot.library.path=" + System.getProperty("jna.boot.library.path"), "-cp", System.getProperty("java.class.path"), JournalLockProbe::class.java.name, shell.toString())
        .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start()
      try {
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val output = Files.readString(outputFile)
        assertTrue(output, finished)
        assertEquals(output, 0, process.exitValue())
        assertTrue(output, output.lineSequence().any { it == "BUSY" })
      } finally { if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
    } }
  }

  @Test fun parentReplacementCannotRedirectTheAnchoredWrite() = runBlocking {
    val shell = shell()
    val original = value(shell)
    rootsJournalFile(shell).writeAndVerify(original)
    val failure = runCatching {
      rootsJournalFile(shell).withStableParentSuspending { stable -> stable.tryAcquireExclusiveDirectoryLock()!!.use {
        Files.move(shell.resolve(".idea"), shell.resolve("detached"))
        Files.createDirectory(shell.resolve(".idea"))
        Files.writeString(shell.resolve(".idea/reqws-loaded-roots.json"), "user replacement")
        stable.writeAndVerify(original)
      } }
    }.exceptionOrNull()
    assertNotNull(failure)
    assertEquals("user replacement", Files.readString(shell.resolve(".idea/reqws-loaded-roots.json")))
  }

  @Test fun symlinkParentFailsClosed() {
    val shell = shell()
    Files.move(shell.resolve(".idea"), shell.resolve("other"))
    Files.createSymbolicLink(shell.resolve(".idea"), shell.resolve("other"))
    assertThrows(VerifiedAtomicStateFileException::class.java) { rootsJournalFile(shell).writeAndVerify(value(shell)) }
    assertFalse(Files.exists(shell.resolve("other/reqws-loaded-roots.json")))
  }
}

object JournalLockProbe {
  @JvmStatic fun main(args: Array<String>) {
    rootsJournalFile(Path.of(args.single())).withStableParent { stable ->
      val lock = stable.tryAcquireExclusiveDirectoryLock()
      println(if (lock == null) "BUSY" else "LOCKED")
      lock?.close()
    }
  }
}
