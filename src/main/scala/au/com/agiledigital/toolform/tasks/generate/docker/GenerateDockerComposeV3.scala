package au.com.agiledigital.toolform.tasks.generate.docker

import java.io.{BufferedWriter, FileWriter}

import au.com.agiledigital.toolform.app.{ToolFormConfiguration, ToolFormError}
import au.com.agiledigital.toolform.model._
import au.com.agiledigital.toolform.tasks.generate.docker.DockerFormatting._
import au.com.agiledigital.toolform.tasks.generate.docker.GenerateDockerComposeV3.{writeComponents, writeEdges, writeResources}
import au.com.agiledigital.toolform.tasks.generate.docker.SubEdgeDef.subEdgeDefsFromProject
import au.com.agiledigital.toolform.tasks.generate.{WriterContext, YamlWriter}
import au.com.agiledigital.toolform.util.DateUtil
import au.com.agiledigital.toolform.version.BuildInfo

import scalaz.Scalaz._
import scalaz._

/**
  * Takes an abstract project definition and outputs it to a file in the Docker Compose V3 YAML format.
  *
  * @see https://docs.docker.com/compose/compose-file/
  */
class GenerateDockerComposeV3() extends YamlWriter {

  /**
    * The main entry point into the Docker Compose file generation.
    *
    * @param toolFormConfiguration  a configuration object that is parsed from command line options.
    * @param project                the abstract project definition parsed by ToolFormApp.
    * @return                       on success it returns a status message to print to the screen, otherwise it will return an
    *                               error object describing what went wrong.
    */
  def runDockerComposeV3(toolFormConfiguration: ToolFormConfiguration, project: Project): Either[ToolFormError, String] = {
    val outFile = toolFormConfiguration.generateTaskConfiguration.out
    val sourceFilePath = toolFormConfiguration.in.getAbsolutePath
    val writer = new BufferedWriter(new FileWriter(outFile, false))
    try {

      val writeFile = for {
        _ <- write(s"# Generated by ${BuildInfo.name} (${BuildInfo.version})")
        _ <- write(s"# Source file: $sourceFilePath")
        _ <- write(s"# Date: ${DateUtil.formattedDateString}")
        _ <- write("version: '3'")
        _ <- write("services:")
        _ <- indented {
          for {
            _ <- writeEdges(project.id, subEdgeDefsFromProject(project).toList)
            _ <- writeComponents(project.id, project.sortedComponents.values.toList)
            _ <- writeResources(project.sortedResources.values.toList)
          } yield ()
        }
      } yield ()

      val context = WriterContext(writer)
      writeFile.eval(context)

      Right("Completed Successfully")
    } finally {
      writer.close()
    }
  }
}

/**
  * A collection of pure functions used by the GenerateDockerComposeV3 class to generate the Docker Compose file.
  */
object GenerateDockerComposeV3 extends YamlWriter {

  // TODO: Surely Scalaz has a built in identity function that could replace this.
  // Maybe it could be replaced with a different construct altogether?
  val identity: State[WriterContext, Unit] = State[WriterContext, Unit] { context =>
    (context, ())
  }

  def writeEdges(projectId: String, subEdgeDefs: List[SubEdgeDef]): State[WriterContext, Unit] = State[WriterContext, Unit] { context =>
    (subEdgeDefs
       .traverseU(subEdge => writeSubEdge(projectId, subEdge))
       .exec(context),
     ())
  }

  def writeComponents(projectId: String, components: List[Component]): State[WriterContext, Unit] = State[WriterContext, Unit] { context =>
    (components
       .traverseU(component => writeComponent(projectId, component))
       .exec(context),
     ())
  }

  def writeResources(resources: List[Resource]): State[WriterContext, Unit] = State[WriterContext, Unit] { context =>
    (resources
       .traverseU(resource => writeResource(resource))
       .exec(context),
     ())
  }

  def writeSubEdge(projectId: String, subEdgeDef: SubEdgeDef): IndexedState[Unit] = {
    val serviceName = subEdgeServiceName(projectId, subEdgeDef)
    val imageName = subEdgeImageName(projectId, subEdgeDef)
    for {
      _ <- write(s"$serviceName:")
      _ <- indented {
        for {
          _ <- write(s"image: $imageName")
          _ <- write(s"restart: always")
          _ <- write(s"ports:")
          _ <- write(subEdgePortDefinition(subEdgeDef))
        } yield ()
      }
    } yield ()
  }

  def writeComponent(projectId: String, component: Component): IndexedState[Unit] = {
    val serviceName = componentServiceName(component)
    val imageName = componentImageName(projectId, component)
    for {
      _ <- write(s"$serviceName:")
      _ <- indented {
        for {
          _ <- write(s"image: $imageName")
          _ <- write(s"restart: always")
          _ <- writeComponentLabels(component)
          _ <- writeEnvironmentVariables(component)
          _ <- writePorts(component)
        } yield ()
      }
    } yield ()
  }

  def writeComponentLabels(component: Component): IndexedState[Unit] =
    for {
      _ <- write("labels:")
      _ <- indented {
        for {
          _ <- write(s"source.path: \042${component.path}\042")
          _ <- write("project.artefact: \"true\"")
        } yield ()
      }
    } yield ()

  def writeResource(resource: Resource): IndexedState[Unit] = {
    val serviceName = resourceServiceName(resource)
    val imageName = resourceImageName(resource)
    for {
      _ <- write(s"$serviceName:")
      _ <- indented {
        for {
          _ <- write(s"image: $imageName")
          _ <- write(s"restart: always")
          _ <- writeEnvironmentVariables(resource)
          _ <- writePorts(resource)
        } yield ()
      }
    } yield ()
  }

  def writeEnvironmentVariables(service: Service): State[WriterContext, Unit] =
    if (service.environment.nonEmpty) {
      for {
        _ <- write("environment:")
        _ <- State[WriterContext, Unit] { context =>
          {
            (service.environment.toList
               .map((entry) => formatEnvironment(entry))
               .traverseU(write)
               .exec(context),
             ())
          }
        }
      } yield ()
    } else {
      identity
    }

  def writePorts(service: Service): State[WriterContext, Unit] =
    if (service.exposedPorts.nonEmpty) {
      for {
        _ <- write("ports:")
        _ <- State[WriterContext, Unit] { context =>
          {
            (service.exposedPorts
               .map((port) => formatPort(port))
               .traverseU(write)
               .exec(context),
             ())
          }
        }
      } yield ()
    } else {
      identity
    }
}
