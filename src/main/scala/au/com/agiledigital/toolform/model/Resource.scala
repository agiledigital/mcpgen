package au.com.agiledigital.toolform.model

import com.typesafe.config.Config

/**
  * Resources are not buildable components. They are expected to be provided externally by the environment that the
  * project is deployed into. For example, a database is a resource.
  *
  * Developers can provided mappings between a resource and the external resource in their 'dev-resources' file. This
  * will convert the Resource into a MappedResource, which is a Composable element.
  *
  * @param id           the id of the resource.
  * @param resourceType the type of the resource (e.g. postgres, elastic search)
  * @param settings     additional settings for the resource.
  */
final case class Resource(id: String, resourceType: String, settings: Option[Config]) extends ProjectElement {
  val tagName = id
}

/**
  * A MappedResource is a Resource that has been mapped by the developer and can be Composed into the .yml config.
  *
  * @param path        the full path to the mapped resource config.
  * @param tagName     the name of the docker image used to provide the resource.
  * @param environment the environment variables to pass to the docker image.
  * @param ports       the ports that will be exposed by the resource docker.
  * @param links       the links that will be made to the resource docker.
  * @param resource    the resource that was mapped.
  */
final case class MappedResource(path: String, tagName: String, environment: Map[String, String], ports: Seq[String], links: Seq[String], resource: Resource) extends ProjectElement {

  override def id: String = path

}
