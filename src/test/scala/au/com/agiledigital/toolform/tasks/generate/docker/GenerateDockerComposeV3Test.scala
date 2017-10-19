package au.com.agiledigital.toolform.tasks.generate.docker

import java.io.{File, StringWriter}

import au.com.agiledigital.toolform.app.ToolFormApp
import au.com.agiledigital.toolform.model.{Component, Resource, Service}
import au.com.agiledigital.toolform.tasks.generate.WriterContext
import au.com.agiledigital.toolform.tasks.generate.docker.GenerateDockerComposeV3._
import au.com.agiledigital.toolform.util.DateUtil
import au.com.agiledigital.toolform.version.BuildInfo
import org.scalatest.{FlatSpec, Matchers, PrivateMethodTester}

import scala.io.Source

class GenerateDockerComposeV3Test extends FlatSpec with Matchers with PrivateMethodTester {

  val testFile: File = pathToFile("/test_project/environment.conf")

  def pathToFile(pathToFile: String): File = {
    val url = getClass.getResource(pathToFile)
    val file = new File(url.toURI)
    file
  }

  "runDockerComposeV3" should "generate a valid Docker Compose v3 file" in {
    val outputFile = File.createTempFile(getClass.getName, ".yaml")
    outputFile.deleteOnExit()
    ToolFormApp.execute(List("generate", "-i", testFile.getAbsolutePath, "-o", outputFile.getAbsolutePath).toArray)
    val result = Source.fromFile(outputFile.getAbsolutePath).mkString
    result should equal(s"""# Generated by ${BuildInfo.name} (${BuildInfo.version})
                           |# Source file: ${testFile.getAbsolutePath}
                           |# Date: ${DateUtil.formattedDateString}
                           |version: '3'
                           |services:
                           |  seswipmainpublicapinginx:
                           |    image: se_swip_main_public_api_nginx
                           |    restart: always
                           |    ports:
                           |    - "80:80"
                           |  clientpublic:
                           |    image: se_swip/client/public
                           |    restart: always
                           |    labels:
                           |      source.path: "client/public"
                           |      project.artefact: "true"
                           |  publicapi:
                           |    image: se_swip/public_api
                           |    restart: always
                           |    labels:
                           |      source.path: "server/public"
                           |      project.artefact: "true"
                           |  seswipelasticsearch:
                           |    image: se_swip/se_swip_elastic_search
                           |    restart: always
                           |    labels:
                           |      source.path: "resources/elastic_search"
                           |      project.artefact: "true"
                           |  seswipinfluxdb:
                           |    image: se_swip/se_swip_influx_db
                           |    restart: always
                           |    labels:
                           |      source.path: "resources/influxdb"
                           |      project.artefact: "true"
                           |  seswipcarbon:
                           |    image: dockerana/carbon
                           |    restart: always
                           |  seswipdb:
                           |    image: docker.agiledigital.com.au:5000/agile/pgpool2
                           |    restart: always
                           |    environment:
                           |    - BACKEND_PORT=5432
                           |    - BACKEND_HOST=localhost
                           |  seswipmailrelay:
                           |    image: djfarrelly/maildev
                           |    restart: always
                           |""".stripMargin)
  }

  "writePorts" should "write ports if exposedPorts is defined" in {
    val testService = new Service {
      def environment = Map()
      def exposedPorts = List("80:80", "443:443", "anything", "9999999")
    }
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writePorts(testService).exec(testContext)

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

    writePorts(testService).exec(testContext)

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

    writeEnvironmentVariables(testService).exec(testContext)

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

    writeEnvironmentVariables(testService).exec(testContext)

    testWriter.toString should equal("")
  }

  "writeEdges" should "not write anything if an empty list of edges is provided" in {
    val testEdges = List[SubEdgeDef]()
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writeEdges("", testEdges).exec(testContext)

    testWriter.toString should equal("")
  }

  "writeResources" should "not write anything if an empty list of resources is provided" in {
    val testResources = List[Resource]()
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writeResources(testResources).exec(testContext)

    testWriter.toString should equal("")
  }

  "writeComponents" should "not write anything if an empty list of components is provided" in {
    val testComponents = List[Component]()
    val testWriter = new StringWriter()
    val testContext = WriterContext(testWriter)

    writeComponents("", testComponents).exec(testContext)

    testWriter.toString should equal("")
  }
}
