import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider
import io.grpc.protobuf.services.ProtoReflectionService
import grpc.health.v1.*

import java.io.File
import java.lang.Exception
import kotlin.system.exitProcess
import kotlin.collections.emptyList
import java.util.concurrent.TimeUnit

import mu.KotlinLogging

import org.redisson.Redisson
import org.redisson.api.*
import org.redisson.config.Config
import org.redisson.config.*

import org.mindrot.jbcrypt.BCrypt;
import sun.misc.SignalHandler

object ENV {
    val CERT = File(System.getenv("IDENTITY_SERVER_CERT"))
    val PRIV = File(System.getenv("IDENTITY_SERVER_PRIVATE_KEY") ?: "")
}

class HealthServiceImpl: HealthGrpcKt.HealthCoroutineImplBase() {

    override suspend fun check(request: HealthOuterClass.HealthCheckRequest): HealthOuterClass.HealthCheckResponse {
        // Check if your service is healthy
        val status = if (isHealthy) {
            HealthOuterClass.HealthCheckResponse.ServingStatus.SERVING
        } else {
            HealthOuterClass.HealthCheckResponse.ServingStatus.NOT_SERVING
        }
        return HealthOuterClass.HealthCheckResponse.newBuilder()
            .setStatus(status)
            .build()
    }

    private val isHealthy: Boolean
        get() {
            // Check the health of your service
            return true
        }
}

class IdentityServer(
    port: Int,
    enableSsl: Boolean
): IdentityServiceGrpcKt.IdentityServiceCoroutineImplBase() {
    val svr = NettyServerBuilder.forPort(port)
        .sslContext(if (!enableSsl) null else GrpcSslContexts
            .forServer(ENV.CERT, ENV.PRIV, null)
            .sslProvider(SslProvider.OPENSSL)
            .clientAuth(ClientAuth.NONE)
            .build()
        )
        .addService(this)
        .addService(HealthServiceImpl())
        .addService(ProtoReflectionService.newInstance())
        .build()

    object SENTINEL_ENV{
        val SENTINEL1 = System.getenv("SENTINEL1") ?: ""
        val SENTINEL2 = System.getenv("SENTINEL2") ?: ""
        val SENTINEL3 = System.getenv("SENTINEL3") ?: ""
    }

    private val logger = KotlinLogging.logger {}

    fun start() {
        logger.info {"running grpc server with reflection."}
        svr.start()
        svr.awaitTermination()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                this@IdentityServer.stop()
            }
        )
    }

    fun stop() { 
        rclient.shutdown()
        svr.shutdown() 
    }

    val rclient = getRedisClient()

    fun getRedisClient():RedissonClient{
        val config: Config = Config()
        config.useSentinelServers()
        .setMasterName("my_redis_master")
        .addSentinelAddress(SENTINEL_ENV.SENTINEL1,SENTINEL_ENV.SENTINEL2,SENTINEL_ENV.SENTINEL3)
        .setPassword("password")
        .setReadMode(ReadMode.SLAVE)
        return Redisson.create(config)
    }

    fun hashPassword(password: String): String {
        return BCrypt.hashpw(password, BCrypt.gensalt())
    }

    fun verifyPassword(password: String, hashedPassword: String): Boolean {
        return BCrypt.checkpw(password, hashedPassword)
    }

    fun clearDB() {
        try {
            rclient.getKeys().flushdb()
            logger.info{"Successfully flushed DB"}
        } catch (e: Exception) {
            logger.error{e}
        }
    }

    override suspend fun createUser(request:CreateUserRequest): CreateUserResponse{
        var loginName = "Login:" + request.loginName
        var realName = request.realName
        var password = request.password
        var user_Id:String = ""

        val lock = rclient.getReadWriteLock("Lock:" + loginName).writeLock()

        val locked = try {
            lock.tryLock(10, 10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }

        if(!locked) {
            logger.error("Could not acquire locks to $loginName")
            return createUserResponse { success = false; message = "Could not acquire locks"}
        }

        val transaction = rclient.createTransaction(TransactionOptions.defaults())
        try{
            val bucket: RBucket<String> = transaction.getBucket(loginName)
            var old_user_Id = bucket.get()
            if(old_user_Id != null){
                logger.error{"User already exists."}
                return createUserResponse{success=false;userId="-1";message="User already exists."}
            }
            var counter:RAtomicLong = rclient.getAtomicLong("userCounter")
            user_Id = ""+counter.incrementAndGet()
            var hsetKey = "user:"+user_Id

            val mapLock = rclient.getReadWriteLock("Lock:" + hsetKey).writeLock()
            val mapLocked = try {
                mapLock.tryLock(10, 10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                false
            }
            if(!mapLocked) {
                logger.error("Could not acquire locks to $loginName")
                return createUserResponse { success = false; message = "Could not acquire locks"}
            }

            try {
                var map: RMap<String, String> = transaction.getMap(hsetKey)
                map.put("loginName", loginName.removePrefix("Login:"))
                map.put("realName", realName)
                map.put("password", hashPassword(password))

                bucket.set(user_Id)
            } finally {
                mapLock.unlock()
            }
            transaction.commit()
            logger.info{"User successfully created."}
        }
        catch(e: Exception){
            logger.error{e}
            transaction.rollback()
        } finally {
            lock.unlock()
        }
        return createUserResponse{ success = true; userId = user_Id; message = "User created successfully."}
    }

   override suspend fun deleteUser(request: DeleteUserRequest): DeleteUserResponse{
       var loginName = "Login:" + request.loginName
       var password = request.password

       val lock = rclient.getReadWriteLock("Lock:" + loginName).writeLock()

       val locked = try {
           lock.tryLock(10, 10, TimeUnit.SECONDS)
       } catch (e: InterruptedException) {
           false
       }

       if(!locked) {
           logger.error("Could not acquire locks to $loginName")
           return deleteUserResponse { success = false; message = "Could not acquire locks"}
       }

       val transaction = rclient.createTransaction(TransactionOptions.defaults())
        try{
            val bucket: RBucket<String> = transaction.getBucket(loginName)
            var user_Id = bucket.get()
            if(user_Id == null){

                logger.error{"User does not exist."}
                return deleteUserResponse{success = false; message = "User does not exist."}
            }else{
                var hsetKey="user:"+user_Id
                var map: RMap<String, String> = transaction.getMap(hsetKey)
                var hashedPassword = map.get("password").toString()
                if(verifyPassword(password, hashedPassword)){
                    bucket.delete()
                    map.delete()
                    transaction.commit()
                }else{
                    logger.error{"Password does not match."}
                    return deleteUserResponse{ success = false; message = "Password does not match."}
                }
            }
        }catch(e:Exception){
            logger.error{e}
            transaction.rollback()
        } finally {
            lock.unlock()
        }
        logger.info{"User successfully deleted."}
        return deleteUserResponse{success = true; message = "User deleted successfully."}
   }

    override suspend fun modifyUser(request: ModifyUserRequest): ModifyUserResponse {
        val oldLoginName = "Login:"+request.oldLoginName
        val newLoginName = "Login:"+request.newLoginName
        val password = request.password

        val lock1 = rclient.getReadWriteLock("Lock:"+oldLoginName).writeLock()
        val lock2 = rclient.getReadWriteLock("Lock:"+newLoginName).writeLock()

        val multiLock = rclient.getMultiLock(lock1, lock2)
        val locked = try {
            multiLock.tryLock(10, 10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }

        if(!locked) {
            logger.error("Could not acquire locks to $oldLoginName")
            return modifyUserResponse { success = false; message = "Could not acquire locks"}
        }

        val transaction = rclient.createTransaction(TransactionOptions.defaults())
        try {
            // Check Password
            val oldBucket: RBucket<String> = transaction.getBucket(oldLoginName)
            var user_Id = oldBucket.get()
            if (user_Id == null) {

               logger.error{"Could not find entry matching: $oldLoginName"}
                return modifyUserResponse { success = false; message = "Could not find entry matching: $oldLoginName" }
            }
            var hsetKey = "user:"+user_Id
            val mapLock = rclient.getReadWriteLock("Lock:"+hsetKey).writeLock()
            val mapLocked = try {
                mapLock.tryLock(10, 10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                false
            }
            if(!mapLocked) {
                logger.error("Could not acquire locks to $oldLoginName")
                return modifyUserResponse { success = false; message = "Could not acquire locks"}
            }

            try {
                var map: RMap<String, String> = transaction.getMap(hsetKey)
                if(!verifyPassword(password, map.get("password").toString())){

                    logger.error{"Incorrect password for: $oldLoginName"}
                    return modifyUserResponse { success = false; message = "Incorrect password for: $oldLoginName."}
                }

                val newBucket: RBucket<String> = transaction.getBucket(newLoginName)
                user_Id = newBucket.get()
                if (user_Id != null) { //Exit without modifying

                    logger.error{"Username $newLoginName already exists."}
                    return modifyUserResponse { success = false; message = "Username $newLoginName already exists." }
                }

                user_Id = oldBucket.getAndDelete()
                newBucket.set(user_Id)

                map.replace("loginName", newLoginName)

                transaction.commit()
            } finally {
                mapLock.unlock()
            }

        }catch (e: Exception){
            logger.error{e}
            transaction.rollback()
        } finally {
            multiLock.unlock()
        }
        logger.info{"Successfully changed login from $oldLoginName to $newLoginName"}
        return modifyUserResponse{ success = true; message = "Successfully changed login from $oldLoginName to $newLoginName" }
    }

    override suspend fun lookup(request: LookupUserRequest): LookupUserResponse {
        val loginName = "Login:" + request.loginName
        var user_Id = ""
        var real_name = ""

        val lock = rclient.getReadWriteLock("Lock:" + loginName).readLock()

        val locked = try {
            lock.tryLock(10, 10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }

        if(!locked) {
            logger.error("Could not acquire locks to $loginName")
            return lookupUserResponse { success = false; message = "Could not acquire locks"}
        }

        val transaction = rclient.createTransaction(TransactionOptions.defaults())

        try {
            val bucket: RBucket<String> = transaction.getBucket(loginName)
            var userId = bucket.get()
            if (userId == null) {
                logger.error{"Could not find entry matching login name."}
                return lookupUserResponse { success = false; userId = ""; realName = ""; message = "Could not find entry matching login name." }
            }
            user_Id = userId
            val hsetKey = "user:"+user_Id

            val mapLock = rclient.getReadWriteLock("Lock:"+hsetKey).readLock()
            val mapLocked = try {
                mapLock.tryLock(10, 10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                false
            }
            if(!mapLocked) {
                logger.error("Could not acquire locks to $loginName")
                return lookupUserResponse { success = false; message = "Could not acquire locks"}
            }
            try {
                var map: RMap<String, String> = transaction.getMap(hsetKey)
                real_name = map.get("realName").toString()
            } finally {
                mapLock.unlock()
            }
            transaction.commit()
        } catch (e: Exception) {
            logger.error{e}
            transaction.rollback()
        } finally {
            lock.unlock()
        }
        logger.info{"Lookup Successful."}
        return lookupUserResponse{ success = true; userId = user_Id; realName = real_name; message = "" }
    }

    override suspend fun listLogins(request: Empty): ListLoginsResponse{
        var loginNameList = mutableListOf<String>()
         try{
             val keys: Iterable<String> = rclient.getKeys().getKeysByPattern("Login:*")
             for (name in keys) {
                 loginNameList.add(name.removePrefix("Login:"))
             }
         }catch(e:Exception){
             logger.error{e}
         }
         logger.info{"List logins successful."}
         return ListLoginsResponse.newBuilder().addAllLoginNames(loginNameList).build()
    }

    override suspend fun reverseLookup(request: ReverseLookupUserRequest): ReverseLookupUserResponse {
        val user_Id = request.userId
        var login_name = ""
        var real_name = ""

        val hsetKey = "user:"+user_Id
        val lock = rclient.getReadWriteLock("Lock:" + hsetKey).readLock()

        val locked = try {
            lock.tryLock(10, 10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }

        if(!locked) {
            logger.error("Could not acquire locks to $user_Id")
            return reverseLookupUserResponse { success = false; message = "Could not acquire locks"}
        }

        val transaction = rclient.createTransaction(TransactionOptions.defaults())
        try {
            val map: RMap<String, String> = transaction.getMap(hsetKey)
            login_name = map.get("loginName").toString()
            if(login_name.equals("null")) {
                logger.error{"Could not find user with ID: $user_Id"}
                return reverseLookupUserResponse{ success = false; loginName = login_name; realName = real_name; message = "Could not find user with ID."}
            }
            real_name = map.get("realName").toString()
            transaction.commit()
        } catch (e: Exception) {
            transaction.rollback()
            logger.error{e}
        } finally {
            lock.unlock()
        }
        logger.info{"Reverse lookup successful."}
        return reverseLookupUserResponse{ success = true; loginName = login_name; realName = real_name; message = ""}
    }

    override suspend fun listIds(request: Empty): ListIdsResponse{
        var idList = mutableListOf<String>()

        val keys: Iterable<String> = rclient.getKeys().getKeysByPattern("Login:*")
        val locks = mutableListOf<RLock>()

        for (key in keys) {
            val lock = rclient.getReadWriteLock("Lock:" + key).readLock()
            locks.add(lock)
        }

        val multiLock = rclient.getMultiLock(*locks.toTypedArray())
        val locked = try {
            multiLock.tryLock(10, 10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if(!locked) {
            logger.error("Could not acquire locks")
            return ListIdsResponse.newBuilder().addAllUserIds(idList).build()
        }

        val transaction = rclient.createTransaction(TransactionOptions.defaults())
        try{
            for (name in keys) {
                val bucket: RBucket<String> = transaction.getBucket(name)
                idList.add(bucket.get())
            }
        }catch(e:Exception){
            logger.error{e}
            transaction.rollback()
        } finally {
            multiLock.unlock()
        }

        logger.info{"IDs successfully listed."}
        return ListIdsResponse.newBuilder().addAllUserIds(idList).build()
    }

    override suspend fun listAllInfo(request: Empty): ListAllInfoResponse {
        var userList = mutableListOf<UserEntry>()
        var idList = mutableListOf<String>()

        val keys: Iterable<String> = rclient.getKeys().getKeysByPattern("Login:*")
        val locks = mutableListOf<RLock>()

        for (key in keys) {
            val lock = rclient.getReadWriteLock("Lock:" + key).readLock()
            locks.add(lock)
        }

        val multiLock = rclient.getMultiLock(*locks.toTypedArray())
        val locked = try {
            multiLock.tryLock(10, 10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if(!locked) {
            logger.error("Could not acquire locks")
            return ListAllInfoResponse.newBuilder().addAllUserEntries(userList).build()
        }

        val transaction = rclient.createTransaction(TransactionOptions.defaults())
        try {
            for (name in keys) {
                val bucket: RBucket<String> = transaction.getBucket(name)
                idList.add(bucket.get())
            }
            for (i in idList){
                var hsetKey = "user:"+i
                var map: RMap<String, String> = transaction.getMap(hsetKey)
                userList.add(userEntry{userId = i; loginName = map.get("loginName").toString(); realName = map.get("realName").toString()})
            }
            transaction.commit()
        } catch (e: Exception) {
            logger.error{e}
            transaction.rollback()
        } finally {
            multiLock.unlock()
        }
        val sortedUserList = userList.sortedBy {
            it.userId
        }
        logger.info{"All Infos are successfully listed."}
        return ListAllInfoResponse.newBuilder().addAllUserEntries(sortedUserList).build()
    }

}

class IdentityServiceClient(host: String, port: Int, enableSsl: Boolean) {
    private val client = if (enableSsl)  {
        IdentityServiceGrpcKt.IdentityServiceCoroutineStub(
            NettyChannelBuilder
                .forAddress(host, port)
                .sslContext(
                    GrpcSslContexts
                        .forClient()
                        .trustManager(ENV.CERT)
                        .sslProvider(SslProvider.OPENSSL)
                        .build()
                )
                .build()
        )
    } else {
        IdentityServiceGrpcKt.IdentityServiceCoroutineStub(
            NettyChannelBuilder.forAddress(host, port).usePlaintext().build()
        )
    }

    suspend fun createUser(loginNameP: String, realNameP: String, passwordP: String) {
        try {
            val response = client.createUser(createUserRequest{loginName=loginNameP;realName=realNameP;password=passwordP})
            if(response.success==true){
                println("User created with User ID = "+response.userId)
            }
            else{
                throw Exception(response.message)
            }
        } catch (e: Exception) {
            println("User creation Failed!")
            println(e)
        }
    }

    suspend fun modifyUser(oldLogin: String, newLogin: String, passwordP: String){
        try {
            val response = client.modifyUser(modifyUserRequest{oldLoginName = oldLogin; newLoginName = newLogin; password = passwordP})
            println(response.message)
        } catch (e: Exception) {
            println("Modification failed: $e")
        }
    }

    suspend fun lookup(loginNameP: String){
        try {
            val resp = client.lookup(lookupUserRequest{ loginName = loginNameP })
            if(resp.success == false){
                println(resp.message)
            }else{
                println("$loginNameP: ID: ${resp.userId} Real Name: ${resp.realName}")
            }
        } catch (e: Exception) {
            println("Lookup failed: $e")
        }
    }

    suspend fun deleteUser(loginNameP: String,passwordP: String){
        try{
            val response = client.deleteUser(deleteUserRequest{loginName=loginNameP;password=passwordP})
            println(response.message)
        }catch(e:Exception){
            println(e)
        }
    }

    suspend fun listLogins(){
         try{
             val response = client.listLogins(empty{})
             //println(response)
            for(i in response.loginNamesList){
                println(i)
            }
         }catch(e:Exception){
             println(e)
         }
     }

    suspend fun listIds(){
        try{
            val response = client.listIds(empty{})
            for(i in response.userIdsList){
                println(i)
            }
        }catch(e:Exception){
            println(e)
        }
    }

    suspend fun reverseLookup(user_Id: String){
        try {
            val resp = client.reverseLookup(reverseLookupUserRequest{ userId = user_Id })
            if(!resp.success){
                println(resp.message)
            }else{
                println("Login Name: ${resp.loginName} Real Name: ${resp.realName}")
            }
        } catch (e: Exception) {
            println("Reverse Lookup failed: $e")
        }
    }

    suspend fun listAllInfo(){
        try {
            val response = client.listAllInfo(empty{})
            for(i in response.userEntriesList){
                println("User ID: ${i.userId} Login Name: ${i.loginName} Real Name: ${i.realName}")
            }
        } catch (e: Exception) {
            println("Exception during list all: $e")
        }
    }

}

fun printUsage(stay: Boolean = false) {
    if (stay) {
        return
    }
    println("""Usage: 
        Identity server <enable_ssl> <port>
        Identity client <enable_ssl> <host> <port> <command> <arguments>

        The commands are arguments for the client are as follows:

        create <loginName> <realName> <password>
        modify <oldLoginName> <newLoginName> <password>
        delete <loginName> <password>
        lookup <loginName>
        reverseLookup <userID>
        listLogins
        listIds
        listAllInfo
        """.trimIndent())
    exitProcess(1)
}

suspend fun main(args: Array<String>) {
    printUsage(args.size in setOf(2, 5, 6, 7, 8))
    if (args.size == 2) {
        printUsage(args[0] == "server")
        IdentityServer(args[1].toInt(), false).start()
        return
    }
    printUsage(args[0] == "client")
    val client = IdentityServiceClient(args[2], args[3].toInt(), args[1].toBooleanStrict())
    if (args.size == 5) {
        printUsage(args[4] in setOf("listLogins","listIds","listAllInfo"))
        if(args[4] == "listLogins"){
            client.listLogins()
        }else if(args[4] == "listIds"){
            client.listIds()
        }else if(args[4] == "listAllInfo"){
            client.listAllInfo()
        }
        return
    }
    if (args.size == 6) {
        printUsage(args[4] in setOf("lookup","reverseLookup"))
        if(args[4] == "lookup"){
            client.lookup(args[5])
        }else if(args[4] == "reverseLookup"){
            client.reverseLookup(args[5])
        }
        return
    }
    if (args.size == 7) {
        printUsage(args[4] == "delete")
        client.deleteUser(args[5], args[6])
        return
    }
    if (args.size == 8) {
        printUsage(args[4] in setOf("create","modify"))
        if(args[4]=="create"){
            client.createUser(args[5], args[6], args[7])
        }
        else if(args[4]=="modify"){
            client.modifyUser(args[5], args[6], args[7])
        }
        return
    }
}