package okay.rust

import java.nio.file.{Files, Path, Paths}

/**
 * Where a kernel's source is, from wherever the tests run. A forked JVM test
 * starts in the PLATFORM directory (okay-native/.jvm since okay-native became a
 * cross project), sbt in the module's or the repository's — and a path
 * that assumed one of them sent every Live test to a directory that did
 * not exist (found by kdf-to-password-hash, 2026-09-24: "Cannot run program
 * cargo (in directory okay-native/kernels/argon2)").
 */
object Kernels:
  def dir(name: String): Path =
    Vector(Paths.get("kernels", name), Paths.get("..", "kernels", name), Paths.get("okay-native", "kernels", name))
      .find(Files.isDirectory(_))
      .getOrElse(throw IllegalStateException(s"kernels/$name is not under ${Paths.get("").toAbsolutePath}"))
