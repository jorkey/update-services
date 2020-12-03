package com.vyulabs.update.distribution.client.distribution

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.OK
import akka.stream.{ActorMaterializer, Materializer}
import com.vyulabs.update.distribution.TestEnvironment
import com.vyulabs.update.info._
import distribution.graphql.{GraphqlContext, GraphqlSchema}
import distribution.mongo.ServiceLogLineDocument
import sangria.macros.LiteralGraphQLStringContext
import spray.json._

import java.util.Date
import com.vyulabs.update.utils.Utils.DateJson._
import scala.concurrent.ExecutionContext

class AddServiceLogsTest extends TestEnvironment {
  behavior of "Service Logs Requests"

  implicit val system = ActorSystem("Distribution")
  implicit val materializer: Materializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutor(null, ex => { ex.printStackTrace(); log.error("Uncatched exception", ex) })

  override def dbName = super.dbName + "-distribution"

  val graphqlContext = new GraphqlContext(UserInfo("distribution1", UserRole.Distribution), workspace)

  val logsCollection = result(collections.State_ServiceLogs)

  it should "add service logs" in {
    val date = new Date()

    assertResult((OK,
      ("""{"data":{"addServiceLogs":true}}""").parseJson))(
      result(graphql.executeQuery(GraphqlSchema.ServiceSchemaDefinition, graphqlContext, graphql"""
        mutation ServicesState($$date: Date!) {
          addServiceLogs (
            service: "service1",
            instance: "instance1",
            process: "process1",
            directory: "dir",
            logs: [
              { date: $$date, level: "INFO", message: "line1" }
              { date: $$date, level: "DEBUG", message: "line2" }
              { date: $$date, level: "ERROR", message: "line3" }
            ]
          )
        }
      """, variables = JsObject("date" -> date.toJson))))

    assertResult(Seq(
      ServiceLogLineDocument(1, new DistributionServiceLogLine("test",
        ServiceLogLine("service1", "instance1", "process1", "dir", LogLine(date, "INFO", None, "line1")))),
      ServiceLogLineDocument(2, new DistributionServiceLogLine("test",
        ServiceLogLine("service1", "instance1", "process1", "dir", LogLine(date, "DEBUG", None, "line2")))),
      ServiceLogLineDocument(3, new DistributionServiceLogLine("test",
        ServiceLogLine("service1", "instance1", "process1", "dir", LogLine(date, "ERROR", None, "line3")))))
    )(result(logsCollection.find()))
  }
}
