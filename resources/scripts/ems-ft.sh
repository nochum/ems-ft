#!/bin/sh

export CLASSPATH=.:/opt/tibco/emsft/ems-ft.jar:/opt/commons-exec-1.3/commons-exec-1.3.jar:/opt/guava/guava-16.0.1.jar:/opt/zookeeper-3.4.6/zookeeper-3.4.6.jar:/opt/curator/curator-client-2.7.0.jar:/opt/curator/curator-framework-2.7.0.jar:/opt/curator/curator-recipes-2.7.0.jar:/opt/libs/jline-0.9.94.jar:/opt/libs/junit-3.8.1.jar:/opt/libs/log4j-1.2.16.jar:/opt/libs/netty-3.7.0.Final.jar:/opt/libs/slf4j-api-1.7.6.jar

java -cp ${CLASSPATH} nochum.ems.utilities.EmsFt.EmsFtCurator /opt/tibco/emsft/ems-ft.properties

