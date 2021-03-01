package com.vyulabs.update.updater

import com.vyulabs.update.common.common.Common
import com.vyulabs.update.common.common.Common.ServiceName
import com.vyulabs.update.common.distribution.client.{DistributionClient, SyncDistributionClient, SyncSource}
import com.vyulabs.update.common.utils.{IoUtils, Utils}
import com.vyulabs.update.common.version.ClientDistributionVersion
import org.slf4j.Logger

import java.io.File
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 16.01.19.
  * Copyright FanDate, Inc.
  */
class SelfUpdater(state: ServiceStateController, distributionClient: DistributionClient[SyncSource])
                 (implicit executionContext: ExecutionContext, log: Logger) {
  private val syncDistributionClient = new SyncDistributionClient[SyncSource](distributionClient, FiniteDuration(60, TimeUnit.SECONDS))

  private val scriptsVersion = IoUtils.readServiceVersion(Common.ScriptsServiceName, new File("."))
  private val updaterVersion = IoUtils.readServiceVersion(Common.UpdaterServiceName, new File("."))

  state.serviceStarted()

  def needUpdate(serviceName: ServiceName, desiredVersion: Option[ClientDistributionVersion]): Option[ClientDistributionVersion] = {
    Utils.isServiceNeedUpdate(serviceName, getVersion(serviceName), desiredVersion)
  }

  def stop(): Unit = {
    state.serviceStopped()
  }

  def beginServiceUpdate(serviceName: ServiceName, toVersion: ClientDistributionVersion): Boolean = {
    log.info(s"Service ${serviceName} is obsolete. Own version ${getVersion(serviceName)} desired version ${toVersion}")
    state.beginUpdateToVersion(toVersion)
    log.info(s"Downloading ${serviceName} of version ${toVersion}")
    if (!syncDistributionClient.downloadClientVersionImage(serviceName, toVersion, new File(Common.ServiceZipName.format(serviceName)))) {
      state.updateError(false, s"Downloading ${serviceName} error")
      return false
    }
    if (!IoUtils.writeServiceVersion(new File("."), serviceName, toVersion)) {
      state.updateError(true, s"Set ${serviceName} version error")
      return false
    }
    true
  }

  private def getVersion(serviceName: ServiceName): Option[ClientDistributionVersion] = {
    if (serviceName == Common.UpdaterServiceName) {
      updaterVersion
    } else if (serviceName == Common.ScriptsServiceName) {
      scriptsVersion
    } else {
      None
    }
  }
}