package distribution.developer.graphql

import com.mongodb.client.model.{Filters, Sorts}
import com.vyulabs.update.distribution.DistributionMain.log
import com.vyulabs.update.info.{ClientFaultReport, FaultInfo}
import distribution.graphql.GraphqlContext
import distribution.graphql.GraphqlSchema._
import sangria.schema._

import collection.JavaConverters._
import scala.concurrent.ExecutionContext

object DeveloperGraphqlSchema {
  implicit val executionContext = ExecutionContext.fromExecutor(null, ex => log.error("Uncatched exception", ex))

  val Client = Argument("clientName", OptionInputType(StringType))
  val Service = Argument("serviceName", OptionInputType(StringType))
  val Last = Argument("last", OptionInputType(IntType))

  val QueryType = ObjectType(
    "Query",
    fields[GraphqlContext, Unit](
      Field("faults", ListType(ClientFaultReportType),
        description = Some("Returns a list of fault reports."),
        arguments = Client :: Service :: Last :: Nil,
        resolve = c => {
          val clientArg = c.arg(Client).map { client => Filters.eq("clientName", client) }
          val serviceArg = c.arg(Service).map { service => Filters.eq("serviceName", service) }
          val filters = Filters.and((clientArg ++ serviceArg).asJava)
          // https://stackoverflow.com/questions/4421207/how-to-get-the-last-n-records-in-mongodb
          val sort = c.arg(Last).map { last => Sorts.descending("_id") }
          for {
            collection <- c.ctx.mongoDb.getCollection[ClientFaultReport]("faults")
            faults <- collection.find(filters, sort, c.arg(Last))
          } yield faults
        })
    )
  )

  val SchemaDefinition = Schema(query = QueryType)
}