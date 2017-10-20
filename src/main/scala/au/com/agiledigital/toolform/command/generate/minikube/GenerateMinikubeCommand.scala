package au.com.agiledigital.toolform.command.generate.minikube

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Path

import au.com.agiledigital.toolform.app.ToolFormError
import au.com.agiledigital.toolform.command.generate.minikube.DeploymentWriter.writeDeployment
import au.com.agiledigital.toolform.command.generate.minikube.ServiceWriter.writeService
import au.com.agiledigital.toolform.command.generate.{WriterContext, YamlWriter}
import au.com.agiledigital.toolform.model._
import au.com.agiledigital.toolform.plugin.ToolFormCommandPlugin
import au.com.agiledigital.toolform.reader.ProjectReader
import au.com.agiledigital.toolform.util.DateUtil
import au.com.agiledigital.toolform.version.BuildInfo
import cats.implicits._
import com.monovore.decline.Opts

/**
  * Takes an abstract project definition and outputs it a combined service/deployment Kubernetes spec in YAML format.
  *
  * For each service/resource defined in the Toolform config, a single Kubernetes service and deployment is created.
  *
  * @see https://kubernetes.io/docs/api-reference/v1.8/
  */
class GenerateMinikubeCommand() extends ToolFormCommandPlugin with YamlWriter {

  /**
    * The primary class for generating Kubernetes (Minikube) config files.
    */
  def command: Opts[Either[ToolFormError, String]] =
    Opts.subcommand("generate", "generates config files for container orchestration.") {
      Opts.subcommand("minikube", "generates config files for Kubernetes (Minikube) container orchestration.") {
        (Opts.option[Path]("in-file", short = "i", metavar = "file", help = "the path to the project config file") |@|
          Opts.option[Path]("out-file", short = "o", metavar = "file", help = "the path to output the generated file(s)"))
          .map { (inputFilePath: Path, outputFilePath: Path) =>
            val inputFile  = inputFilePath.toFile
            val outputFile = outputFilePath.toFile
            if (!inputFile.exists()) {
              Left(ToolFormError(s"Input file [${inputFile}] does not exist."))
            } else if (!inputFile.isFile) {
              Left(ToolFormError(s"Input file [${inputFile}] is not a valid file."))
            } else if (!outputFile.getParentFile.exists()) {
              Left(ToolFormError(s"Output directory [${outputFile.getParentFile}] does not exist."))
            } else {
              for {
                project <- ProjectReader.readProject(inputFile)
                status  <- runGenerateMinikube(inputFile.getAbsolutePath, outputFile, project)
              } yield status
            }
          }
      }
    }

  /**
    * The main entry point into the Kubernetes (Minikube) file generation.
    *
    * @param sourceFilePath project config input file path
    * @param project        the abstract project definition parsed by ToolFormApp.
    * @return on success it returns a status message to print to the screen, otherwise it will return an
    *         error object describing what went wrong.
    */
  def runGenerateMinikube(sourceFilePath: String, outFile: File, project: Project): Either[ToolFormError, String] = {
    val writer = new BufferedWriter(new FileWriter(outFile, false))
    try {
      val writeFile = for {
        _ <- write(s"# Generated by ${BuildInfo.name} (${BuildInfo.version})")
        _ <- write(s"# Source file: $sourceFilePath")
        _ <- write(s"# Date: ${DateUtil.formattedDateString}")
        _ <- project.components.values.filter(shouldWriteService).toList.traverse_(writeService)
        _ <- project.resources.values.filter(shouldWriteService).toList.traverse_(writeService)
        _ <- project.components.values.toList.traverse_((component) => writeDeployment(project.id, component))
        _ <- project.resources.values.toList.traverse_((resource) => writeDeployment(project.id, resource))
      } yield ()

      val context = WriterContext(writer)
      // Final state needs to be read for anything to happen because of lazy evaluation
      val _ = writeFile.run(context).value

      Right("Completed Successfully")
    } finally {
      writer.close()
    }
  }

  private def shouldWriteService(toolFormService: ToolFormService): Boolean =
    toolFormService.exposedPorts.nonEmpty || toolFormService.externalPorts.nonEmpty
}
