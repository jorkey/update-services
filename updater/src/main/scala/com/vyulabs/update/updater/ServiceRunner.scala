package com.vyulabs.update.updater

import com.vyulabs.update.common.common.Common.InstanceId
import com.vyulabs.update.common.common.Timer
import com.vyulabs.update.common.config.{InstallConfig, RunServiceConfig}
import com.vyulabs.update.common.info.{FaultInfo, ProfiledServiceName}
import com.vyulabs.update.common.logs.LogWriter
import com.vyulabs.update.common.process.{ChildProcess, ProcessMonitor}
import com.vyulabs.update.common.utils.{IoUtils, Utils}
import com.vyulabs.update.common.version.{DeveloperDistributionVersion, DeveloperVersion}
import com.vyulabs.update.updater.uploaders.FaultUploader
import org.slf4j.Logger

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

/**
  * Created by Andrei Kaplanov (akaplanov@vyulabs.com) on 17.04.19.
  * Copyright FanDate, Inc.
  */
class ServiceRunner(config: RunServiceConfig, parameters: Map[String, String], directory: File, instanceId: InstanceId,
                    profiledServiceName: ProfiledServiceName, state: ServiceStateController, faultUploader: FaultUploader)
                   (implicit log: Logger, timer: Timer, executionContext: ExecutionContext) {
  private val maxLogHistoryDirCapacity = 5L * 1000 * 1000 * 1000

  @volatile private var currentProcess = Option.empty[ChildProcess]
  private var lastStartTime = 0L

  def startService(): Boolean = {
    if (currentProcess.isDefined) {
      state.error("Service process is already started")
      false
    } else {
      val command = Utils.extendMacro(config.command, parameters)
      val arguments = config.args.getOrElse(Seq.empty).map(Utils.extendMacro(_, parameters))
      val env = config.env.getOrElse(Map.empty).mapValues(Utils.extendMacro(_, parameters))
      val logWriter = config.logWriter.map { logWriterConfig =>
        new LogWriter(new File(state.currentServiceDirectory, logWriterConfig.directory),
          logWriterConfig.maxFileSizeMB * 1024 * 1024,
          logWriterConfig.maxFilesCount,
          logWriterConfig.filePrefix,
          (message, exception) => state.error(message, exception))
      }
      val onOutputLines = logWriter.map { logWriter =>
        val dateFormat = config.logWriter.get.dateFormat.map(new SimpleDateFormat(_))
        (lines: Seq[(String, Boolean)]) => lines.foreach { case (line, nl) => {
          val formattedLine = dateFormat match {
            case Some(dateFormat) =>
              s"${dateFormat.format(new Date)} ${line}"
            case None =>
              line
          }
          logWriter.writeLogLine(formattedLine)
        }}
      }.getOrElse((_: Seq[(String, Boolean)]) => ())
      ChildProcess.start(command, arguments, env, directory, onOutputLines).onComplete {
        case Success(process) =>
          currentProcess = Some(process)
          lastStartTime = System.currentTimeMillis()
          for (restartConditions <- config.restartConditions) {
            new ProcessMonitor(process, restartConditions).start()
          }
          process.onTermination().onComplete {
            case result =>
              if (currentProcess.contains(process)) {
                result match {
                  case Success(exitCode) =>
                    val logTail = logWriter.map { logWriter =>
                      val logTail = logWriter.getLogTail()
                      logWriter.close()
                      logTail
                    }.getOrElse(Queue.empty)
                    log.info(s"Process fault of service process ${process.getHandle().pid()}")
                    processFault(exitCode, logTail)
                  case Failure(ex) =>
                    log.error(s"Waiting for process termination error", ex)
                }
                currentProcess = None
              } else {
                log.warn(s"Process ${process.getHandle().pid()} terminated, but current process is ${currentProcess.map(_.getHandle().pid())}")
              }
          }
        case Failure(ex) =>
          log.error(s"Can't start process ${command}", ex)
      }
      true
    }
  }

  def stopService(): Boolean = {
    currentProcess match {
      case Some(process) =>
        try {
          Await.result(process.terminate(), FiniteDuration(30, TimeUnit.SECONDS))
        } catch {
          case _: Exception =>
            false
        }
      case None =>
        true
    }
  }

  def saveLogs(failed: Boolean): Unit = {
    for (currentInstallConfig <- InstallConfig.read(state.currentServiceDirectory)) {
      for (logDirectory <- currentInstallConfig.runService.map(_.logWriter.map(_.directory).map(new File(state.currentServiceDirectory, _))).flatten) {
        state.info(s"Save log files to history directory")
        if (state.logHistoryDirectory.exists() || state.logHistoryDirectory.mkdir()) {
          val dirName = state.getVersion().getOrElse(DeveloperDistributionVersion("???", DeveloperVersion.empty)).toString + s"-${Utils.serializeISO8601Date(new Date())}" +
            (if (failed) "-failed" else "")
          val saveDir = new File(state.logHistoryDirectory, s"${dirName}.log")
          if (!saveDir.exists() || IoUtils.deleteFileRecursively(saveDir)) {
            if (logDirectory.exists()) {
              if (!logDirectory.renameTo(saveDir)) {
                log.error(s"Can't rename ${logDirectory} to ${saveDir}")
              }
            } else {
              log.error(s"Log directory ${logDirectory} not exist")
            }
          } else {
            log.error(s"Can't delete ${saveDir}")
          }
          IoUtils.maybeFreeSpace(state.logHistoryDirectory, maxLogHistoryDirCapacity, Set.empty)
        } else {
          state.error(s"Can't make directory ${state.logHistoryDirectory}")
        }
      }
    }
  }

  private def processFault(exitCode: Int, logTail: Queue[String]): Unit = {
    state.failure(exitCode)
    saveLogs(true)
    val pattern = config.faultFilesMatch.getOrElse("core")
    val regPattern = Utils.extendMacro(pattern, parameters).r
    val files = directory.listFiles().filter { file =>
      file.getName match {
        case regPattern() => true
        case _ => false
      }
    }
    val reportFilesTmpDir = if (!files.isEmpty) {
      val reportTmpDir = Files.createTempDirectory(s"${profiledServiceName}-fault-").toFile
      for (file <- files) {
        val tmpFile = new File(reportTmpDir, file.getName)
        if (!file.renameTo(tmpFile)) log.error(s"Can't rename ${file} to ${tmpFile}")
      }
      Some(reportTmpDir)
    } else {
      None
    }
    val info = FaultInfo(new Date(),  instanceId,
      new java.io.File(".").getCanonicalPath(), profiledServiceName.name, profiledServiceName.profile, state.getState(), logTail)
    faultUploader.addFaultReport(info, reportFilesTmpDir)
    val restartOnFault = config.restartOnFault.getOrElse(true)
    if (restartOnFault) {
      state.info("Try to restart service")
      val period = System.currentTimeMillis() - lastStartTime
      if (period < 1000) {
        Thread.sleep(1000 - period)
      }
      if (!startService()) {
        state.error("Can't restart service")
      }
    } else {
      state.info(s"Service is failed")
    }
  }
}
