package au.com.agiledigital.toolform.command.generate.docker

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Path

import au.com.agiledigital.toolform.app.ToolFormError
import au.com.agiledigital.toolform.command.generate.Formatting._
import au.com.agiledigital.toolform.command.generate.docker.DockerFormatting._
import au.com.agiledigital.toolform.command.generate.docker.GenerateDockerComposeV3Command.runGenerateDockerComposeV3
import au.com.agiledigital.toolform.command.generate.{WriterContext, YamlWriter}
import au.com.agiledigital.toolform.model._
import au.com.agiledigital.toolform.plugin.ToolFormGenerateCommandPlugin
import au.com.agiledigital.toolform.reader.ProjectReader
import au.com.agiledigital.toolform.util.DateUtil
import au.com.agiledigital.toolform.version.BuildInfo
import cats.data.Validated.{invalid, valid}
// import cats.syntax.traverse._
import cats.data.NonEmptyList
import cats.implicits._
import com.monovore.decline.Opts

/**
  * Takes an abstract project definition and outputs it to a file in the Docker Compose V3 YAML format.
  *
  * @see https://docs.docker.com/compose/compose-file/
  */
class GenerateDockerComposeV3Command extends ToolFormGenerateCommandPlugin {

  def command: Opts[Either[NonEmptyList[ToolFormError], String]] =
    Opts.subcommand("dockercompose", "generates config files for container orchestration") {
      (Opts.option[Path]("in-file", short = "i", metavar = "file", help = "the path to the project config file") |@|
        Opts.option[Path]("out-file", short = "o", metavar = "file", help = "the path to output the generated file(s)"))
        .map(execute)
    }

  def execute(inputFilePath: Path, outputFilePath: Path): Either[NonEmptyList[ToolFormError], String] = {
    val inputFile  = inputFilePath.toFile
    val outputFile = outputFilePath.toFile.getAbsoluteFile
    val parentFile = Option(outputFile.getParentFile)
    if (!inputFile.exists()) {
      Left(NonEmptyList.of(ToolFormError(s"Input file [${inputFile}] does not exist.")))
    } else if (!inputFile.isFile) {
      Left(NonEmptyList.of(ToolFormError(s"Input file [${inputFile}] is not a valid file.")))
    } else if (!parentFile.exists(_.exists())) {
      Left(NonEmptyList.of(ToolFormError(s"Output directory [${outputFile.getParentFile}] does not exist.")))
    } else {
      for {
        project <- ProjectReader.readProject(inputFile)
        status  <- runGenerateDockerComposeV3(inputFile.getAbsolutePath, outputFile, project)
      } yield status
    }
  }
}

object GenerateDockerComposeV3Command extends YamlWriter {

  /**
    * The main entry point into the Docker Compose file generation.
    *
    * @param sourceFilePath project config input file path
    * @param project                the abstract project definition parsed by ToolFormApp.
    * @return                       on success it returns a status message to print to the screen, otherwise it will return an
    *                               error object describing what went wrong.
    */
  def runGenerateDockerComposeV3(sourceFilePath: String, outFile: File, project: Project): Either[NonEmptyList[ToolFormError], String] = {
    // validated image names
    val imageNameErrors = project.sortedResources.values.toList.traverseU { resource =>
      if (resource.image == None)
        invalid(NonEmptyList.of(ToolFormError(s"Image name is required for: ${resource.id}")))
      else
        valid(resource.id)
    }

    imageNameErrors.toEither match {
      case Left(error) => Left(error)
      case Right(_) => {
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
                    _ <- writeComponents(project.id, project.sortedComponents.values.toList)
                    _ <- writeResources(project.sortedResources.values.toList)
                  } yield ()
                }
          } yield ()

          val context = WriterContext(writer)
          // Final state needs to be read for anything to happen because of lazy evaluation
          val _ = writeFile.run(context).value

          Right(s"Wrote configuration to [$outFile].\nRun with `docker-compose up -f '$outFile'`")
        } finally {
          writer.close()
        }
      }
    }
  }

  def writeComponents(projectId: String, components: List[Component]): Result[Unit] =
    components.traverse_((component) => writeComponent(projectId, component))

  def writeResources(resources: List[Resource]): Result[Unit] =
    resources.traverse_(writeResource)

  def writeComponent(projectId: String, component: Component): Result[Unit] = {
    val serviceName = componentServiceName(component)
    val imageName   = componentImageName(projectId, component)
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

  def writeComponentLabels(component: Component): Result[Unit] =
    for {
      _ <- write("labels:")
      _ <- indented {
            for {
              _ <- write(s"source.path: \042${component.path}\042")
              _ <- write("project.artefact: \"true\"")
            } yield ()
          }
    } yield ()

  def writeResource(resource: Resource): Result[Unit] = {
    val imageName = resource.image.getOrElse(Nil)

    for {
      _ <- write(s"${resource.id}:")
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

  def writeEnvironmentVariables(service: ToolFormService): Result[Unit] =
    if (service.environment.nonEmpty) {
      for {
        _ <- write("environment:")
        _ <- service.environment.toList
              .map((entry) => formatEnvironment(entry))
              .traverse_(write)
      } yield ()
    } else {
      identity
    }

  def writePorts(service: ToolFormService): Result[Unit] =
    if (service.externalPorts.nonEmpty) {
      for {
        _ <- write("ports:")
        _ <- service.externalPorts
              .map((port) => formatPort(port))
              .traverse_(write)
      } yield ()
    } else {
      identity
    }
}
