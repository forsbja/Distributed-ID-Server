#!/bin/bash
etcd1=localhost:2379
etcd2=localhost:2380
etcd3=localhost:2381
./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put traefik/http/routers/identity/entryPoints/0 http
./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put traefik/http/routers/identity/rule PathPrefix\(\`/\`\)
./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put traefik/http/routers/identity/service identity
./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put traefik/http/services/identity/loadbalancer/healthcheck/mode grpc

./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put traefik/http/routers/identitytls/entryPoints/0 https
./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put traefik/http/routers/identitytls/rule PathPrefix\(\`/\`\)
./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put traefik/http/routers/identitytls/service identity
./etcdctl --endpoints="$etcd1","$etcd2","$etcd3" put traefik/http/routers/identitytls/tls/options default