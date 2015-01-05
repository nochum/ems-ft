#!/bin/sh

# kill EMS
scriptdir=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
pidfile=${scriptdir}/ems.pid
kill $(cat ${pidfile})

# unmount the filesystem
sudo umount /dev/drbd0

# assume secondary role
sudo /usr/sbin/drbdadm secondary emsdata
