## Project 3/Distributed Identity Service

* Authors: Everest K C, Jake Forsberg
* Class: CS555 [Distributed Systems]
* Semseter: Spring, Year: 2023

## Overview

The project implements a replicatable identity server and a client in Kotlin. The project uses redis to store and persist our data, as well as a combination of other services such as Traefik reverse-proxy and etcd key-value store for elasticity and scalability of our service. We are using Redis Sentinel Cluster as it provides fault tolerance and high availability for Redis.

## Link to Demo Video
https://youtu.be/7FKO9pD3ZAA

## Manifest

`certificates` directory containing the self signed certificate and the private key used to sign the certificate.<br/>
`certificates/certificate.cer` self signed certificate for the server.<br/>
`certificates/mykey.pem.pkcs8` private key used to sign the certificate.<br/>
`docker` directory containing the docker-compose configuration files for our dependencies and the volume and configurations for our Redis Sentinels.<br/>
`docker/prometheus/prometheus.yml` Prometheus configuration file.<br/>
`docker/sentinel` directory containing the three Redis Sentinel configuration files.<br/>
`docker/docker-compose-monitoring.yml` docker-compose configuration file for Prometheus/Grafana.<br/>
`docker/docker-compose.yaml` main docker-compose configuration file.<br/>
`identity-service` directory containing the kotlin source codes, protocol buffer files, kotlin and java libraries, and gradle build files required for the project.<br/>
`identity-service/src` directory containing the kotlin source codes, and protocol buffer files.<br/>
`identity-service/build` directory containing the libraries and binary files generated after the build process.<br/>
`identity-service/build.gradle.kts` gradle build file containing the steps required to fetch the necessary libraries and build the project.<br/>
`Makefile` file containing build steps to build the project.<br/>
`infra.sh` shell script used to start the necessary docker infrastructure: Redis/Traefik/etcd.<br/>
`populate_etcd.sh` shell script used to set up the traefik routing with etcd.<br/>
`runservice.sh` shell script used to run the identity server and identity client.<br/>
`setup.sh` shell script combining infra.sh and populate_etcd.sh.<br/>

## Building the project

Below mentioned steps should be followed to build the project:
1. Clone the code repository from the github
```sh
git@github.com:cs455-spring2023/project-3-replicated-identity-server-bestteam.git
```

2. Change directory to project-3-replicated-identity-server-bestteam
```sh
cd project-3-replicated-identity-server-bestteam
```

3. Build the Server and Client
```sh
make clean
make all
```
executables files are generated.

## Usage
1. Run the setup script
./setup.sh 
```sh
./setup.sh 
```

1. Run the server   
./runservice.sh server \<port>
```sh
./runservice.sh server 5001
```

2. Run the client   
./runservice.sh client \<enable_ssl> \<host> \<port> \<command> \<arguments>
```sh
./runservice.sh client true localhost 5001 create jke Jake strongpassword
```

3. You get the chat prompt, where you can use the following commands:

| __Command__ | __Description__ |
|-------------|-----------------|
| create \<loginName> \<realName> \<password>| Creates a user entry with the given login name and real name.|
| modify \<oldLoginName> \<newLoginName> \<password>| Changes the user’s login name from the old to the new. |
| delete \<loginName> \<password> | Deletes the entry of the user with the given login name. |
| lookup \<loginName> | Retrieves the user entry with the specified login name. |
| reverseLookup <userID> | Retrieves the entry of user with the ID passed in. |
| listLogins | Returns a list of login names for all user entries. |
| listIds | Returns a list of user IDs for all user entries. |
| listAllInfo | Returns the complete entry for every user. |

## Assumptions
For ease of work in our project we have made following assumptions:<br/>
1. The real name to be supplied to the create command cannot be multiple string literals seperated by space.<br/>
2. All other services run in the internal network and only the reverse proxy is accesible to the client, and thus we have only encrypted the traffic between the client and the reverse proxy. The TLS is terminated at the reverse proxy, meaning any communication further done by the reverse proxy our backend server is not encrypted.<br/>
3. We believe that the Redis Server should not be killed when the Identity Server is shutdown.<br/>
4. Docker containers are used to depict different nodes. The docker containers for now run in a single node, and if the node fails our service shall fail too.

## Implementation
Our project utilized the powerful jBCrypt library, a Java implementation of OpenBSD's Blowfish password hashing code. This enabled us to accurately manage the hash and salt aspects of password storage.<br/>
To store our data, we relied on Redis cluster, an efficient in-memory Key Value store, run with Sentinel. We also leveraged Redis' AOF (Append Only File) functionality to ensure that data persistence was achieved. The Redisson library was used to interact with the Redis Server, providing a reliable and user-friendly interface.<br/>
To achieve consistency in our data, we relied on Redisson's Distributed Read Write Locks mechanism, which allowed us to ensure that data integrity was maintained even in complex situations. We have also used Sentinel's `min-slaves-to-write` and `min-slaves-max-lag` options to ensure consistency of our data.<br/>
We have used several out of the box solutions in our project which are as follows:<br/>
- Traefik : Reverse proxy for load balancing between our backend servers and for fault tolerance of our backend server.
- etcd : Key Value store for Service Registation and as a dynamic source of configuration for Traefik.
- Redis Sentinel: Cluster management for Redis.
- Prometheus: Metrics aggeregrator and data source for grafana.
- Grafana: Visualization and monitoring of different services.
- Node Exporter: Node data source for Grafana.

## Testing
Our team has taken a thorough approach to testing our Kotlin code by writing several unit tests using Kotlin tests. For each method, we have created a dedicated test that not only verifies correct input, but also tests for potential issues that could potentially break the code. For instance, when testing the create method, which is responsible for user creation, we have designed tests that successfully create a user, as well as tests that cover user creation failures due to duplicate login names.<br/>
We also have a test function that runs multiple write and read operations concurrently to ensure that no write can happen during a read and vice versa.<br/>
In addition to our unit tests, we have taken the extra step of running manual tests to verify the encryption of our application's traffic. For this purpose, we have utilized the tcpdump tool to capture network traffic, ensuring that sensitive information is properly protected. To make this process more accessible to others, we have included a detailed demonstration of our encryption test in a video.<br/>
We also performed the tests for loadbalancing, elasticity, scalability, fault tolerance and high availability which is covered in the video.

## Known Issues
1. Traefik reverse can be the single point of failure for our Identity Service. In real case scenario, this can improved by have multiple reverse proxies that have the same FQDN and are pointed by DNS, or we can also replace the reverse proxy with some advance DNS that allows health checks and load balancing.</br>
2. Though it is self evident that use of Sentinel allows the fault tolerance of Redis, we forgot to show that in the video. The fault tolerance has been properly tested during the testing of our project. 

## Extra Credit Attempt
1. Load Balancing using Round-Robin Algorithm with Traefik reverse proxy.
<img width="1305" alt="Screenshot 2023-05-01 at 3 06 40 AM" src="https://user-images.githubusercontent.com/112115864/235432887-c3ea10a5-8a3a-4269-979a-dbbb2197bdc2.png">
2. Monitoring the services with Prometheus, Node Exporter and Grafana.
<img width="1675" alt="Screenshot 2023-05-01 at 12 06 15 AM" src="https://user-images.githubusercontent.com/112115864/235432670-b9321f52-6f15-4c83-8fc5-9b07ad9d31bf.png">
<img width="1673" alt="Screenshot 2023-05-01 at 12 06 58 AM" src="https://user-images.githubusercontent.com/112115864/235432707-86cfc9a2-7709-44f6-bf6b-6cc4b0701b64.png">

## Reflection and Self Assessment
This project was really fun to brainstrom and work on, but at the same time it was hectic and painful too. The decision to use Traefik reverse proxy came with a lot of complexities. Since, the stable version of Traefik didn't support loadbalancing of GRPC we had to use beta version of Traefik. The documentation of Traefik wasn't good enough, so setting up of TLS was extremely boggling.</br>
The docker desktop for mac didn't show up docker0 interface, so we had to use `docker-mac-net-connect` to allow traffic forwarding from host to docker network. It took us quite someime to figure it out.</br>
We did learn a lot while working on this project. Also, we thoroughly enjoyed working on this project, although we had sleepless nights.</br>

**Development Process / Role**   
Throughout this project, we leveraged the power of peer-programming to achieve our goals. Our team worked collaboratively on both the server-side and client-side code, as well as the various tests required for the project.<br/>
Code review was a critical aspect of our peer-programming approach. We took the time to review each other's work thoroughly, providing feedback and suggestions where necessary. This allowed us to identify and address any issues early on in the development process, ensuring that our code was of the highest quality.
