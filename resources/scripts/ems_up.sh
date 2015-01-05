#!/bin/sh

# sleep for number of configured seconds
/bin/sleep $1

# assume primary role
sudo /usr/sbin/drbdadm primary --force emsdata

# mount the filesystem
sudo mount -t ext4 -o noatime /dev/drbd0 /mnt/shm0

# after mounting we need to make user ec2-user owner
sudo chown -R ec2-user /mnt/shm0

# start ems
/opt/tibco/ems/8.0/bin/tibemsd64 -config "/opt/tibco/cfgmgmt/ems/data/tibemsd.conf" &

# get the ems pid
emspid=$!

# increase EMS priority to real-time -18
sudo renice -n -18 ${emspid}

# save the pid to a file
scriptdir=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
echo ${emspid} > ${scriptdir}/ems.pid

# wait for EMS to complete
wait
