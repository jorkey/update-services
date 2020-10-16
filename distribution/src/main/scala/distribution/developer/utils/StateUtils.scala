package distribution.developer.utils

import java.io.{File, IOException}
import java.util.Date

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, onSuccess}
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import com.vyulabs.update.common.Common
import com.vyulabs.update.common.Common._
import com.vyulabs.update.distribution.DistributionMain
import com.vyulabs.update.distribution.developer.{DeveloperDistributionDirectory, DeveloperDistributionWebPaths}
import com.vyulabs.update.info.InstanceVersions._
import com.vyulabs.update.info._
import com.vyulabs.update.lock.SmartFilesLocker
import distribution.developer.config.DeveloperDistributionConfig
import distribution.utils.GetUtils
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import spray.json._

trait StateUtils extends GetUtils with DeveloperDistributionWebPaths with SprayJsonSupport {
  private implicit val log = LoggerFactory.getLogger(this.getClass)

  protected implicit val system: ActorSystem
  protected implicit val materializer: Materializer
  protected implicit val executionContext: ExecutionContext
  protected implicit val filesLocker: SmartFilesLocker

  protected val dir: DeveloperDistributionDirectory
  protected val config: DeveloperDistributionConfig

  def getOwnServicesState(): ServicesState = {
    ServicesState.getOwnInstanceState(Common.DistributionServiceName, new Date(DistributionMain.executionStart))
      .merge(ServicesState.getServiceInstanceState(Common.ScriptsServiceName, new File(".")))
      .merge(ServicesState.getServiceInstanceState(Common.BuilderServiceName, new File(config.builderDirectory)))
      .merge(ServicesState.getServiceInstanceState(Common.ScriptsServiceName, new File(config.builderDirectory)))
  }

  def getOwnInstancesState(): InstancesState = {
    InstancesState.empty.addState(config.instanceId, getOwnServicesState())
  }

  def getOwnInstanceVersions(): InstanceVersions = {
    InstanceVersions.empty.addVersions(config.instanceId, getOwnServicesState())
  }

  def getClientInstancesState(clientName: ClientName): Future[Option[InstancesState]] = {
    val promise = Promise[Option[InstancesState]]()
    if (config.selfDistributionClient.contains(clientName)) {
      promise.success(Some(getOwnInstancesState()))
    } else {
      getFileContentWithLock(dir.getInstancesStateFile(clientName)).onComplete {
        case Success(bytes) =>
          bytes match {
            case Some(bytes) =>
              try {
                val instancesState = bytes.decodeString("utf8").parseJson.convertTo[InstancesState]
                promise.success(Some(instancesState))
              } catch {
                case ex: Exception =>
                  promise.failure(ex)
              }
            case None =>
              promise.success(None)
          }
        case Failure(ex) =>
          promise.failure(ex)
      }
    }
    promise.future
  }

  def getClientInstanceVersions(clientName: ClientName): Future[InstanceVersions] = {
    getClientInstancesState(clientName).collect {
      case Some(state) =>
        var versions = InstanceVersions.empty
        state.instances.foreach { case (instanceId, servicesStates) =>
          versions = versions.addVersions(instanceId, servicesStates)
        }
        versions
      case None =>
        InstanceVersions.empty
    }
  }

  def getServiceState(clientName: ClientName, instanceId: InstanceId,
                      directory: ServiceDirectory, serviceName: ServiceName): Future[Option[ServiceState]] = {
    for {
      instancesState <- getClientInstancesState(clientName)
      serviceState <- {
        Future(instancesState match {
          case Some(instancesState) =>
            instancesState.instances.get(instanceId) match {
              case Some(servicesState) =>
                servicesState.directories.get(directory) match {
                  case Some(directoryState) =>
                    directoryState.get(serviceName) match {
                      case Some(state) =>
                        Some(state)
                      case None =>
                        log.debug(s"Service ${serviceName} is not found")
                        None
                    }
                  case None =>
                    log.debug(s"Directory ${directory} is not found")
                    None
                }
              case None =>
                log.debug(s"Instance ${instanceId} is not found")
                None
            }
          case None =>
            log.debug(s"Client ${clientName} state is not found")
            None
        })
      }
    } yield {
      serviceState
    }
  }
}
