package com.vyulabs.update.distribution

import java.io._
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import java.util.Base64

import com.typesafe.config.ConfigParseOptions
import com.vyulabs.update.common.Common.{ClientName, InstanceId, ServiceName}
import com.vyulabs.update.utils.{IoUtils, ZipUtils}
import com.vyulabs.update.version.BuildVersion
import org.slf4j.Logger
import spray.json.JsValue

import scala.annotation.tailrec
import spray.json._
import com.vyulabs.update.info.{DesiredVersion, DeveloperVersionInfo, DeveloperVersionsInfo, DirectoryServiceState, InstanceServiceState, ProfiledServiceName, ServicesVersions}
import DistributionWebPaths._
import com.vyulabs.update.logs.ServiceLogs

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 23.04.19.
  * Copyright FanDate, Inc.
  */
class DistributionDirectoryClient(val url: URL)(implicit log: Logger) {

  def isVersionExists(serviceName: ServiceName, buildVersion: BuildVersion): Boolean = {
    // TODO graphql
    false
  }

  def downloadVersionImage(serviceName: ServiceName, buildVersion: BuildVersion, file: File): Boolean = {
    //downloadToFile(makeUrl(downloadVersionPath(serviceName, buildVersion)), file)
    // TODO graphql
    false
  }

  def downloadVersion(serviceName: ServiceName, buildVersion: BuildVersion, directory: File): Boolean = {
    val tmpFile = File.createTempFile(s"build", ".zip")
    try {
      if (!downloadVersionImage(serviceName, buildVersion, tmpFile)) {
        return false
      }
      if (!ZipUtils.unzip(tmpFile, directory)) {
        log.error(s"Can't unzip version ${buildVersion} of service ${serviceName}")
        return false
      }
      true
    } finally {
      tmpFile.delete()
    }
  }

  def uploadDeveloperVersion(serviceName: ServiceName, buildVersion: BuildVersion, buildVersionInfo: DeveloperVersionInfo, buildDir: File): Boolean = {
    val imageTmpFile = File.createTempFile("build", ".zip")
    try {
      if (!ZipUtils.zip(imageTmpFile, buildDir)) {
        log.error("Can't zip build directory")
        return false
      }
      // TODO graphql
      // uploadFromFile(makeUrl(uploadVersionPath(serviceName, buildVersion)), "developerVersion", imageTmpFile)
      // set version info
      false
    } finally {
      imageTmpFile.delete()
    }
  }

  def uploadTestedVersions(servicesVersions: ServicesVersions): Boolean = {
    // TODO graphql
    false
  }

  def getServerVersion(versionPath: String): Option[BuildVersion] = {
    // TODO graphql
    // set version info
    null
  }

  def waitForServerUpdated(versionPath: String, desiredVersion: BuildVersion): Boolean = {
    log.info(s"Wait for distribution server updated")
    Thread.sleep(5000)
    for (_ <- 0 until 25) {
      if (exists(makeUrl(versionPath))) {
        getServerVersion(versionPath) match {
          case Some(version) =>
            if (version == desiredVersion) {
              log.info(s"Distribution server is updated")
              return true
            }
          case None =>
            return false
        }
      }
      Thread.sleep(1000)
    }
    log.error(s"Timeout of waiting for distribution server become available")
    false
  }

  def downloadVersionsInfo(clientName: Option[ClientName], serviceName: ServiceName): Option[DeveloperVersionsInfo] = {
    // TODO graphql
    None
  }

  def downloadInstalledDesiredVersions(): Option[Map[ServiceName, BuildVersion]] = {
    // TODO graphql
    None
  }

  def downloadDeveloperDesiredVersionsForMe(): Option[Map[ServiceName, BuildVersion]] = {
    null
  }

  def downloadDeveloperDesiredVersions(clientName: Option[ClientName]): Option[Seq[DesiredVersion]] = {
    clientName match {
      case Some(clientName) =>
        log.info(s"Download desired versions for client ${clientName}")
      case None =>
        log.info(s"Download desired versions")
    }
    // TODO graphql
    null
  }

  def uploadDesiredVersions(clientName: Option[ClientName], desiredVersions: Seq[DesiredVersion]): Boolean = {
    clientName match {
      case Some(clientName) =>
        log.info(s"Upload desired versions for client ${clientName}")
      case None =>
        log.info(s"Upload desired versions")
    }
    // TODO graphql
    false
  }

  def uploadDesiredVersions(desiredVersions: Seq[DesiredVersion]): Boolean = {
    // TODO graphql
    //uploadFromJson(makeUrl(uploadDesiredVersionsPath), desiredVersionsName, uploadDesiredVersionsPath, desiredVersions.toJson)
    false
  }

  def uploadServicesStates(servicesState: Seq[DirectoryServiceState]): Boolean = {
    // TODO graphql
    false
  }

  def uploadServiceLogs(instanceId: InstanceId, profiledServiceName: ProfiledServiceName, serviceLogs: ServiceLogs): Boolean = {
    // TODO graphql
    false
  }

  def uploadServiceFault(serviceName: ServiceName, faultFile: File): Boolean = {
    // TODO graphql
    false
  }

  def uploadServicesState(servicesState: Seq[InstanceServiceState]): Boolean = {
    // TODO graphql
    //uploadFromJson(makeUrl(apiPathPrefix + "/" + getInstancesStatePath()),
    //  instancesStateName, instancesStatePath, servicesState.toJson)
    false
  }

  protected def exists(url: URL): Boolean = {
    executeRequest(url, (connection: HttpURLConnection) => {
      if (!url.getUserInfo.isEmpty) {
        val encoded = Base64.getEncoder.encodeToString((url.getUserInfo).getBytes(StandardCharsets.UTF_8))
        connection.setRequestProperty("Authorization", "Basic " + encoded)
      }
      connection.setConnectTimeout(10000)
      connection.setReadTimeout(30000)
      connection.setRequestMethod("HEAD")
    })
  }

  protected def downloadToJson(url: URL, options: ConfigParseOptions = ConfigParseOptions.defaults()): Option[JsValue] = {
    downloadToString(url).map(_.parseJson)
  }

  protected def downloadToString(url: URL): Option[String] = {
    val output = new ByteArrayOutputStream()
    if (download(url, output)) {
      Some(output.toString("utf8"))
    } else {
      None
    }
  }

  protected def downloadToFile(url: URL, file: File): Boolean = {
    val output =
      try {
        new FileOutputStream(file)
      } catch {
        case e: IOException =>
          log.error(s"Can't open ${file}", e)
          return false
      }
    var stat = false
    try {
      stat = download(url, output)
      stat
    } finally {
      output.close()
      if (!stat) {
        file.delete()
      }
    }
  }

  protected def download(url: URL, output: OutputStream): Boolean = {
    if (log.isDebugEnabled) log.debug(s"Download by url ${url}")
    executeRequest(url, (connection: HttpURLConnection) => {
      if (!url.getUserInfo.isEmpty) {
        val encoded = Base64.getEncoder.encodeToString((url.getUserInfo).getBytes(StandardCharsets.UTF_8))
        connection.setRequestProperty("Authorization", "Basic " + encoded)
      }
      connection.setConnectTimeout(10000)
      connection.setReadTimeout(30000)
      val input = connection.getInputStream
      copy(input, output)
    })
  }

  protected def uploadFromFile(url: URL, name: String, file: File): Boolean = {
    val input =
      try {
        new FileInputStream(file)
      } catch {
        case e: IOException =>
          log.error(s"Can't open ${file}", e)
          return false
      }
    try {
      upload(url, name, file.getName, input)
    } finally {
      input.close()
    }
  }

  protected def uploadFromJson(url: URL, name: String, destinationFile: String, json: JsValue): Boolean = {
    val content = json.sortedPrint
    val input = new ByteArrayInputStream(content.getBytes("utf8"))
    upload(url, name, destinationFile, input)
  }

  protected def upload(url: URL, name: String, destinationFile: String, input: InputStream): Boolean = {
    if (log.isDebugEnabled) log.debug(s"Upload by url ${url}")
    val CRLF = "\r\n"
    val boundary = System.currentTimeMillis.toHexString
    executeRequest(url, (connection: HttpURLConnection) => {
      if (!url.getUserInfo.isEmpty) {
        val encoded = Base64.getEncoder.encodeToString((url.getUserInfo).getBytes(StandardCharsets.UTF_8))
        connection.setRequestProperty("Authorization", "Basic " + encoded)
      }
      connection.setChunkedStreamingMode(0)
      connection.setConnectTimeout(10000)
      connection.setReadTimeout(30000)
      connection.setDoOutput(true)
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
      val output = connection.getOutputStream
      val writer = new PrintWriter(new OutputStreamWriter(output, "utf8"), true)

      writer.append("--" + boundary).append(CRLF)
      writer.append(s"Content-Type: application/octet-stream").append(CRLF)
      writer.append(f"""Content-Disposition: form-data; name="${name}"; filename="${destinationFile}"""").append(CRLF)
      writer.append(CRLF).flush

      copy(input, output)

      writer.append(CRLF).flush
      writer.append("--" + boundary + "--").append(CRLF).flush()
    })
  }

  protected def makeUrl(path: String): URL = {
    new URL(url.toString + "/" + path)
  }

  private def copy(in: InputStream, out: OutputStream): Unit = {
    val buffer = new Array[Byte](1024)
    var len = in.read(buffer)
    while (len > 0) {
      out.write(buffer, 0, len)
      out.flush
      len = in.read(buffer)
    }
  }

  @tailrec
  private def executeRequest(url: URL, request: (HttpURLConnection) => Unit): Boolean = {
    if (log.isDebugEnabled) log.debug(s"Make request to ${url}")
    val connection =
      try {
        url.openConnection().asInstanceOf[HttpURLConnection]
      } catch {
        case e: IOException =>
          log.error(s"Can't open connection to URL ${url}, error ${e.getMessage}")
          return false
      }
    val responseCode = try {
      request(connection)
      connection.getResponseCode
    } catch {
      case e: IOException =>
        log.error(s"Error: ${e.getMessage}")
        try {
          connection.getResponseCode
        } catch {
          case _: IOException =>
            return false
        }
    } finally {
      try {
        val responseCode = connection.getResponseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
          log.error(s"Request: ${connection.getRequestMethod} ${url}")
          try {
            log.error(s"Response message: ${connection.getResponseMessage}")
          } catch {
            case _: IOException =>
          }
          try {
            val errorStream = connection.getErrorStream()
            if (errorStream != null) log.error("Response error: " + new String(errorStream.readAllBytes(), "utf8"))
          } catch {
            case _: IOException =>
          }
        }
      } catch {
        case _: IOException =>
      }
      connection.disconnect()
    }
    if (responseCode == 423) {
      if (log.isDebugEnabled) log.debug("The resource that is being accessed is locked. Retry request after pause.")
      Thread.sleep(1000)
      executeRequest(url, request)
    } else {
      responseCode == HttpURLConnection.HTTP_OK
    }
  }
}
