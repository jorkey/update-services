package com.vyulabs.update.distribution.mongo

import com.vyulabs.update.common.common.Common.DistributionName
import com.vyulabs.update.common.config.{DistributionClientInfo, DistributionClientProfile}
import com.vyulabs.update.common.info.{ClientDesiredVersion, ClientVersionInfo, DeveloperDesiredVersion, DeveloperVersionInfo, DistributionFaultReport, DistributionServiceState, ServiceLogLine, TestedDesiredVersions}
import com.vyulabs.update.distribution.users.ServerUserInfo

case class SequenceDocument(name: String, sequence: Long)

case class UserInfoDocument(info: ServerUserInfo)

case class DistributionClientInfoDocument(info: DistributionClientInfo)
case class DistributionClientProfileDocument(profile: DistributionClientProfile)

case class DeveloperVersionInfoDocument(_id: Long, info: DeveloperVersionInfo)
case class ClientVersionInfoDocument(_id: Long, info: ClientVersionInfo)

case class DeveloperDesiredVersionsDocument(versions: Seq[DeveloperDesiredVersion], _id: Long = 0)
case class ClientDesiredVersionsDocument(versions: Seq[ClientDesiredVersion], _id: Long = 0)

case class InstalledDesiredVersionsDocument(distributionName: DistributionName, versions: Seq[ClientDesiredVersion])
case class TestedDesiredVersionsDocument(versions: TestedDesiredVersions)

case class ServiceStateDocument(sequence: Long, state: DistributionServiceState)
case class ServiceLogLineDocument(_id: Long, line: ServiceLogLine)
case class FaultReportDocument(_id: Long, fault: DistributionFaultReport)

case class UploadStatus(lastUploadSequence: Option[Long], lastError: Option[String])
case class UploadStatusDocument(component: String, status: UploadStatus)
