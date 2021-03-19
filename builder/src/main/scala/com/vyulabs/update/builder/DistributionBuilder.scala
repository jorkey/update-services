package com.vyulabs.update.builder

import com.vyulabs.libs.git.GitRepository
import com.vyulabs.update.builder.config._
import com.vyulabs.update.common.common.Common
import com.vyulabs.update.common.common.Common.{ConsumerProfile, DistributionName, ServiceName, UserName}
import com.vyulabs.update.common.config.DistributionConfig
import com.vyulabs.update.common.distribution.client.graphql.AdministratorGraphqlCoder.{administratorMutations, administratorQueries, administratorSubscriptions}
import com.vyulabs.update.common.distribution.client.{DistributionClient, HttpClientImpl, SyncDistributionClient, SyncSource}
import com.vyulabs.update.common.distribution.server.{DistributionDirectory, SettingsDirectory}
import com.vyulabs.update.common.info.UserRole.UserRole
import com.vyulabs.update.common.info.{ClientDesiredVersionDelta, DeveloperDesiredVersionDelta, DeveloperDesiredVersions, SequencedServiceLogLine, UserRole}
import com.vyulabs.update.common.process.ProcessUtils
import com.vyulabs.update.common.utils.IoUtils
import com.vyulabs.update.common.version.{ClientDistributionVersion, ClientVersion, DeveloperDistributionVersion, DeveloperVersion}
import org.slf4j.{Logger, LoggerFactory}
import spray.json.DefaultJsonProtocol._

import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 04.02.19.
  * Copyright FanDate, Inc.
  */
class DistributionBuilder(cloudProvider: String, startService: () => Boolean,
                          distributionDirectory: DistributionDirectory,
                          distributionName: String, distributionTitle: String,
                          mongoDbName: String, mongoDbTemporary: Boolean, port: Int)
                         (implicit executionContext: ExecutionContext) {
  implicit val log = LoggerFactory.getLogger(this.getClass)

  private val developerBuilder = new DeveloperBuilder(distributionDirectory.getBuilderDir(), distributionName)
  private val clientBuilder = new ClientBuilder(distributionDirectory.getBuilderDir(), distributionName)

  private val initialClientVersion = ClientDistributionVersion(DeveloperDistributionVersion(distributionName, DeveloperVersion.initialVersion))

  private var adminDistributionClient = Option.empty[SyncDistributionClient[SyncSource]]
  private var developerDistributionClient = Option.empty[SyncDistributionClient[SyncSource]]
  private var builderDistributionClient = Option.empty[SyncDistributionClient[SyncSource]]

  private var distributionConfig = Option.empty[DistributionConfig]

  private var providerDistributionName = Option.empty[DistributionName]
  private var providerDistributionClient = Option.empty[SyncDistributionClient[SyncSource]]

  def buildDistributionFromSources(): Boolean = {
    log.info("")
    log.info(s"########################### Generate initial versions of services")
    log.info("")
    if (!generateDeveloperAndClientVersions(Map(
        (Common.DistributionServiceName -> DeveloperVersion.initialVersion),
        (Common.ScriptsServiceName -> DeveloperVersion.initialVersion)))) {
      log.error("Can't generate initial versions")
      return false
    }

    log.info("")
    log.info(s"########################### Install distribution service")
    log.info("")
    if (!installDistributionService(initialClientVersion, initialClientVersion)) {
      log.error("Can't install distribution service")
      return false
    }

    log.info("")
    log.info(s"########################### Distribution service is ready")
    log.info("")
    true
  }

  def addDistributionUsers(): Boolean = {
    log.info(s"--------------------------- Add distribution users")
    if (!addUser("installer", UserRole.Developer) || !addUser("builder", UserRole.Builder) || !addUser("service", UserRole.Updater)) {
      return false
    }
    true
  }

  def removeTemporaryDistributionUsers(): Boolean = {
    log.info(s"--------------------------- Remove temporary distribution users")
    if (!removeUser("installer")) {
      return false
    }
    true
  }

  def buildFromProviderDistribution(providerDistributionName: DistributionName, providerDistributionURL: URL,
                                    consumerProfile: ConsumerProfile, testDistributionMatch: Option[String]): Boolean = {
    this.providerDistributionName = Some(providerDistributionName)
    providerDistributionClient = Some(new SyncDistributionClient(
      new DistributionClient(new HttpClientImpl(providerDistributionURL)), FiniteDuration(60, TimeUnit.SECONDS)))

    log.info("")
    log.info(s"########################### Download and generate client versions")
    log.info("")
    val scriptsVersion = downloadAndGenerateClientVersion(providerDistributionClient.get, Common.ScriptsServiceName).getOrElse {
      return false
    }
    val distributionVersion = downloadAndGenerateClientVersion(providerDistributionClient.get, Common.DistributionServiceName).getOrElse {
      return false
    }

    log.info("")
    log.info(s"########################### Install distribution service")
    log.info("")
    if (!installDistributionService(scriptsVersion, distributionVersion)) {
      log.error("Can't install distribution service")
      return false
    }

    log.info("")
    log.info(s"########################### Add distribution provider to distribution server")
    log.info("")
    if (!adminDistributionClient.get.graphqlRequest(administratorMutations.addDistributionProvider(providerDistributionName, providerDistributionURL, None)).getOrElse(false)) {
      log.error(s"Can't add distribution provider")
      return false
    }

    log.info("")
    log.info(s"########################### Add distribution consumer to provider distribution server")
    log.info("")
    if (!providerDistributionClient.get.graphqlRequest(administratorMutations.addDistributionConsumer(distributionName, consumerProfile, testDistributionMatch)).getOrElse(false)) {
      log.error(s"Can't add distribution consumer")
      return false
    }

    log.info("")
    log.info(s"########################### Distribution service is ready")
    log.info("")
    true
  }

  def generateAndUploadInitialVersions(author: String): Boolean = {
    log.info(s"--------------------------- Generate and upload initial versions")
    if (!uploadDeveloperAndClientVersions(Map(
      (Common.DistributionServiceName -> DeveloperDistributionVersion(distributionName, DeveloperVersion.initialVersion)),
      (Common.ScriptsServiceName -> DeveloperDistributionVersion(distributionName, DeveloperVersion.initialVersion))), author)) {
      return false
    }
    if (!generateAndUploadDeveloperAndClientVersions(Map(
      (Common.BuilderServiceName -> DeveloperVersion.initialVersion),
      (Common.UpdaterServiceName -> DeveloperVersion.initialVersion)), author)) {
      return false
    }
    true
  }

  def addCommonConsumerProfile(): Boolean = {
    log.info(s"--------------------------- Add common consumer profile")
    adminDistributionClient.get.graphqlRequest(
      administratorMutations.addDistributionConsumerProfile(Common.CommonConsumerProfile, Seq(Common.DistributionServiceName,
        Common.ScriptsServiceName, Common.BuilderServiceName, Common.UpdaterServiceName))).getOrElse(false)
  }

  def updateDistributionFromProvider(): Boolean = {
    log.info(s"--------------------------- Get distribution provider desired versions")
    val providerDesiredVersions = DeveloperDesiredVersions.toMap(
        adminDistributionClient.get.graphqlRequest(administratorQueries.getDistributionProviderDesiredVersions(providerDistributionName.get)).getOrElse {
      log.error("Can't get provider distribution developer desired versions")
      return false
    })
    val versionsForUpdate = providerDesiredVersions.filter { case (serviceName, version) =>
      val existingVersions = adminDistributionClient.get.graphqlRequest(administratorQueries.getDeveloperVersionsInfo(serviceName)).getOrElse {
        log.error(s"Can't get distribution server existing versions of service ${serviceName}")
        return false
      }
      !existingVersions.exists(_.version == version)
    }
    log.info(s"--------------------------- Versions for update ${versionsForUpdate}")
    versionsForUpdate.foreach { case (serviceName, version) =>
      log.info(s"--------------------------- Install provider version ${version} of service ${serviceName}")
      val taskId = adminDistributionClient.get.graphqlRequest(administratorMutations.installProviderVersion(providerDistributionName.get, serviceName, version)).getOrElse {
        log.error(s"Can't install provider developer version ${version} of service ${serviceName}")
        return false
      }
      val source = adminDistributionClient.get.graphqlSubRequest(administratorSubscriptions.subscribeTaskLogs(taskId)).getOrElse {
        log.error(s"Can't subscribe to task ${taskId} logs")
        return false
      }
      var line = Option.empty[SequencedServiceLogLine]
      do {
        line = source.next()
        line.foreach(line => {
          val l = line.logLine.line
          if (l.level == "INFO") {
            log.info(l.message)
          }
          for (terminationStatus <- l.terminationStatus) {
            if (!terminationStatus) {
              log.error(s"Install version ${version} of service ${serviceName} error")
              return false
            }
          }
        })
      } while (line.isDefined)
    }
    log.info(s"--------------------------- Consumer distribution server is updated successfully")
    true
  }

  def waitForServerAvailable(waitingTimeoutSec: Int = 10000)
                            (implicit log: Logger): Boolean = {
    val client = adminDistributionClient.getOrElse {
      sys.error("No distribution client")
    }
    log.info(s"Wait for distribution server become available")
    for (_ <- 0 until waitingTimeoutSec) {
      if (client.available()) {
        Thread.sleep(1000)
        return true
      }
      Thread.sleep(1000)
    }
    log.error(s"Timeout of waiting for distribution server become available")
    false
  }

  def installBuilderFromSources(): Boolean = {
    log.info(s"--------------------------- Install builder")
    val updateSourcesUri = GitRepository.openRepository(new File(".")).map(_.getUrl())
    if (installBuilder(updateSourcesUri)) {
      log.info(s"--------------------------- Builder is installed successfully")
      true
    } else {
      false
    }
  }

  def installBuilder(updateSourcesUri: Option[String]): Boolean = {
    val config = distributionConfig.getOrElse {
      sys.error("No distribution config")
    }
    log.info(s"--------------------------- Initialize builder directory")
    if (!IoUtils.copyFile(new File(clientBuilder.clientBuildDir(Common.ScriptsServiceName), "builder"), distributionDirectory.getBuilderDir()) ||
      !IoUtils.copyFile(new File(clientBuilder.clientBuildDir(Common.ScriptsServiceName), Common.UpdateSh), new File(distributionDirectory.getBuilderDir(), Common.UpdateSh))) {
      return false
    }
    distributionDirectory.getBuilderDir().listFiles().foreach { file =>
      if (file.getName.endsWith(".sh") && !IoUtils.setExecuteFilePermissions(file)) {
        return false
      }
    }

    log.info(s"--------------------------- Create builder config")
    val distributionLinks = Seq(DistributionLink(distributionName, makeDistributionUrl("builder")))
    if (!IoUtils.writeJsonToFile(new File(distributionDirectory.getBuilderDir(), Common.BuilderConfigFileName), BuilderConfig(config.instanceId, distributionLinks))) {
      return false
    }

    log.info(s"--------------------------- Create settings directory")
    val settingsDirectory = new SettingsDirectory(distributionDirectory.getBuilderDir(), distributionName)

    for (updateSourcesUri <- updateSourcesUri) {
      log.info(s"--------------------------- Create sources config")
      val sourcesConfig = Map.empty[ServiceName, Seq[SourceConfig]] +
        (Common.ScriptsServiceName -> Seq(SourceConfig(Right(GitConfig(updateSourcesUri, None)), None))) +
        (Common.BuilderServiceName -> Seq(SourceConfig(Right(GitConfig(updateSourcesUri, None)), None))) +
        (Common.UpdaterServiceName -> Seq(SourceConfig(Right(GitConfig(updateSourcesUri, None)), None))) +
        (Common.DistributionServiceName -> Seq(SourceConfig(Right(GitConfig(updateSourcesUri, None)), None)))
      if (!IoUtils.writeJsonToFile(settingsDirectory.getSourcesFile(), SourcesConfig(sourcesConfig))) {
        log.error(s"Can't write sources config file")
        return false
      }
    }
    true
  }

  private def generateAndUploadDeveloperAndClientVersions(versions: Map[ServiceName, DeveloperVersion], author: String): Boolean = {
    generateDeveloperAndClientVersions(versions) &&
      uploadDeveloperAndClientVersions(versions.mapValues(v => DeveloperDistributionVersion(distributionName, v)), author)
  }

  private def uploadDeveloperAndClientVersions(versions: Map[ServiceName, DeveloperDistributionVersion], author: String): Boolean = {
    if (!uploadDeveloperVersions(versions, author)) {
      return false
    }
    if (!uploadClientVersions(versions.mapValues(version => ClientDistributionVersion(version)), author)) {
      return false
    }
    true
  }

  private def installDistributionService(scriptsVersion: ClientDistributionVersion, distributionVersion: ClientDistributionVersion): Boolean = {
    if (!IoUtils.copyFile(new File(clientBuilder.clientBuildDir(Common.ScriptsServiceName), "distribution"), distributionDirectory.directory) ||
      !IoUtils.copyFile(new File(clientBuilder.clientBuildDir(Common.ScriptsServiceName), Common.UpdateSh), new File(distributionDirectory.directory, Common.UpdateSh)) ||
      !IoUtils.copyFile(clientBuilder.clientBuildDir(Common.DistributionServiceName), distributionDirectory.directory)) {
      return false
    }
    distributionDirectory.directory.listFiles().foreach { file =>
      if (file.getName.endsWith(".sh") && !IoUtils.setExecuteFilePermissions(file)) {
        return false
      }
    }
    if (!IoUtils.writeDesiredServiceVersion(distributionDirectory.directory, Common.ScriptsServiceName, scriptsVersion) ||
      !IoUtils.writeServiceVersion(distributionDirectory.directory, Common.ScriptsServiceName, scriptsVersion)) {
      return false
    }
    if (!IoUtils.writeDesiredServiceVersion(distributionDirectory.directory, Common.DistributionServiceName, distributionVersion) ||
      !IoUtils.writeServiceVersion(distributionDirectory.directory, Common.DistributionServiceName, distributionVersion)) {
      return false
    }
    log.info(s"--------------------------- Make distribution config file")
    val arguments = Seq(cloudProvider, distributionName, distributionTitle, mongoDbName, mongoDbTemporary.toString, port.toString)
    if (!ProcessUtils.runProcess("/bin/sh", ".make_distribution_config.sh" +: arguments, Map.empty,
      distributionDirectory.directory, Some(0), None, ProcessUtils.Logging.Realtime)) {
      log.error(s"Make distribution config file error")
      return false
    }
    log.info(s"--------------------------- Read distribution config")
    distributionConfig = DistributionConfig.readFromFile(distributionDirectory.getConfigFile())
    if (distributionConfig.isEmpty) {
      log.error(s"Can't read distribution config file ${distributionDirectory.getConfigFile()}")
      return false
    }

    adminDistributionClient = Some(new SyncDistributionClient(
      new DistributionClient(new HttpClientImpl(makeDistributionUrl("admin"))), FiniteDuration(60, TimeUnit.SECONDS)))
    log.info(s"--------------------------- Start distribution service")
    if (!startDistributionService()) {
      log.error("Can't start distribution service")
      return false
    }

    true
  }

  private def addUser(userName: UserName, role: UserRole): Boolean = {
    adminDistributionClient.get.graphqlRequest(administratorMutations.addUser(userName, Seq(role), userName)).getOrElse {
      return false
    }
    role match {
      case UserRole.Developer =>
        developerDistributionClient = Some(new SyncDistributionClient(
          new DistributionClient(new HttpClientImpl(makeDistributionUrl(userName))), FiniteDuration(60, TimeUnit.SECONDS)))
      case UserRole.Builder =>
        builderDistributionClient = Some(new SyncDistributionClient(
          new DistributionClient(new HttpClientImpl(makeDistributionUrl(userName))), FiniteDuration(60, TimeUnit.SECONDS)))
      case _ =>
    }
    true
  }

  private def removeUser(userName: UserName): Boolean = {
    adminDistributionClient.get.graphqlRequest(administratorMutations.removeUser(userName)).getOrElse {
      return false
    }
  }

  private def generateDeveloperAndClientVersions(versions: Map[ServiceName, DeveloperVersion]): Boolean = {
    versions.foreach { case (serviceName, version) =>
      if (!generateDeveloperAndClientVersions(serviceName, version)) {
        return false
      }
    }
    true
  }

  private def uploadDeveloperVersions(versions: Map[ServiceName, DeveloperDistributionVersion], author: String): Boolean = {
    log.info(s"--------------------------- Upload developer images of services ${versions.keySet}")
    versions.foreach { case (serviceName, version) =>
      if (!developerBuilder.uploadDeveloperVersion(builderDistributionClient.get, serviceName, version, author)) {
        log.error(s"Can't upload developer version ${version} of service ${serviceName}")
        return false
      }
    }

    log.info(s"--------------------------- Set developer desired versions")
    if (!developerBuilder.setDesiredVersions(developerDistributionClient.get, versions.map { case (serviceName, version) =>
      DeveloperDesiredVersionDelta(serviceName, Some(version)) }.toSeq)) {
      log.error("Set developer desired versions error")
      return false
    }
    true
  }

  private def uploadClientVersions(versions: Map[ServiceName, ClientDistributionVersion], author: String): Boolean = {
    log.info(s"--------------------------- Upload client images of services")
    versions.foreach { case (serviceName, version) =>
      if (!clientBuilder.uploadClientVersion(builderDistributionClient.get, serviceName, version, author)) {
        log.error(s"Can't upload developer version ${version} of service ${serviceName}")
        return false
      }
    }

    log.info(s"--------------------------- Set client desired versions")
    if (!clientBuilder.setDesiredVersions(builderDistributionClient.get, versions.map { case (serviceName, version) =>
      ClientDesiredVersionDelta(serviceName, Some(version)) }.toSeq)) {
      log.error("Set developer desired versions error")
      return false
    }

    true
  }

  private def generateDeveloperAndClientVersions(serviceName: ServiceName, developerVersion: DeveloperVersion): Boolean = {
    val developerDistributionVersion = DeveloperDistributionVersion(distributionName, developerVersion)
    log.info(s"--------------------------- Generate version ${developerDistributionVersion} of service ${serviceName}")
    log.info(s"Generate developer version of service ${serviceName}")
    val arguments = Map.empty + ("version" -> developerDistributionVersion.toString)
    if (!developerBuilder.generateDeveloperVersion(serviceName, new File("."), arguments)) {
      log.error(s"Can't generate developer version of service ${serviceName}")
      return false
    }

    log.info(s"Copy developer version of service ${serviceName} to client directory")
    if (!IoUtils.copyFile(developerBuilder.developerBuildDir(serviceName), clientBuilder.clientBuildDir(serviceName))) {
      log.error(s"Can't copy ${developerBuilder.developerBuildDir(serviceName)} to ${clientBuilder.clientBuildDir(serviceName)}")
      return false
    }

    log.info(s"Generate client version of service ${serviceName}")
    if (!clientBuilder.generateClientVersion(serviceName, Map.empty)) {
      log.error(s"Can't generate client version of service ${serviceName}")
      return false
    }
    true
  }

  private def downloadAndGenerateClientVersion(developerDistributionClient: SyncDistributionClient[SyncSource],
                                               serviceName: ServiceName): Option[ClientDistributionVersion] = {
    log.info(s"--------------------------- Get developer desired version of service ${serviceName}")
    val desiredVersion = developerDistributionClient.graphqlRequest(administratorQueries.getDeveloperDesiredVersions(Seq(serviceName))).getOrElse(Seq.empty).headOption.getOrElse {
      log.error(s"Can't get developer desired version of service ${serviceName}")
      return None
    }
    val developerVersion = desiredVersion.version

    log.info(s"--------------------------- Download developer version of service ${serviceName}")
    val developerVersionInfo = clientBuilder.downloadDeveloperVersion(developerDistributionClient,
        serviceName, developerVersion).getOrElse {
      log.error("Can't download developer version of distribution service")
      return None
    }

    val clientVersion = ClientDistributionVersion(developerVersion.distributionName, ClientVersion(developerVersion.version))
    log.info(s"--------------------------- Generate client version ${clientVersion} of service ${serviceName}")
    if (!clientBuilder.generateClientVersion(serviceName, Map.empty)) {
      log.error(s"Can't generate client version of service ${serviceName}")
      return None
    }

    Some(clientVersion)
  }

  private def makeDistributionUrl(user: UserName): URL = {
    val config = distributionConfig.getOrElse {
      sys.error("No distribution config")
    }
    val protocol = if (config.network.ssl.isDefined) "https" else "http"
    val port = config.network.port
    new URL(s"${protocol}://${user}:${user}@localhost:${port}")
  }

  private def startDistributionService(): Boolean = {
    log.info(s"--------------------------- Start service")
    if (!startService()) {
      log.error("Can't start service process")
      return false
    }
    log.info(s"--------------------------- Waiting for distribution service became available")
    if (!waitForServerAvailable(10000)) {
      log.error("Can't start distribution server")
      return false
    }
    log.info("Distribution server is available")

    Thread.sleep(5000)
    true
  }
}