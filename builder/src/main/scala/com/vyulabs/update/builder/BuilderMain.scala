package com.vyulabs.update.builder

import com.vyulabs.update.builder.config.BuilderConfig
import com.vyulabs.update.common.Common.{ServiceName}
import com.vyulabs.update.common.com.vyulabs.common.utils.Arguments
import com.vyulabs.update.distribution.{DistributionDirectoryClient}
import com.vyulabs.update.lock.SmartFilesLocker
import com.vyulabs.update.utils.{Utils}
import com.vyulabs.update.version.DeveloperDistributionVersion
import org.slf4j.LoggerFactory

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 21.02.19.
  * Copyright FanDate, Inc.
  */
object BuilderMain extends App {
  implicit val log = LoggerFactory.getLogger(this.getClass)
  implicit val filesLocker = new SmartFilesLocker()

  def usage(): String =
    "Use: buildVersion <author=value> <service=value> [client=value]\n" +
    "                 [version=value] [comment=value] [sourceBranches=value1,value2,...] [setDesiredVersion=true]\n" +
    "   getDesiredVersions [clientName=value]\n" +
    "   setDesiredVersions [clientName=value] [services=<service[:version]>,[service1[:version1]],...]"

  if (args.size < 1) {
    Utils.error(usage())
  }

  val command = args(0)
  if (command != "buildVersion" && command != "getDesiredVersions" && command != "setDesiredVersions") {
    Utils.error(usage())
  }
  val arguments = Arguments.parse(args.drop(1))

  val config = BuilderConfig().getOrElse {
    Utils.error("No config")
  }

  val developerDistribution = new DistributionDirectoryClient(config.developerDistributionUrl)

  command match {
    case "buildVersion" =>
      val author: String = arguments.getValue("author")
      val serviceName: ServiceName = arguments.getValue("service")
      val comment: Option[String] = arguments.getOptionValue("comment")
      val version = arguments.getOptionValue("version").map(DeveloperDistributionVersion.parse(_))
      val sourceBranches = arguments.getOptionValue("sourceBranches").map(_.split(",").toSeq).getOrElse(Seq.empty)
      val setDesiredVersion = arguments.getOptionBooleanValue("setDesiredVersion").getOrElse(true)

      log.info(s"Make new version of service ${serviceName}")
      val builder = new Builder(developerDistribution, config.adminRepositoryUrl)
      builder.makeVersion(author, serviceName, comment, version, sourceBranches) match {
        case Some(version) =>
          if (setDesiredVersion) {
            /* TODO graphql
            val waitFor = (if (serviceName == Common.DistributionServiceName) {
              Some(developerDistribution.getDistributionVersionPath)
            } else if (serviceName == Common.ScriptsServiceName) {
              Some(developerDistribution.getScriptsVersionPath)
            } else {
              None
            }).map(path => (path, developerDistribution.getServerVersion(path)))
              .filter { case (_, v) => v.isDefined && v.get.client == version.client }
              .map(_._1)
            builder.setDesiredVersions(version.client, Map(serviceName -> Some(version)))
            waitFor.foreach(path => {
              log.info("Update distribution server")
              if (!developerDistribution.waitForServerUpdated(path, version)) {
                log.error("Can't update distribution server")
              }
            })*/
          }
        case None =>
          log.error("Make version error")
          System.exit(1)
      }
    case "getDesiredVersions" =>
      new Builder(developerDistribution, config.adminRepositoryUrl).getDesiredVersions() match {
        case Some(versions) =>
          log.info("Desired versions:")
          versions.foreach { case (serviceName, version) => log.info(s"  ${serviceName} ${version}") }
        case None =>
          Utils.error("Get desired versions error")
      }
    case "setDesiredVersions" =>
      var servicesVersions = Map.empty[ServiceName, Option[DeveloperDistributionVersion]]

      for (services <- arguments.getOptionValue("services")) {
        val servicesRecords: Seq[String] = services.split(",")
        for (record <- servicesRecords) {
          val fields = record.split(":")
          if (fields.size == 2) {
            val version = if (fields(1) != "-") {
              Some(DeveloperDistributionVersion.parse(fields(1)))
            } else {
              None
            }
            servicesVersions += (fields(0) -> version)
          } else {
            Utils.error(s"Invalid service record ${record}")
          }
        }
      }

      if (!new Builder(developerDistribution, config.adminRepositoryUrl).setDesiredVersions(servicesVersions)) {
        Utils.error("Set desired versions error")
      }

      /* TODO graphql
      servicesVersions.get(Common.DistributionServiceName) match {
        case Some(Some(distributionVersion)) =>
          log.info("Update distribution server")
          for (version <- developerDistribution.getServerVersion(developerDistribution.getDistributionVersionPath)) {
            if (version.client == distributionVersion.client) {
              if (!developerDistribution.waitForServerUpdated(developerDistribution.getDistributionVersionPath, distributionVersion)) {
                log.error("Can't update distribution server")
              }
            }
          }
        case _ =>
          servicesVersions.get(Common.ScriptsServiceName) match {
            case Some(Some(scriptsVersion)) =>
              log.info("Update distribution server scripts")
              for (version <- developerDistribution.getServerVersion(developerDistribution.getScriptsVersionPath)) {
                if (version.client == scriptsVersion.client) {
                  if (!developerDistribution.waitForServerUpdated(developerDistribution.getScriptsVersionPath, scriptsVersion)) {
                    log.error("Can't update distribution server scripts")
                  }
                }
              }
            case _ =>
          }
      }*/
    case command =>
      Utils.error(s"Invalid command ${command}\n${usage()}")
  }
}