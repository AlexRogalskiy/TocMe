import com.github.blueboxware.tocme.TocMePlugin
import com.vladsch.flexmark.ast.Document
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Copyright 2018 Blue Box Ware
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
internal class ProjectFixture {

  private val tempDir = TemporaryFolder().apply { create() }
  private val buildFile = tempDir.newFile("build.gradle")

  val project: Project = ProjectBuilder.builder().withProjectDir(tempDir.root).build()

  val backupDir = "build/tocme/backups/${TocMePlugin.INSERT_TOCS_TASK}/"

  private val GRADLE_VERSION = GradleVersion.current()

  private var latestBuildResult: BuildResult? = null
  private var latestTask: String? = null

  private val testDataDir = File("src/test/resources")

  private val header = """

        plugins {
          id 'com.github.blueboxware.tocme' version '${getCurrentPluginVersion()}'
        }

  """.trimIndent()

  fun destroy() {
    tempDir.delete()
  }

  fun addFile(fileName: String) {
    project.copy {
      it.from(testDataDir.absolutePath) {
        it.include(fileName)
      }
      it.into(tempDir.root)
    }
  }

  fun createFile(fileName: String, contents: String) =
          File(project.rootDir, fileName).apply {
            writeText(contents)
          }

  fun buildFile(contents: String) {
    buildFile.writeText(header + contents)
  }

  fun replaceInBuildFile(old: String, new: String) = buildFile.writeText(buildFile.readText().replace(old, new))

  fun file(path: String) = File(tempDir.root, path)

  fun buildCheck(vararg extraArguments: String) = build(TocMePlugin.CHECK_TOCS_TASK, *extraArguments)

  fun build(taskName: String? = TocMePlugin.INSERT_TOCS_TASK, vararg extraArguments: String): BuildResult {

    val args = extraArguments.toMutableList()
    taskName?.let { args.add(taskName) }

    latestTask = taskName

    val runner = GradleRunner
            .create()
            .withPluginClasspath()
//            .withDebug(true)
            .withProjectDir(tempDir.root)
            .withGradleVersion(GRADLE_VERSION.version)
            .withArguments(*args.toTypedArray())

    val result = runner.build()

    latestBuildResult = result

    return result

  }

  // https://github.com/gradle/gradle/issues/1079
  fun sleepIfNecessary() {
    if (GRADLE_VERSION < GradleVersion.version("3.5")) {
      Thread.sleep(2000)
    }
  }

  fun assertFileEquals(
          expectedFileName: String,
          actualFileName: String,
          showDiff: Boolean = true,
          showActual: Boolean = true,
          showExpected: Boolean = false,
          createExpectedFileIfNotFound: Boolean = true
  ) =
          assertFileEquals(
                  File(testDataDir, expectedFileName),
                  File(tempDir.root, actualFileName),
                  showDiff,
                  showActual,
                  showExpected,
                  createExpectedFileIfNotFound = createExpectedFileIfNotFound
          )

  fun assertFilesExist(vararg fileNames: String) =
          fileNames.forEach {
            assertTrue("File '$it' doesn't exist") { File(tempDir.root, it).exists() }
          }

  fun assertOutputContains(str: String) =
          latestBuildResult?.output?.let { output ->
            assertTrue(output.contains(str), "String '$str' not found in output. Output:\n" + output)
          }

  fun assertOutputContainsNot(str: String) =
          latestBuildResult?.output?.let { output ->
            assertFalse(output.contains(str), "String '$str' found in output. Output:\n" + output)
          }

  fun assertOutputContainsNot(regex: Regex) =
          latestBuildResult?.output?.let { output ->
            assertNull(regex.find(output))
          }

  fun assertCheckOutputFileIsOutOfDate(fileName: String) =
          assertOutputContains("$fileName: ${TocMePlugin.OUT_OF_DATE_MSG}")

  fun assertCheckOutputFileIsNotOutOfDate(fileName: String) =
          assertOutputContainsNot("$fileName: ${TocMePlugin.OUT_OF_DATE_MSG}")

  fun assertCheckOutputNothingIsOutOfDate() =
          assertOutputContainsNot(TocMePlugin.OUT_OF_DATE_MSG)

  fun assertBuildSuccess(task: String? = latestTask) =
          assertTaskOutcome(TaskOutcome.SUCCESS, task)

  @Suppress("unused")
  fun assertBuildFailure(task: String? = latestTask) =
          assertTaskOutcome(TaskOutcome.FAILED, task)

  fun assertBuildUpToDate(task: String? = latestTask) =
          assertTaskOutcome(TaskOutcome.UP_TO_DATE, task)

  fun assertBuildNoSource(task: String? = latestTask) =
          assertTaskOutcome(TaskOutcome.NO_SOURCE, task)

  fun assertBuildNoSourceAfter33(task: String? = latestTask) =
          if (GRADLE_VERSION < GradleVersion.version("3.4"))
            assertBuildUpToDate(task)
          else
            assertBuildNoSource()

  private fun assertTaskOutcome(expectedOutcome: TaskOutcome, task: String? = latestTask) =
          task?.let { actualTask ->
            latestBuildResult?.let { actualResult ->
              actualResult.task(":" + actualTask.removePrefix(":"))?.let {
                assertEquals(expectedOutcome, it.outcome, "Expected task outcome: $expectedOutcome, actual outcome: ${it.outcome}")
              }
            }
          } ?: throw AssertionError()

  companion object {

    private val CURRENT_VERSION_REGEX = Regex("""pluginVersion\s*=\s*'([^']+)'""")

    internal fun getCurrentPluginVersion() =
            CURRENT_VERSION_REGEX.find(File("versions.gradle").readText())?.groupValues?.getOrNull(1)
                    ?: throw AssertionError()

    internal fun assertTextEquals(
            expectedText: String,
            actualText: String,
            showDiff: Boolean = true,
            showActual: Boolean = true,
            showExpected: Boolean = true,
            document: Document? = null
    ) {
      if (expectedText != actualText) {
        val expectedFile = createTempFile().apply { writeText(expectedText) }
        val actualFile = createTempFile().apply { writeText(actualText) }
        assertFileEquals(
                expectedFile,
                actualFile,
                showDiff,
                showActual,
                showExpected,
                showFileNames = false,
                createExpectedFileIfNotFound = false,
                document = document
        )
      }
    }

    internal fun assertFileEquals(
            expectedFile: File,
            actualFile: File,
            showDiff: Boolean = true,
            showActual: Boolean = true,
            showExpected: Boolean = false,
            showFileNames: Boolean = true,
            createExpectedFileIfNotFound: Boolean = true,
            document: Document? = null
    ) {

      if (!actualFile.exists()) {
        throw AssertionError("Actual file '${actualFile.absolutePath}' does not exist")
      }

      if (!expectedFile.exists()) {
        if (createExpectedFileIfNotFound) {
          expectedFile.writeText(actualFile.readText())
        }
        throw AssertionError(
                "Expected file '${expectedFile.absolutePath}' does not exist." +
                        if (createExpectedFileIfNotFound) " Creating." else "")
      }

      var diffCmd: Process? = null
      var diffCmdErr: String? = null

      if (showDiff) {
        diffCmd = try {
          Runtime.getRuntime().exec(arrayOf("diff", "-d", expectedFile.absolutePath, actualFile.absolutePath))
        } catch (e: Exception) {
          diffCmdErr = e.message + "\n"
          null
        }
      }

      if (diffCmd?.waitFor() == 1 || expectedFile.readText() != actualFile.readText()) {

        var msg = if (showFileNames)
          "Actual file '${actualFile.name}' differs from expected file '${expectedFile.name}':\n\n"
        else
          "Actual text differs from expected text:\n\n"

        var footer = false
        if (showDiff) {
          msg += "=== DIFF =====================================================================================\n"
          msg += diffCmd?.inputStream?.reader()?.readText() ?: diffCmdErr
          msg += "\n"
          footer = true
        }
        if (showActual) {
          msg += "=== ACTUAL ===================================================================================\n"
          msg += actualFile.readText()
          msg += "\n\n"
          footer = true
        }
        if (showExpected) {
          msg += "=== EXPECTED =================================================================================\n"
          msg += expectedFile.readText()
          msg += "\n\n"
          footer = true
        }
        if (footer) {
          msg += "==============================================================================================\n"
        }

        document?.let { doc ->
          doc.children.forEach {
            println(it.toAstString(true) + ": '" + it.chars.replace("\n", "\\n") + "'")
          }
        }

        throw AssertionError(msg)

      }

    }

  }

}