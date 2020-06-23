#!/bin/bash -e

function exitUsage() {
  echo "Use: $0 name services distribDirectoryUrl"
  exit 1
}

if [ -z "$3" ]; then
  exitUsage
fi

name=$1
services=$2
distribDirectoryUrl=$3

serviceToSetup=updater
. update.sh

echo "Execute setup"
./updater_setup.sh Azure ${name} ${services} ${distribDirectoryUrl}