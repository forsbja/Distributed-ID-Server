#!/bin/bash
export IDENTITY_SERVER_CERT=./certificates/certificate.cer
export IDENTITY_SERVER_PRIVATE_KEY=./certificates/mykey.pem.pkcs8

export SENTINEL1='redis://127.0.0.1:26379'
export SENTINEL2='redis://127.0.0.1:26380'
export SENTINEL3='redis://127.0.0.1:26381'

etcd1=localhost:2379
etcd2=localhost:2380
etcd3=localhost:2381

if [ "$1" = 'server' ];then
    url_no=`./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" get "" --prefix=true|grep "traefik/http/services/identity/loadbalancer/servers/[0-9]*/url"|sort -u|tail -1|awk 'BEGIN { FS = "/" } ; { print $7 }'`
    if [ -z "$url_no" ];then
        new_url_no=0
    else
        new_url_no=$(($url_no+1))
    fi
    key="traefik/http/services/identity/loadbalancer/servers/$new_url_no/url"
    host="docker.for.mac.host.internal"
    value="h2c://$host:$2"
    ./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put "$key" "$value"
fi
java -jar ./identity-service/build/libs/IdentityService.jar "$@"


