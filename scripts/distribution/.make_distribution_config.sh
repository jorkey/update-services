#!/bin/bash -e
set -e

function exitUsage() {
  >&2 echo "Use: $0 <cloudProvider> <distributionName> <distributionTitle> <mongoDbName> <mongoDbTemporary> <port>"
  exit 1
}

if [ $# -ne 6 ]; then
  exitUsage
fi

cloudProvider=$1
distributionName=$2
distributionTitle=$3
mongoDbName=$4
mongoDbTemporary=$5
port=$6

jwtSecret=`openssl rand -base64 32`

if [ "${cloudProvider}" == "Azure" ]; then
  if ! instanceId=`curl --silent -H "Metadata: True" http://169.254.169.254/metadata/instance?api-version=2019-06-01 | jq -rj '.compute.resourceGroupName, ":", .compute.name'`; then
    >&2 echo "Can't get instance Id"
    exit 1
  fi
elif [ "${cloudProvider}" == "None" ]; then
  instanceId=none
else
  >&2 echo "Invalid cloud provider ${cloudProvider}"
  exit 1
fi

jq ".distributionName=\"${distributionName}\" | .title=\"${distributionTitle}\" | .instanceId=\"${instanceId}\" | .jwtSecret=\"${jwtSecret}\" | .mongoDb.name=\"${mongoDbName}\" | .mongoDb.temporary=${mongoDbTemporary} | .network.port=${port}" >distribution.json <<EOF
{
  "distributionName": "undefined",
  "title": "undefined",
  "instanceId": "undefined",
  "jwtSecret": "undefined",
  "mongoDb" : {
    "connection" : "mongodb://localhost:27017",
    "name": "undefined",
    "test": false
  },
  "network": {
    "port" : 8000
  },
  "versions": {
    "maxHistorySize": 100
  },
  "instanceState": {
    "expirationTimeout": { "length": 60, "unit": "SECONDS" }
  },
  "faultReports": {
    "expirationTimeout": { "length": 7, "unit": "DAYS" },
    "maxReportsCount": 100
  }
}
EOF