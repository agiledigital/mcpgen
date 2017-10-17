package au.com.agiledigital.toolform.tasks

import java.io.{BufferedWriter, File, FileWriter}

import au.com.agiledigital.toolform.app.{ToolFormConfiguration, ToolFormError}
import au.com.agiledigital.toolform.model.{Component, Project, Resource}
import au.com.agiledigital.toolform.tasks.GenerateTaskOutputType.GenerateTaskOutputType
import au.com.agiledigital.toolform.version.BuildInfo

import scala.compat.Platform.EOL

class GenerateTask() extends Task {

  private val indentSize = 2

  override def run(toolFormConfiguration: ToolFormConfiguration, project: Project): Either[ToolFormError, String] =
    toolFormConfiguration.generateTaskConfiguration.generateTaskOutputType match {
      case GenerateTaskOutputType.DockerComposeV3 => runDockerComposeV3(toolFormConfiguration, project)
      case other                                  => Left(ToolFormError(s"Output type [$other] not supported at this time"))
    }

  def runDockerComposeV3(toolFormConfiguration: ToolFormConfiguration, project: Project): Either[ToolFormError, String] = {
    val outFile = toolFormConfiguration.generateTaskConfiguration.out
    if (outFile.isDirectory) {
      return Left(ToolFormError("Output path is a directory. Docker Compose V3 output requires a single file as an output."))
    }

    // Utility Functions

    def write(text: String)(context: WriterContext): WriterContext = {
      val indentRange = 0 until context.indentLevel * this.indentSize
      indentRange.foreach(_ => context.writer.write(" "))
      context.writer.write(text)
      context.writer.write(EOL)
      context
    }

    // Name Formatting

    def normaliseName(name: String): String =
      name
        .replace(' ', '_')
        .replace('-', '_')
        .toLowerCase

    def componentName(component: Component): String =
      normaliseName(s"${project.id}/${component.id}")

    def resourceName(resource: Resource): String =
      normaliseName(s"${project.id}/${resource.id}")

    // Indentation

    def indent(context: WriterContext): WriterContext = context.copy(indentLevel = context.indentLevel + 1)

    def outdent(context: WriterContext): WriterContext = context.copy(indentLevel = context.indentLevel - 1)

    def resetIndent(context: WriterContext): WriterContext = context.copy(indentLevel = 0)

    // Preamble

    def writeHeader: (WriterContext) => WriterContext =
      write(s"# Generated by ${BuildInfo.name} (${BuildInfo.version})")

    def writeSpecVersion = write("version: '3'") _

    def finish = resetIndent _

    // Services

    def beginServicesBlock = (indent _).andThen(write("services:"))

    // Components

    def beginComponentBlock(component: Component) =
      (indent _)
        .andThen(write(s"${component.name}:"))
        .andThen(indent)

    def writeComponentBody(component: Component) = {
      val imageName = componentName(component)
      (write(s"image: $imageName") _)
        .andThen(write(s"restart: always"))
    }

    def endComponentBlock(component: Component) =
      (outdent _)
        .andThen(outdent)

    // Resources

    def beginResourceBlock(resource: Resource) = {
      val resourceKey = normaliseName(resource.id)
      (indent _)
        .andThen(write(s"$resourceKey:"))
        .andThen(indent)
    }

    def writeResourceBody(resource: Resource) = {
      val imageName = normaliseName(resourceName(resource))
      (write(s"image: $imageName") _)
        .andThen(write(s"restart: always"))
    }

    def enResourceBlock(resource: Resource) =
      (outdent _)
        .andThen(outdent)

    // Sections

    def writePreamble =
      writeHeader
        .andThen(writeSpecVersion)
        .andThen(beginServicesBlock)

    def writeComponent(component: Component) =
      beginComponentBlock(component)
        .andThen(writeComponentBody(component))
        .andThen(endComponentBlock(component))

    def writeResource(resource: Resource) =
      beginResourceBlock(resource)
        .andThen(writeResourceBody(resource))
        .andThen(enResourceBlock(resource))

    def writeComponents =
      project.components.values
        .foldLeft((a: WriterContext) => a)((prev, component) => prev.andThen(writeComponent(component)))

    def writeResources =
      project.resources.values
        .foldLeft((a: WriterContext) => a)((prev, component) => prev.andThen(writeResource(component)))

    // Logic

    val writer = new BufferedWriter(new FileWriter(outFile, false))
    try {

      val writeFile = writePreamble
        .andThen(writeComponents)
        .andThen(writeResources)
        .andThen(finish)

      val context = WriterContext(writer)
      writeFile(context)

      Right("Completed Successfully")
    } finally {
      writer.close()
    }
  }
}

/**
  * The configuration for the generate task.
  *
  * @param out                    The path to output the result of this task. For Docker Compose V3 this will be a single file.
  *                               For Kubernetes this will be a folder.
  * @param generateTaskOutputType The format of the file generated by the "Generate" task.
  */
final case class GenerateTaskConfiguration(out: File = new File("."), generateTaskOutputType: GenerateTaskOutputType = GenerateTaskOutputType.DockerComposeV3)

/**
  * An enumeration representing all the modes this tool can function in.
  */
object GenerateTaskOutputType extends Enumeration {
  type GenerateTaskOutputType = Value

  val DockerComposeV3, Kubernetes = Value
}

/**
  * An object that is used provide context to the generator while it is writing.
  *
  * @param writer      The object used to do the actual writing to the file.
  * @param indentLevel The number of indent levels deep the current context is.
  */
final case class WriterContext(writer: BufferedWriter, indentLevel: Int = 0)
