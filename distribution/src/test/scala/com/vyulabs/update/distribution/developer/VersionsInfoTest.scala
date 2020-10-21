package com.vyulabs.update.distribution.developer

import java.nio.file.Files
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.OK
import akka.stream.ActorMaterializer
import com.vyulabs.update.common.Common
import com.vyulabs.update.config.{ClientConfig, ClientInfo}
import com.vyulabs.update.info.{BuildVersionInfo, VersionInfo}
import com.vyulabs.update.lock.SmartFilesLocker
import com.vyulabs.update.users.{UserInfo, UserRole}
import com.vyulabs.update.utils.{IoUtils, Utils}
import com.vyulabs.update.version.BuildVersion
import distribution.developer.config.DeveloperDistributionConfig
import distribution.developer.graphql.{DeveloperGraphqlContext, DeveloperGraphqlSchema}
import distribution.graphql.Graphql
import distribution.mongo.MongoDb
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.slf4j.LoggerFactory
import sangria.macros.LiteralGraphQLStringContext
import spray.json._

import scala.concurrent.Await.result
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext}

class VersionsInfoTest extends FlatSpec with Matchers with BeforeAndAfterAll {
  behavior of "Version Info Requests"

  implicit val system = ActorSystem("Distribution")
  implicit val materializer = ActorMaterializer()

  implicit val log = LoggerFactory.getLogger(this.getClass)

  implicit val executionContext = ExecutionContext.fromExecutor(null, ex => log.error("Uncatched exception", ex))
  implicit val filesLocker = new SmartFilesLocker()

  val config = DeveloperDistributionConfig("Distribution", "instance1", 0, None, "distribution", None, "builder")

  val ownServicesDir = Files.createTempDirectory("test").toFile

  val dir = new DeveloperDistributionDirectory(Files.createTempDirectory("test").toFile)
  val mongo = new MongoDb(getClass.getSimpleName)
  val graphql = new Graphql()

  override def beforeAll() = {
    IoUtils.writeServiceVersion(ownServicesDir, Common.DistributionServiceName, BuildVersion(1, 2, 3))

    val versionInfoCollection = result(mongo.getOrCreateCollection[VersionInfo](), FiniteDuration(3, TimeUnit.SECONDS))
    val clientInfoCollection = result(mongo.getOrCreateCollection[ClientInfo](), FiniteDuration(3, TimeUnit.SECONDS))

    versionInfoCollection.drop().foreach(assert(_))
    clientInfoCollection.drop().foreach(assert(_))

    assert(result(versionInfoCollection.insert(
      VersionInfo("service1", None, BuildVersion(1, 1, 2),
        BuildVersionInfo("author1", Seq.empty, new Date(), None))), FiniteDuration(3, TimeUnit.SECONDS)))
    assert(result(versionInfoCollection.insert(
      VersionInfo("service1", None, BuildVersion(1, 1, 3),
        BuildVersionInfo("author1", Seq.empty, new Date(), None))), FiniteDuration(3, TimeUnit.SECONDS)))

    assert(result(clientInfoCollection.insert(
      ClientInfo("client1", ClientConfig("common", Some("test")))), FiniteDuration(3, TimeUnit.SECONDS)))

    assert(result(versionInfoCollection.insert(
      VersionInfo("service1", Some("client1"), BuildVersion("client1", 1, 1, 0),
        BuildVersionInfo("author2", Seq.empty, new Date(), None))), FiniteDuration(3, TimeUnit.SECONDS)))
    assert(result(versionInfoCollection.insert(
      VersionInfo("service1", Some("client1"), BuildVersion("client1", 1, 1, 1),
        BuildVersionInfo( "author2", Seq.empty, new Date(), None))), FiniteDuration(3, TimeUnit.SECONDS)))
  }

  override protected def afterAll(): Unit = {
    dir.drop()
    IoUtils.deleteFileRecursively(ownServicesDir)
    mongo.dropDatabase().foreach(assert(_))
  }

  it should "return own service info" in {
    val graphqlContext = DeveloperGraphqlContext(config, dir, mongo, UserInfo("admin", UserRole.Administrator))
    val query =
      graphql"""
        query DistributionVersionQuery($$directory: String!) {
          ownServiceVersion (service: "distribution", directory: $$directory)
        }
      """
    val future = graphql.executeQuery(DeveloperGraphqlSchema.AdministratorSchemaDefinition, graphqlContext, query,
      None, variables = JsObject("directory" -> JsString(ownServicesDir.toString)))
    val result = Await.result(future, FiniteDuration.apply(1, TimeUnit.SECONDS))
    assertResult((OK,
      ("""{"data":{"ownServiceVersion":"1.2.3"}}""").parseJson))(result)
  }

  it should "return version info" in {
    val graphqlContext = DeveloperGraphqlContext(config, dir, mongo, UserInfo("admin", UserRole.Administrator))
    val query =
      graphql"""
        query {
          versionsInfo (service: "service1", version: "1.1.2") {
            version
            buildInfo {
              author
            }
          }
        }
      """
    val future = graphql.executeQuery(DeveloperGraphqlSchema.AdministratorSchemaDefinition, graphqlContext, query)
    val result = Await.result(future, FiniteDuration.apply(1, TimeUnit.SECONDS))
    assertResult((OK,
      ("""{"data":{"versionsInfo":[{"version":"1.1.2","buildInfo":{"author":"author1"}}]}}""").parseJson))(result)
  }

  it should "return versions info" in {
    val graphqlContext = DeveloperGraphqlContext(config, dir, mongo, UserInfo("admin", UserRole.Administrator))
    val query =
      graphql"""
        query {
          versionsInfo (service: "service1") {
            version
            buildInfo {
              author
            }
          }
        }
      """
    val future = graphql.executeQuery(DeveloperGraphqlSchema.AdministratorSchemaDefinition, graphqlContext, query)
    val result = Await.result(future, FiniteDuration.apply(1, TimeUnit.SECONDS))
    assertResult((OK,
      ("""{"data":{"versionsInfo":[{"version":"1.1.2","buildInfo":{"author":"author1"}},{"version":"1.1.3","buildInfo":{"author":"author1"}}]}}""").parseJson))(result)
  }

  it should "return client versions info" in {
    val graphqlContext = DeveloperGraphqlContext(config, dir, mongo, UserInfo("admin", UserRole.Administrator))
    val query =
      graphql"""
        query {
          versionsInfo (service: "service1", client: "client1") {
            version
            buildInfo {
              author
            }
          }
        }
      """
    val future = graphql.executeQuery(DeveloperGraphqlSchema.AdministratorSchemaDefinition, graphqlContext, query)
    val result = Await.result(future, FiniteDuration.apply(1, TimeUnit.SECONDS))
    assertResult((OK,
      ("""{"data":{"versionsInfo":[{"version":"client1-1.1.0","buildInfo":{"author":"author2"}},{"version":"client1-1.1.1","buildInfo":{"author":"author2"}}]}}""").parseJson))(result)
  }
}
