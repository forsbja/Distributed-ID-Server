#!/bin/bash
cd ./docker
if [ "$1" = "start" ];then
    docker-compose up -d
    docker-compose -f docker-compose-monitoring.yml up -d
elif [ "$1" = "stop" ];then
    docker-compose down
    docker-compose -f docker-compose-monitoring.yml down
else
    "Invalid argument!"
fi
cd ..