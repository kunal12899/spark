/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import scala.annotation.tailrec

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

//noinspection ScalaStyle
object CirclePlugin extends AutoPlugin {
  lazy val Circle = config("circle").extend(Test)

  case class ProjectTests(project: ProjectRef, tests: Seq[TestDefinition])

  val circleTestsByProject = taskKey[Option[Seq[ProjectTests]]]("The tests that should be run under this circle node, if circle is set up")
  val copyTestReportsToCircle: TaskKey[Boolean] = taskKey("Copy the test reports to circle if CIRCLE_TEST_REPORTS is defined")

  override def projectConfigurations: Seq[Configuration] = List(Circle)

  override def requires: Plugins = JvmPlugin

  override def trigger: PluginTrigger = allRequirements

  private[this] lazy val testsByProject = Def.task {
    // Defaults.detectTests is basically the value of Keys.definedTests, but since we're
    // overriding the latter depending on the value of this task, we can't depend on it
    ProjectTests(thisProjectRef.value, Defaults.detectTests.value)
  }

  override def globalSettings: Seq[Def.Setting[_]] = List(
    circleTestsByProject := {
      if (sys.env contains "CIRCLE_NODE_INDEX") {
        val index = sys.env("CIRCLE_NODE_INDEX").toInt
        val total = sys.env("CIRCLE_NODE_TOTAL").toInt
        val byProject: Seq[ProjectTests] = testsByProject.all(ScopeFilter(inAnyProject, inConfigurations(Test))).value

        // need a stable sort of projects
        val sortedProjects = byProject.sortBy(_.project.project).toList

        val totalTests = sortedProjects.iterator.map(_.tests.size).sum
        val from = index * totalTests / total
        val to = (index + 1) * totalTests / total

        // We allow a slice of [from, to) from all tests across all projects (in the order of sortedProjects)
        // We then filter out every other

        @tailrec
        def process(projectsLeft: List[ProjectTests], testsSoFar: Int, acc: List[ProjectTests]): List[ProjectTests] = {
          val from1 = from - testsSoFar
          val to1 = to - testsSoFar
          projectsLeft match {
            case ProjectTests(proj, tests) :: rest =>
              val out = ProjectTests(proj, tests.iterator.zipWithIndex.collect {
                case (td, idx) if idx >= from1 && idx < to1 => td
              }.toList)
              process(rest, testsSoFar + tests.size, out :: acc)
            case _ =>
              acc
          }
        }
        Some(process(sortedProjects, 0, Nil))
      } else {
        None
      }
    }
  )

  override def projectSettings: Seq[Def.Setting[_]] = inConfig(Circle)(Defaults.testSettings) ++ List(
    copyTestReportsToCircle := {
      val log = streams.value.log
      val reportsDir = target.value / "test-reports"
      val circleReports = sys.env.get("CIRCLE_TEST_REPORTS")
      val projectName = thisProjectRef.value.project
      circleReports.filter(_ => reportsDir.exists).exists { circle =>
        IO.copyDirectory(reportsDir, file(circle) / projectName)
        log.info(s"Copied test reports from $projectName to circle.")
        true
      } || {
        log.warn(s"Didn't copy test reports from $projectName to circle " +
          "(either CIRCLE_TEST_REPORTS wasn't set, or there were no reports).")
        false
      }
    },

    definedTests in Circle := {
      val testsByProject = (circleTestsByProject in Global).value
        .getOrElse(sys.error("We are not running in circle."))
      val thisProj = thisProjectRef.value

      testsByProject.collectFirst {
        case ProjectTests(`thisProj`, tests) => tests
      }.getOrElse(sys.error(s"Didn't find any tests for $thisProj in the global circleTestsByProject. " +
        s"Only projects found: ${testsByProject.map(_.project)}"))
    },

    test in Circle := {
      Def.sequential(test in Circle, copyTestReportsToCircle).value
    }
  )
}
