package au.com.agiledigital.toolform.tasks

import java.io.{BufferedWriter, File, FileWriter}

import au.com.agiledigital.toolform.app.{ToolFormConfiguration, ToolFormError}
import au.com.agiledigital.toolform.model.{Service, _}
import au.com.agiledigital.toolform.version.BuildInfo
import enumeratum.{Enum, EnumEntry}

import scala.compat.Platform.EOL
import scalaz.Scalaz._
import scalaz._

class GenerateTask() extends Task {

  private val indentSize = 2
  type Result[A] = State[WriterContext, A]
  private val identity = State[WriterContext, Unit] { context =>
    (context, ())
  }

  override def run(toolFormConfiguration: ToolFormConfiguration, project: Project): Either[ToolFormError, String] =
    toolFormConfiguration.generateTaskConfiguration.generateTaskOutputType match {
      case GenerateTaskOutputType.`dockerComposeV3` => runDockerComposeV3(toolFormConfiguration, project)
      case other                                    => Left(ToolFormError(s"Output type [$other] not supported at this time"))

    }

  private def runDockerComposeV3(toolFormConfiguration: ToolFormConfiguration, project: Project): Either[ToolFormError, String] = {
    val outFile = toolFormConfiguration.generateTaskConfiguration.out
    if (outFile.isDirectory) {
      return Left(ToolFormError("Output path is a directory. Docker Compose V3 output requires a single file as an output."))
    }

    // Name Formatting

    def normaliseServiceName(name: String): String =
      name
        .replace("/", "")
        .replace(" ", "")
        .replace("-", "")
        .replace("_", "")
        .toLowerCase

    def normaliseImageName(name: String): String =
      name
        .replace(" ", "_")
        .replace("-", "_")
        .toLowerCase

    def componentServiceName(component: Component): String =
      normaliseServiceName(s"${component.id}")

    def resourceServiceName(resource: Resource): String =
      normaliseServiceName(s"${resource.id}")

    def subEdgeServiceName(subEdgeDef: SubEdgeDef): String =
      normaliseServiceName(s"${project.id}${subEdgeDef.edgeId}${subEdgeDef.subEdgeId}nginx")

    def componentImageName(component: Component): String =
      normaliseImageName(s"${project.id}/${component.id}")

    def resourceImageName(resource: Resource): String =
      normaliseImageName(s"${resource.image}")

    def subEdgeImageName(subEdgeDef: SubEdgeDef): String =
      normaliseImageName(s"${project.id}_${subEdgeDef.edgeId}_${subEdgeDef.subEdgeId}_nginx")

    def subEdgePortDefinition(subEdgeDef: SubEdgeDef): String =
      SubEdgeType.withNameInsensitive(subEdgeDef.subEdge.edgeType) match {
        case SubEdgeType.http  => "- \"80:80\""
        case SubEdgeType.https => "- \"443:443\""
      }

    def formatEnvironment(entry: (String, String)): String = s"- ${entry._1}=${entry._2}"

    def formatPort(port: String): String = s"- \042$port\042"

    // Utility Functions

    def write(text: String): Result[Unit] = State[WriterContext, Unit] { context =>
      {
        val indentRange = 0 until context.indentLevel * this.indentSize
        indentRange.foreach(_ => context.writer.write(" "))
        context.writer.write(text)
        context.writer.write(EOL)
        (context, ())
      }
    }

    // Indentation

    def indent(): Result[Unit] = State[WriterContext, Unit] { context =>
      (context.copy(indentLevel = context.indentLevel + 1), ())
    }

    def outdent(): Result[Unit] = State[WriterContext, Unit] { context =>
      (context.copy(indentLevel = context.indentLevel - 1), ())
    }

    def resetIndent(): Result[Unit] = State[WriterContext, Unit] { context =>
      (context.copy(indentLevel = 0), ())
    }

    // Preamble

    def writeHeader = write(s"# Generated by ${BuildInfo.name} (${BuildInfo.version})")

    def writeSpecVersion = write("version: '3'")

    def finish() =
      for {
        _ <- resetIndent()
      } yield ()

    // Services

    def beginServicesBlock =
      for {
        _ <- indent()
        _ <- write("services:")
      } yield ()

    // Edges

    def beginSubEdgeBlock(subEdgeDef: SubEdgeDef) = {
      val serviceName = subEdgeServiceName(subEdgeDef)
      for {
        _ <- indent()
        _ <- write(s"$serviceName:")
        _ <- indent()
      } yield ()
    }

    def writeSubEdgeBody(subEdgeDef: SubEdgeDef) = {
      val imageName = subEdgeImageName(subEdgeDef)
      for {
        _ <- write(s"image: $imageName")
        _ <- write(s"restart: always")
        _ <- write(s"ports:")
        _ <- write(subEdgePortDefinition(subEdgeDef))
      } yield ()
    }

    def endSubEdgeBlock(subEdgeDef: SubEdgeDef) =
      for {
        _ <- outdent()
        _ <- outdent()
      } yield ()

    // Components

    def beginComponentBlock(component: Component) = {
      val serviceName = componentServiceName(component)
      for {
        _ <- indent()
        _ <- write(s"$serviceName:")
        _ <- indent()
      } yield ()
    }

    def writeComponentBody(component: Component) = {
      val imageName = componentImageName(component)
      for {
        _ <- write(s"image: $imageName")
        _ <- write(s"restart: always")
      } yield ()
    }

    def endComponentBlock(component: Component) =
      for {
        _ <- outdent()
        _ <- outdent()
      } yield ()

    // Labels

    def beginLabelsBlock(component: Component) =
      for {
        _ <- write("labels:")
        _ <- indent()
      } yield ()

    // \042 represents the quote character. See:https://stackoverflow.com/a/39457924/1153203
    def writeLabelsBody(component: Component) =
      for {
        _ <- write(s"source.path: \042${component.path}\042")
        _ <- write("project.artefact: \"true\"")
      } yield ()

    def endLabelsBlock(component: Component) = outdent

    // Resources

    def beginResourceBlock(resource: Resource) = {
      val serviceName = resourceServiceName(resource)
      for {
        _ <- indent()
        _ <- write(s"$serviceName:")
        _ <- indent()
      } yield ()
    }

    def writeResourceBody(resource: Resource) = {
      val imageName = resourceImageName(resource)
      for {
        _ <- write(s"image: $imageName")
        _ <- write(s"restart: always")
      } yield ()
    }

    def endResourceBlock(resource: Resource) =
      for {
        _ <- outdent()
        _ <- outdent()
      } yield ()

    // Environment

    def beginEnvironmentBlock(service: Service) =
      service.environment match {
        case Some(_) => write("environment:")
        case None    => identity
      }

    def writeEnvironmentBody(service: Service) =
      service.environment match {
        case Some(environmentEntries) =>
          State[WriterContext, Unit] { context =>
            {
              (environmentEntries.toList
                 .map((entry) => formatEnvironment(entry))
                 .traverseU(write)
                 .exec(context),
               ())
            }
          }
        case None => identity
      }

    def endEnvironmentBlock(service: Service) = identity

    // Exposed Ports

    def beginExposedPortsBlock(service: Service) =
      service.exposedPorts match {
        case Some(_) => write("ports:")
        case None    => identity
      }

    def writeExposedPortsBlock(service: Service) =
      service.exposedPorts match {
        case Some(ports) =>
          State[WriterContext, Unit] { context =>
            {
              (ports
                 .map((port) => formatPort(port))
                 .traverseU(write)
                 .exec(context),
               ())
            }
          }
        case None => identity
      }

    def endExposedPortsBlock(service: Service) = identity

    // Sections

    def writePreamble() =
      for {
        _ <- writeHeader
        _ <- writeSpecVersion
        _ <- beginServicesBlock
      } yield ()

    def writeComponentLabels(component: Component) =
      for {
        _ <- beginLabelsBlock(component)
        _ <- writeLabelsBody(component)
        _ <- endLabelsBlock(component)
      } yield ()

    def writeSubEdge(subEdgeDef: SubEdgeDef) =
      for {
        _ <- beginSubEdgeBlock(subEdgeDef)
        _ <- writeSubEdgeBody(subEdgeDef)
        _ <- endSubEdgeBlock(subEdgeDef)
      } yield ()

    def writeEnvironmentVariables(service: Service) =
      for {
        _ <- beginEnvironmentBlock(service)
        _ <- writeEnvironmentBody(service)
        _ <- endEnvironmentBlock(service)
      } yield ()

    def writePorts(service: Service) =
      for {
        _ <- beginExposedPortsBlock(service)
        _ <- writeExposedPortsBlock(service)
        _ <- endExposedPortsBlock(service)
      } yield ()

    def writeComponent(component: Component) =
      for {
        _ <- beginComponentBlock(component)
        _ <- writeComponentBody(component)
        _ <- writeComponentLabels(component)
        _ <- writeEnvironmentVariables(component)
        _ <- writePorts(component)
        _ <- endComponentBlock(component)
      } yield ()

    def writeResource(resource: Resource) =
      for {
        _ <- beginResourceBlock(resource)
        _ <- writeResourceBody(resource)
        _ <- writeEnvironmentVariables(resource)
        _ <- writePorts(resource)
        _ <- endResourceBlock(resource)
      } yield ()

    def writeComponents() = State[WriterContext, Unit] { context =>
      (project.sortedComponents.values.toList
         .traverseU(writeComponent)
         .exec(context),
       ())
    }

    def writeResources() = State[WriterContext, Unit] { context =>
      (project.sortedResources.values.toList
         .traverseU(writeResource)
         .exec(context),
       ())
    }

    def writeEdges() = State[WriterContext, Unit] { context =>
      (generateSubEdgeDefs(project).toList
         .traverseU(writeSubEdge)
         .exec(context),
       ())
    }

    // Logic

    val writer = new BufferedWriter(new FileWriter(outFile, false))
    try {

      val writeFile = for {
        _ <- writePreamble()
        _ <- writeEdges()
        _ <- writeComponents()
        _ <- writeResources()
        _ <- finish()
      } yield ()

      val context = WriterContext(writer)
      writeFile.eval(context)

      Right("Completed Successfully")
    } finally {
      writer.close()
    }
  }

  private def generateSubEdgeDefs(project: Project): Iterable[SubEdgeDef] =
    project.topology.sortedEdges.flatMap((edgePair) => {
      val (edgeName, edge) = edgePair
      edge.sortedSubEdges.map((subEdgePair) => {
        val (subEdgeName, subEdge) = subEdgePair
        SubEdgeDef(edgeName, subEdgeName, subEdge)
      })
    })
}

/**
  * The configuration for the generate task.
  *
  * @param out                    The path to output the result of this task. For Docker Compose V3 this will be a single file.
  *                               For Kubernetes this will be a folder.
  * @param generateTaskOutputType The format of the file generated by the "Generate" task.
  */
final case class GenerateTaskConfiguration(out: File = new File("."), generateTaskOutputType: GenerateTaskOutputType = GenerateTaskOutputType.dockerComposeV3)

/**
  * An enumeration representing all the modes this tool can function in.
  */
sealed trait GenerateTaskOutputType extends EnumEntry

object GenerateTaskOutputType extends Enum[GenerateTaskOutputType] {
  val values = findValues

  case object dockerComposeV3 extends GenerateTaskOutputType
  case object kubernetes      extends GenerateTaskOutputType
}

/**
  * An enumeration representing types a subedge can be.
  */
sealed trait SubEdgeType extends EnumEntry

object SubEdgeType extends Enum[SubEdgeType] {
  val values = findValues

  case object http  extends SubEdgeType
  case object https extends SubEdgeType

}

/**
  * An object that is used provide context to the generator while it is writing.
  *
  * @param writer      The object used to do the actual writing to the file.
  * @param indentLevel The number of indent levels deep the current context is.
  */
final case class WriterContext(writer: BufferedWriter, indentLevel: Int = 0)

/**
  * An object that collects relevant data on a sub edge.
  *
  * This was created because the Edge and SubEdge objects don't seem to have a way of knowing their own ID.
  * The ID is specified as the key of the dictionary they are defined in so they have no access to it.
  *
  * E.g.
  * "some_id": {
  *   "object_key": "object_value"
  * }
  *
  * The object defined by the key "some_id" has no way of knowing it's ID is "some_id".
  * Therefore this context must be constructed by iterating the maps and collecting the data into this object.
  *
  * We could put the ID in the object itself but this would add redundancy.
  *
  * @param edgeId     The ID of the parent edge of the wrapped subedge.
  * @param subEdgeId  The ID of the wrapped subedge.
  * @param subEdge    The subedge that is wrapped by this object.
  */
final case class SubEdgeDef(edgeId: String, subEdgeId: String, subEdge: SubEdge)
