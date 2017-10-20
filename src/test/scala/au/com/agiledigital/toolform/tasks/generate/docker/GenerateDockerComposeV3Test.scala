package au.com.agiledigital.toolform.tasks.generate.docker

import java.io.{File, StringWriter}

import au.com.agiledigital.toolform.app.ToolFormApp
import au.com.agiledigital.toolform.model.{Component, Resource, Service}
import au.com.agiledigital.toolform.tasks.generate.WriterContext
import au.com.agiledigital.toolform.tasks.generate.docker.GenerateDockerComposeV3._
import org.scalatest.{FlatSpec, Matchers, PrivateMethodTester}
import scala.compat.Platform.EOL

import scala.io.Source

class GenerateDockerComposeV3Test extends FlatSpec with Matchers with PrivateMethodTester {

  private val rootTestFolder: File = pathToFile("/testprojects")

  private def pathToFile(pathToFile: String): File = {
    val url = getClass.getResource(pathToFile)
    val file = new File(url.toURI)
    file
  }

  /**
    * Strips out all the lines starting with a hash and returns them as joined string.
    * This is used because the commented lines change with the current date/app version etc. and are not stable.
    * @param file the file to read from.
    * @return a string representing the specified file with the commented lines removed.
    */
  private def readFileIgnoringComments(file: File) =
    Source
      .fromFile(file.getAbsolutePath)
      .getLines()
      .filterNot(line => line.startsWith("#"))
      .mkString(EOL)

  private val testFolders = rootTestFolder
    .listFiles()
    .filter(_.isDirectory)

  for (folder <- testFolders) {
    "runDockerComposeV3" should s"generate valid Docker Compose v3 files for scenario: ${folder.getName}" in {
      val inputFile = new File(s"${folder.getAbsolutePath}/environment.conf")
      val expectedFile = new File(s"${folder.getAbsolutePath}/expected.yaml")
      val outputFile = File.createTempFile(getClass.getName, ".yaml")
      outputFile.deleteOnExit()
      ToolFormApp.execute(List("generate", "-i", inputFile.getAbsolutePath, "-o", outputFile.getAbsolutePath).toArray) match {
        case Left(error)    => println(s"runDockerComposeV3 --> Error: ${error.message}")
        case Right(message) => println(s"runDockerComposeV3 --> Output: $message")
      }
      val actual = readFileIgnoringComments(outputFile)
      val expected = readFileIgnoringComments(expectedFile)
      actual should equal(expected)
    }
  }

  "writePorts" should "write ports if exposedPorts is defined" in {
    val testService = new Service {
      def environment = Map()
      def exposedPorts = List("80:80", "443:443", "anything", "9999999")
    }
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writePorts(testService).run(testContext).value

    print(testWriter.toString)

    testWriter.toString should equal(
      s"""ports:
         |- "80:80"
         |- "443:443"
         |- "anything"
         |- "9999999"
         |""".stripMargin
    )
  }

  "writePorts" should "not write anything if empty list of ports defined" in {
    val testService = new Service {
      def environment = Map()
      def exposedPorts = List()
    }
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writePorts(testService).run(testContext).value

    testWriter.toString should equal("")
  }

  "writeEnvironmentVariables" should "write environment variables if environment is defined" in {
    val testService = new Service {
      def environment =
        Map(
          "ABC" -> "DEF",
          "123" -> "345"
        )
      def exposedPorts = List()
    }
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writeEnvironmentVariables(testService).run(testContext).value

    print(testWriter.toString)

    testWriter.toString should equal(
      s"""environment:
         |- ABC=DEF
         |- 123=345
         |""".stripMargin
    )
  }

  "writeEnvironmentVariables" should "not write anything if empty map of environment variables defined" in {
    val testService = new Service {
      def environment = Map()
      def exposedPorts = List()
    }
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writeEnvironmentVariables(testService).run(testContext).value

    testWriter.toString should equal("")
  }

  "writeEdges" should "not write anything if an empty list of edges is provided" in {
    val testEdges = List[SubEdgeDef]()
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writeEdges("", testEdges).run(testContext).value

    testWriter.toString should equal("")
  }

  "writeResources" should "not write anything if an empty list of resources is provided" in {
    val testResources = List[Resource]()
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writeResources(testResources).run(testContext).value

    testWriter.toString should equal("")
  }

  "writeComponents" should "not write anything if an empty list of components is provided" in {
    val testComponents = List[Component]()
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writeComponents("", testComponents).run(testContext).value

    testWriter.toString should equal("")
  }
}
