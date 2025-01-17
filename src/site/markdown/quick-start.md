# DBus-Java Quickstart

1. Add in dbus-java-core and a apropriate transport to your dependencies. [Documentation on adding to dependencies.](./dependency-info.html)
2. (optional) Add in a logging framework of your choice to see the log messages.
  DBus-Java uses SLF4J internally for logging.

        <!-- Example of using log4j2 as the logging implementation with Maven -->
        <dependencies>
            <!-- ... other dependencies here ... -->
            <dependency>
                <groupId>org.apache.logging.log4j</groupId>
                <artifactId>log4j-api</artifactId>
                <version>2.17.2</version>
            </dependency>
            <dependency>
                <groupId>org.apache.logging.log4j</groupId>
                <artifactId>log4j-slf4j-impl</artifactId>
                <version>2.17.2</version>
            </dependency>

        </dependencies>

3. Get a connection to the bus. This can be either the SESSION bus or the SYSTEM bus.

        DBusConnection conn = DBusConnectionBuilder.forSessionBus().build();

4. Request bus name if you want a well-known bus name, or begin calling methods on the bus!
  You only need to request a bus name if you are expecting other people to talk
  to you, so that they know how to talk to you.


## Session vs System bus

By default, there are two buses on the system for different purposes.  The
first bus, the session bus, is created on a per-user basis, generally when you
log in.  However, this bus may not be created unless you have a graphical
session.  This bus is used for session data such as notifications to the user,
property changes on the shell you are using, etc.

The second bus is the system bus.  There is only one system bus at a time, and
this bus contains information that is relevant to the system as a whole.  Some
of this data can be common to the session bus as well.  Data that may be on the
system bus includes things such as mount/unmount data, new devices found, etc.

Since there is only one system bus, it has more stringent security requirements.
You should always be able to listen to events on the system bus, but you may not
be able to request a bus name without updating some permissions.  The process
of updating permissions is outside the scope of this document; however, the XML
files that control the permissions can be found in `/etc/dbus-1/system.d`.

## Connection configuration

If you do not have any special requirements, usually `DBusConnectionBuilder.forSessionBus().build()` or `DBusConnectionBuilder.forSystemBus().build()` is all you need to do.

In some cases you may have to change the default settings. This can be done through the appropriate builder options.
The Builder allows you to change most settings used to connect and to communicate with DBus.

### Changing thread-pool settings
For receiving data from DBus dbus-java uses several thread pools. This is required due to the different purposes of 
received messages. There are signals, methods and method returns.
To prevent one message processing blocking other message processing, each of those message types are handled in different
thread pools.

The default settings is 1 thread per message type for signals, methods and errors. Method-Return messages have a thread pool
size of 4. 

To change those settings, use the `receivingThreadConfig()` method on the connection builders.
Example:

    DBusConnectionBuilder.forSessionBus()
        .receivingThreadConfig()
            .withSignalThreadCount(4)
            .withMethodThreadCount(2)
        .connectionConfig()
        .withShared(false)
        .build()

In the example above, the signal thread pool will be increased to 4 threads, the method thread pool to 2 threads.
Calling `connectionConfig()` will return the used connection builder (`DBusConnectionBuilder` in this case) to allow configure further settings on the connection before creating the actual connection.
If you do not want to do additional configuration you can also call `.buildConnection()` on the `ReceivingServiceConfigBuilder` to create the connection directly.

With this pattern you can also change the thread priority for each pool.

### IMPORTANT NOTE ABOUT SIGNAL THREADPOOL
Increasing the signal thread pool will improve the speed the signals are handled.
Nevertheless increasing the thread pool may also cause the signals to be handled in any order, not necessarily in the order 
they were sent/received. 

This is caused by concurrency of the threads in the pool. If you receive e.g. 10 signals and have 4 signal handler threads, 4 of them are handled in parallel and thread 3 might be finished before thread 1 is finished etc.

If signal order is important for your use case, DO NOT INCREMENT the signal thread pool size!

### Using retry handler
In some very rare cases (this has only been reported for some JDK versions of some vendors used on ARM 32-bit platform, see [#172](https://github.com/hypfvieh/dbus-java/issues/172)), the thread pool executors may throw unexpected NullPointerExceptions. 

In such cases the default behavior is to retry adding the message callback runnable to the executor up to 10 times.
If adding still fails, the message callback runnable will be dropped (ignored) and an error will be logged.

You can change this behavior by installing a custom `IThreadPoolRetryHandler`. 
This handler is a functional interface and can be expressed as lambda.
The handler will receive the executor type (`ExecutorNames` enum value) which failed execute a runnable and the
exception which was thrown in that case.
It should return `true` if the execution should be retried, or `false` to ignore the runnable.

In any case there is a 'hard' limit of retries (to avoid spinning up CPU without any use) which is 50 currently.
After that hard limit is reached, error will be logged and runnable will be ignored.

You can also set the configured retry handler to `null` which will cause the `ReceivingService` log an error (including exception) and ignoring the failed runnable.

Retry handler can be installed like this:

    DBusConnectionBuilder.forSessionBus()
        .receivingThreadConfig()
            .withRetryHandler((t, ex) -> {
                // do something useful and return true or false
                return false;
            })
        .connectionConfig()
        .withShared(false)
        .build()

### IMPORTANT NOTE USING RETRY HANDLER
The retry handler is executed synchronously (in the thread where the connection receives data from the bus).
That means whatever you do in your retry handler, either do it quick or delegate the actual work to your own thread pool/thread and do it asynchronously. Doing too much work in the retry handler will cause delays in other received messages and may cause DBus to disconnect the session.
        
## Examples
For example code checkout dbus-java-examples module of this project. 
