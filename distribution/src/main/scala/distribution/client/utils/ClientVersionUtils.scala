package distribution.client.utils

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import com.vyulabs.update.common.Common.ServiceName
import com.vyulabs.update.distribution.client.{ClientDistributionDirectory, ClientDistributionWebPaths}
import com.vyulabs.update.info.DesiredVersions
import com.vyulabs.update.lock.SmartFilesLocker
import com.vyulabs.update.version.BuildVersion
import distribution.utils.{GetUtils, VersionUtils}

import scala.concurrent.Future

trait ClientVersionUtils extends GetUtils with VersionUtils with ClientDistributionWebPaths with SprayJsonSupport {
  def getClientDesiredVersion(serviceName: ServiceName)
                                 (implicit system: ActorSystem, materializer: Materializer, filesLocker: SmartFilesLocker, dir: ClientDistributionDirectory): Future[Option[BuildVersion]] = {
    val future = parseJsonFileWithLock[DesiredVersions](dir.getDesiredVersionsFile())
    getDesiredVersion(serviceName, future)
  }
}
