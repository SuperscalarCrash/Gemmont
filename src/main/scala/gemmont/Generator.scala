package gemmont

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

import chisel3.stage.ChiselStage
import gemmont.top.ChiplabCoreTop
import scala.annotation.nowarn
import scala.collection.JavaConverters._

object Generator extends App {
  private def booleanSetting(
      property: String,
      environment: String,
      default: Boolean
  ): Boolean =
    sys.props
      .get(property)
      .orElse(sys.env.get(environment))
      .map(_.toBoolean)
      .getOrElse(default)

  private def inlineBlackBoxSources(targetDir: File, output: File): Unit = {
    val resourceList = new File(targetDir, "firrtl_black_box_resource_files.f")
    if (!resourceList.isFile) return

    Files
      .readAllLines(resourceList.toPath, StandardCharsets.UTF_8)
      .asScala
      .map(_.trim)
      .filter(_.nonEmpty)
      .foreach { sourceName =>
        val source = new File(sourceName)
        val contents = Files.readString(source.toPath, StandardCharsets.UTF_8)
        Files.writeString(
          output.toPath,
          s"\n$contents\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND
        )
        Files.delete(source.toPath)
      }

    Files.delete(resourceList.toPath)
  }

  private def guardChiselAssertionsForSynthesis(file: File): Unit = {
    val path = file.toPath
    val guardedLines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.flatMap { line =>
      val indent = line.takeWhile(_.isWhitespace)
      val trimmed = line.drop(indent.length)

      if (trimmed.startsWith("assert(") && trimmed.contains(");")) {
        Seq(s"${indent}`ifndef SYNTHESIS", line, s"${indent}`endif")
      } else {
        Seq(line)
      }
    }

    Files.write(path, guardedLines.asJava, StandardCharsets.UTF_8)
  }

  private def sanitizeGeneratedSourcePaths(file: File, sourceRoot: File): Unit = {
    val canonicalRoot = sourceRoot.getCanonicalPath.replace('\\', '/')
    val rootPrefixes = Seq(canonicalRoot, canonicalRoot.stripPrefix("/")).distinct
    val original = Files.readString(file.toPath, StandardCharsets.UTF_8)
    val sanitized = rootPrefixes.foldLeft(original) { (contents, prefix) =>
      contents.replace(s"${prefix}/", "")
    }
    if (sanitized != original) {
      Files.writeString(file.toPath, sanitized, StandardCharsets.UTF_8)
    }
  }

  val targetDir =
    if (args.nonEmpty) args(0)
    else new File(".").getCanonicalPath

  @nowarn("cat=deprecation")
  val stage = new ChiselStage
  val h64DetailedTrace =
    booleanSetting(
      "gemmont.h64.detailedTrace",
      "H64_DETAILED_TRACE",
      default = false
    )
  val config = GemmontConfig(
    frontend = FrontendConfig(
      h64 = H64CorrectorConfig(
        detailedTrace = h64DetailedTrace
      )
    )
  )
  println(s"Generating Gemmont with H64 detailedTrace=$h64DetailedTrace")
  stage.emitSystemVerilog(
    new ChiplabCoreTop(config),
    Array("--target-dir", targetDir, "--source-root", targetDir)
  )

  val generatedTop = new File(targetDir, "core_top.sv")
  inlineBlackBoxSources(new File(targetDir), generatedTop)

  val coreTop = new File(targetDir, "core_top.v")
  Files.move(
    generatedTop.toPath,
    coreTop.toPath,
    StandardCopyOption.REPLACE_EXISTING
  )
  guardChiselAssertionsForSynthesis(coreTop)
  sanitizeGeneratedSourcePaths(coreTop, new File(targetDir))
}
