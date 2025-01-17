package org.freedesktop.dbus.connections.impl;

import static org.freedesktop.dbus.utils.CommonRegexPattern.*;

import org.freedesktop.dbus.DBusMatchRule;
import org.freedesktop.dbus.RemoteInvocationHandler;
import org.freedesktop.dbus.RemoteObject;
import org.freedesktop.dbus.connections.AbstractConnection;
import org.freedesktop.dbus.connections.IDisconnectAction;
import org.freedesktop.dbus.connections.config.ReceivingServiceConfig;
import org.freedesktop.dbus.connections.config.TransportConfig;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.exceptions.NotConnected;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.interfaces.Introspectable;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.messages.ExportedObject;
import org.freedesktop.dbus.types.UInt32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Handles a connection to DBus.
 * <p>
 * This is a Singleton class, only 1 connection to the SYSTEM or SESSION busses can be made. Repeated calls to
 * getConnection will return the same reference.
 * </p>
 * <p>
 * Signal Handlers and method calls from remote objects are run in their own threads, you MUST handle the concurrency
 * issues.
 * </p>
 */
public final class DBusConnection extends AbstractConnection {

    static final ConcurrentMap<String, DBusConnection> CONNECTIONS           = new ConcurrentHashMap<>();

    private final Logger                               logger                = LoggerFactory.getLogger(getClass());

    private final List<String>                         busnames;

    private final String                               machineId;
    private DBus                                       dbus;

    /** Whether the connection was registered using 'Hello' message. */
    private boolean                                    registered;

    /** Count how many 'connections' we manage internally.
     * This is required because a {@link DBusConnection} to the same address will always return the same object and
     * the 'real' disconnection should only occur when there is no second/third/whatever connection is left. */
    //CHECKSTYLE:OFF
    final AtomicInteger                                concurrentConnections = new AtomicInteger(1);
    //CHECKSTYLE:ON

    /**
     * Whether this connection is used in shared mode.
     */
    private final boolean shared;

    DBusConnection(boolean _shared, String _machineId, TransportConfig _tranportCfg, ReceivingServiceConfig _rsCfg) throws DBusException {
        super(_tranportCfg, _rsCfg);
        busnames = new ArrayList<>();
        machineId = _machineId;
        shared = _shared;
    }

    private AtomicInteger getConcurrentConnections() {
        return concurrentConnections;
    }

    /**
     * Connect to bus and register if asked. Should only be called by Builder.
     *
     * @throws DBusException if registering or connection fails
     */
    void connectImpl() throws DBusException {
        // start listening for calls
        try {
            listen();
        } catch (IOException _ex) {
            throw new DBusException(_ex);
        }

        // register disconnect handlers
        DBusSigHandler<?> h = new SigHandler();
        addSigHandlerWithoutMatch(DBus.NameAcquired.class, h);

        // register ourselves if not disabled
        if (getTransportConfig().isRegisterSelf() && getTransport().isConnected()) {
            register();
        }
    }

    /**
     * Register this connection on the bus using 'Hello' message.<br>
     * Will do nothing if session was already registered.
     *
     * @throws DBusException when sending message fails
     *
     * @since 5.0.0 - 2023-10-11
     */
    public void register() throws DBusException {
        if (registered) {
            return;
        }

        dbus = getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);

        try {
            busnames.add(dbus.Hello());
            registered = true;
        } catch (DBusExecutionException _ex) {
            logger.debug("Error while doing 'Hello' handshake", _ex);
            throw new DBusException(_ex.getMessage(), _ex);
        }
    }

    /**
     * Tries to resolve a proxy to a remote object.
     * If a type class is given, it tries to convert the object using that class.
     * If null is given as type, it tries to find a proper interface for this object.
     *
     * @param <T> object type (DBusInterface compatible)
     * @param _source source
     * @param _path path
     * @param _type class of object type
     *
     * @return DBusInterface compatible object
     *
     * @throws DBusException when something goes wrong
     *
     * @apiNote This method is only intended for internal use.
     * Visibility may change in future release
     */
    @SuppressWarnings("unchecked")
    public <T extends DBusInterface> T dynamicProxy(String _source, String _path, Class<T> _type) throws DBusException {
        logger.debug("Introspecting {} on {} for dynamic proxy creation", _path, _source);
        try {
            Introspectable intro = getRemoteObject(_source, _path, Introspectable.class);
            String data = intro.Introspect();
            logger.trace("Got introspection data: {}", data);

            String[] tags = PROXY_SPLIT_PATTERN.split(data);

            List<String> ifaces = Arrays.stream(tags).filter(t -> t.startsWith("interface"))
                .map(t -> IFACE_PATTERN.matcher(t).replaceAll("$1"))
                .map(i -> {
                    if (i.startsWith("org.freedesktop.DBus.")) { // if this is a default DBus interface, look for it in our package structure
                        return DBUS_IFACE_PATTERN.matcher(i).replaceAll("$1");
                    }
                    return i;
                })
                .collect(Collectors.toList());

            List<Class<?>> ifcs = findMatchingTypes(_type, ifaces);

            // interface could not be found, we guess that this exported object at least support DBusInterface
            if (ifcs.isEmpty()) {
                ifcs.add(DBusInterface.class);
            }

            RemoteObject ro = new RemoteObject(_source, _path, _type, false);
            DBusInterface newi = (DBusInterface) Proxy.newProxyInstance(ifcs.get(0).getClassLoader(),
                    ifcs.toArray(Class[]::new), new RemoteInvocationHandler(this, ro));
            getImportedObjects().put(newi, ro);

            return (T) newi;
        } catch (Exception _ex) {
            logger.debug("", _ex);
            throw new DBusException(
                    String.format("Failed to create proxy object for %s exported by %s. Reason: %s", _path,
                            _source, _ex.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends DBusInterface> T getExportedObject(String _source, String _path, Class<T> _type) throws DBusException {
        ExportedObject o;
        synchronized (getExportedObjects()) {
            o = getExportedObjects().get(_path);
        }
        if (null != o && o.getObject().get() == null) {
            unExportObject(_path);
            o = null;
        }
        if (null != o) {
            return (T) o.getObject().get();
        }
        if (null == _source) {
            throw new DBusException("Not an object exported by this connection and no remote specified");
        }
        return dynamicProxy(_source, _path, _type);
    }

    @Override
    public DBusInterface getExportedObject(String _source, String _path) throws DBusException {
        return getExportedObject(_source, _path, null);
    }

    /**
     * Release a bus name. Releases the name so that other people can use it
     *
     * @param _busname
     *            The name to release. MUST be in dot-notation like "org.freedesktop.local"
     * @throws DBusException
     *             If the busname is incorrectly formatted.
     */
    public void releaseBusName(String _busname) throws DBusException {
        if (_busname.length() > MAX_NAME_LENGTH || !BUSNAME_REGEX.matcher(_busname).matches()) {
            throw new DBusException("Invalid bus name");
        }
        try {
            dbus.ReleaseName(_busname);
        } catch (DBusExecutionException _ex) {
            logger.debug("", _ex);
            throw new DBusException(_ex.getMessage());
        }

        synchronized (this.busnames) {
            this.busnames.remove(_busname);
        }
    }

    /**
     * Request a bus name. Request the well known name that this should respond to on the Bus.
     *
     * @param _busname
     *            The name to respond to. MUST be in dot-notation like "org.freedesktop.local"
     * @throws DBusException
     *             If the register name failed, or our name already exists on the bus. or if busname is incorrectly
     *             formatted.
     */
    public void requestBusName(String _busname) throws DBusException {
        if (_busname.length() > MAX_NAME_LENGTH || !BUSNAME_REGEX.matcher(_busname).matches()) {
            throw new DBusException("Invalid bus name");
        }

        UInt32 rv;
        try {
            rv = dbus.RequestName(_busname,
                    new UInt32(DBus.DBUS_NAME_FLAG_REPLACE_EXISTING | DBus.DBUS_NAME_FLAG_DO_NOT_QUEUE));
        } catch (DBusExecutionException _exDb) {
            logger.debug("", _exDb);
            throw new DBusException(_exDb);
        }

        if (rv.intValue() == DBus.DBUS_REQUEST_NAME_REPLY_IN_QUEUE
            || rv.intValue() == DBus.DBUS_REQUEST_NAME_REPLY_EXISTS) {
            throw new DBusException("Failed to register bus name");
        }

        synchronized (this.busnames) {
            this.busnames.add(_busname);
        }
    }

    /**
     * Returns the unique name of this connection.
     *
     * @return unique name
     */
    public String getUniqueName() {
        return busnames.get(0);
    }

    /**
     * Returns all the names owned by this connection.
     *
     * @return connection names
     */
    public String[] getNames() {
        Set<String> names = new TreeSet<>();
        names.addAll(busnames);
        return names.toArray(String[]::new);
    }

    public <I extends DBusInterface> I getPeerRemoteObject(String _busname, String _objectpath, Class<I> _type)
            throws DBusException {
        return getPeerRemoteObject(_busname, _objectpath, _type, true);
    }

    /**
     * Return a reference to a remote object. This method will resolve the well known name (if given) to a unique bus
     * name when you call it. This means that if a well known name is released by one process and acquired by another
     * calls to objects gained from this method will continue to operate on the original process.
     *
     * This method will use bus introspection to determine the interfaces on a remote object and so <b>may block</b> and
     * <b>may fail</b>. The resulting proxy object will, however, be castable to any interface it implements. It will
     * also autostart the process if applicable. Also note that the resulting proxy may fail to execute the correct
     * method with overloaded methods and that complex types may fail in interesting ways. Basically, if something odd
     * happens, try specifying the interface explicitly.
     *
     * @param _busname
     *            The bus name to connect to. Usually a well known bus name in dot-notation (such as
     *            "org.freedesktop.local") or may be a DBus address such as ":1-16".
     * @param _objectpath
     *            The path on which the process is exporting the object.$
     * @return A reference to a remote object.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusInterface
     * @throws DBusException
     *             If busname or objectpath are incorrectly formatted.
     */
    public DBusInterface getPeerRemoteObject(String _busname, String _objectpath) throws DBusException {
        if (null == _busname) {
            throw new DBusException("Invalid bus name: null");
        }

        if (_busname.length() > MAX_NAME_LENGTH || !BUSNAME_REGEX.matcher(_busname).matches() && !CONNID_REGEX.matcher(_busname).matches()) {
            throw new DBusException("Invalid bus name: " + _busname);
        }

        String unique = dbus.GetNameOwner(_busname);

        return dynamicProxy(unique, _objectpath, null);
    }

    /**
     * Return a reference to a remote object. This method will always refer to the well known name (if given) rather
     * than resolving it to a unique bus name. In particular this means that if a process providing the well known name
     * disappears and is taken over by another process proxy objects gained by this method will make calls on the new
     * proccess.
     *
     * This method will use bus introspection to determine the interfaces on a remote object and so <b>may block</b> and
     * <b>may fail</b>. The resulting proxy object will, however, be castable to any interface it implements. It will
     * also autostart the process if applicable. Also note that the resulting proxy may fail to execute the correct
     * method with overloaded methods and that complex types may fail in interesting ways. Basically, if something odd
     * happens, try specifying the interface explicitly.
     *
     * @param _busname
     *            The bus name to connect to. Usually a well known bus name name in dot-notation (such as
     *            "org.freedesktop.local") or may be a DBus address such as ":1-16".
     * @param _objectpath
     *            The path on which the process is exporting the object.
     * @return A reference to a remote object.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusInterface
     * @throws DBusException
     *             If busname or objectpath are incorrectly formatted.
     */
    public DBusInterface getRemoteObject(String _busname, String _objectpath) throws DBusException {
        if (null == _busname) {
            throw new DBusException("Invalid bus name: null");
        }
        if (null == _objectpath) {
            throw new DBusException("Invalid object path: null");
        }

        if (_busname.length() > MAX_NAME_LENGTH || !BUSNAME_REGEX.matcher(_busname).matches() && !CONNID_REGEX.matcher(_busname).matches()) {
            throw new DBusException("Invalid bus name: " + _busname);
        }

        if (_objectpath.length() > MAX_NAME_LENGTH || !OBJECT_REGEX_PATTERN.matcher(_objectpath).matches()) {
            throw new DBusException("Invalid object path: " + _objectpath);
        }

        return dynamicProxy(_busname, _objectpath, null);
    }

    /**
     * Return a reference to a remote object. This method will resolve the well known name (if given) to a unique bus
     * name when you call it. This means that if a well known name is released by one process and acquired by another
     * calls to objects gained from this method will continue to operate on the original process.
     *
     * @param <I>
     *            class extending {@link DBusInterface}
     * @param _busname
     *            The bus name to connect to. Usually a well known bus name in dot-notation (such as
     *            "org.freedesktop.local") or may be a DBus address such as ":1-16".
     * @param _objectpath
     *            The path on which the process is exporting the object.$
     * @param _type
     *            The interface they are exporting it on. This type must have the same full class name and exposed
     *            method signatures as the interface the remote object is exporting.
     * @param _autostart
     *            Disable/Enable auto-starting of services in response to calls on this object. Default is enabled; when
     *            calling a method with auto-start enabled, if the destination is a well-known name and is not owned the
     *            bus will attempt to start a process to take the name. When disabled an error is returned immediately.
     * @return A reference to a remote object.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusInterface
     * @throws DBusException
     *             If busname or objectpath are incorrectly formatted or type is not in a package.
     */
    public <I extends DBusInterface> I getPeerRemoteObject(String _busname, String _objectpath, Class<I> _type,
            boolean _autostart) throws DBusException {
        if (null == _busname) {
            throw new DBusException("Invalid bus name: null");
        }

        if (_busname.length() > MAX_NAME_LENGTH || !BUSNAME_REGEX.matcher(_busname).matches() && !CONNID_REGEX.matcher(_busname).matches()) {
            throw new DBusException("Invalid bus name: " + _busname);
        }

        String unique = dbus.GetNameOwner(_busname);

        return getRemoteObject(unique, _objectpath, _type, _autostart);
    }

    /**
     * Return a reference to a remote object. This method will always refer to the well known name (if given) rather
     * than resolving it to a unique bus name. In particular this means that if a process providing the well known name
     * disappears and is taken over by another process proxy objects gained by this method will make calls on the new
     * proccess.
     *
     * @param <I>
     *            class extending {@link DBusInterface}
     * @param _busname
     *            The bus name to connect to. Usually a well known bus name name in dot-notation (such as
     *            "org.freedesktop.local") or may be a DBus address such as ":1-16".
     * @param _objectpath
     *            The path on which the process is exporting the object.
     * @param _type
     *            The interface they are exporting it on. This type must have the same full class name and exposed
     *            method signatures as the interface the remote object is exporting.
     * @return A reference to a remote object.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusInterface
     * @throws DBusException
     *             If busname or objectpath are incorrectly formatted or type is not in a package.
     */
    public <I extends DBusInterface> I getRemoteObject(String _busname, String _objectpath, Class<I> _type)
            throws DBusException {
        return getRemoteObject(_busname, _objectpath, _type, true);
    }

    /**
     * Return a reference to a remote object. This method will always refer to the well known name (if given) rather
     * than resolving it to a unique bus name. In particular this means that if a process providing the well known name
     * disappears and is taken over by another process proxy objects gained by this method will make calls on the new
     * proccess.
     *
     * @param <I>
     *            class extending {@link DBusInterface}
     * @param _busname
     *            The bus name to connect to. Usually a well known bus name name in dot-notation (such as
     *            "org.freedesktop.local") or may be a DBus address such as ":1-16".
     * @param _objectpath
     *            The path on which the process is exporting the object.
     * @param _type
     *            The interface they are exporting it on. This type must have the same full class name and exposed
     *            method signatures as the interface the remote object is exporting.
     * @param _autostart
     *            Disable/Enable auto-starting of services in response to calls on this object. Default is enabled; when
     *            calling a method with auto-start enabled, if the destination is a well-known name and is not owned the
     *            bus will attempt to start a process to take the name. When disabled an error is returned immediately.
     * @return A reference to a remote object.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusInterface
     * @throws DBusException
     *             If busname or objectpath are incorrectly formatted or type is not in a package.
     */
    @SuppressWarnings("unchecked")
    public <I extends DBusInterface> I getRemoteObject(String _busname, String _objectpath, Class<I> _type,
            boolean _autostart) throws DBusException {
        if (null == _busname) {
            throw new DBusException("Invalid bus name: null");
        }
        if (null == _objectpath) {
            throw new DBusException("Invalid object path: null");
        }
        if (null == _type) {
            throw new ClassCastException("Not A DBus Interface");
        }

        if (_busname.length() > MAX_NAME_LENGTH || !BUSNAME_REGEX.matcher(_busname).matches() && !CONNID_REGEX.matcher(_busname).matches()) {
            throw new DBusException("Invalid bus name: " + _busname);
        }

        if (!OBJECT_REGEX_PATTERN.matcher(_objectpath).matches() || _objectpath.length() > MAX_NAME_LENGTH) {
            throw new DBusException("Invalid object path: " + _objectpath);
        }

        if (!DBusInterface.class.isAssignableFrom(_type)) {
            throw new ClassCastException("Not A DBus Interface");
        }

        // don't let people import things which don't have a
        // valid D-Bus interface name
        if (_type.getName().equals(_type.getSimpleName())) {
            throw new DBusException("DBusInterfaces cannot be declared outside a package");
        }

        RemoteObject ro = new RemoteObject(_busname, _objectpath, _type, _autostart);
        I i = (I) Proxy.newProxyInstance(_type.getClassLoader(), new Class[] {
                _type
        }, new RemoteInvocationHandler(this, ro));
        getImportedObjects().put(i, ro);
        return i;
    }

    /**
     * Remove a Signal Handler. Stops listening for this signal.
     *
     * @param <T>
     *            class extending {@link DBusSignal}
     * @param _type
     *            The signal to watch for.
     * @param _source
     *            The source of the signal.
     * @param _handler
     *            the handler
     * @throws DBusException
     *             If listening for the signal on the bus failed.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusSignal.
     */
    public <T extends DBusSignal> void removeSigHandler(Class<T> _type, String _source, DBusSigHandler<T> _handler)
            throws DBusException {
        validateSignal(_type, _source);
        removeSigHandler(new DBusMatchRule(_type, _source, null), _handler);
    }

    /**
     * Remove a Signal Handler. Stops listening for this signal.
     *
     * @param <T>
     *            class extending {@link DBusSignal}
     * @param _type
     *            The signal to watch for.
     * @param _source
     *            The source of the signal.
     * @param _object
     *            The object emitting the signal.
     * @param _handler
     *            the handler
     * @throws DBusException
     *             If listening for the signal on the bus failed.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusSignal.
     */
    public <T extends DBusSignal> void removeSigHandler(Class<T> _type, String _source, DBusInterface _object,
            DBusSigHandler<T> _handler) throws DBusException {
        validateSignal(_type, _source);

        String objectpath = getImportedObjects().get(_object).getObjectPath();
        if (objectpath.length() > MAX_NAME_LENGTH || !OBJECT_REGEX_PATTERN.matcher(objectpath).matches()) {
            throw new DBusException("Invalid object path: " + objectpath);
        }
        removeSigHandler(new DBusMatchRule(_type, _source, objectpath), _handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected <T extends DBusSignal> void removeSigHandler(DBusMatchRule _rule, DBusSigHandler<T> _handler)
            throws DBusException {

        Queue<DBusSigHandler<? extends DBusSignal>> dbusSignalList = getHandledSignals().get(_rule);

        if (null != dbusSignalList) {
            dbusSignalList.remove(_handler);
            if (dbusSignalList.isEmpty()) {
                getHandledSignals().remove(_rule);
                try {
                    dbus.RemoveMatch(_rule.toString());
                } catch (NotConnected _ex) {
                    logger.debug("No connection.", _ex);
                } catch (DBusExecutionException _ex) {
                    logger.debug("", _ex);
                    throw new DBusException(_ex);
                }
            }
        }
    }

    /**
     * Add a Signal Handler. Adds a signal handler to call when a signal is received which matches the specified type,
     * name and source.
     *
     * @param <T>
     *            class extending {@link DBusSignal}
     * @param _type
     *            The signal to watch for.
     * @param _source
     *            The process which will send the signal. This <b>MUST</b> be a unique bus name and not a well known
     *            name.
     * @return closeable that removes signal handler
     * @param _handler
     *            The handler to call when a signal is received.
     * @throws DBusException
     *             If listening for the signal on the bus failed.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusSignal.
     */
    public <T extends DBusSignal> AutoCloseable addSigHandler(Class<T> _type, String _source, DBusSigHandler<T> _handler)
            throws DBusException {
        validateSignal(_type, _source);
        addSigHandler(new DBusMatchRule(_type, _source, null), (DBusSigHandler<? extends DBusSignal>) _handler);
        return new AutoCloseable() {

            @Override
            public void close() throws DBusException {
                removeSigHandler(_type, _source, _handler);
            }
        };
    }

    /**
     * Add a Signal Handler. Adds a signal handler to call when a signal is received which matches the specified type,
     * name, source and object.
     *
     * @param <T>
     *            class extending {@link DBusSignal}
     * @param _type
     *            The signal to watch for.
     * @param _source
     *            The process which will send the signal. This <b>MUST</b> be a unique bus name and not a well known
     *            name.
     * @param _object
     *            The object from which the signal will be emitted
     * @param _handler
     *            The handler to call when a signal is received.
     * @return closeable that removes signal handler
     * @throws DBusException
     *             If listening for the signal on the bus failed.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusSignal.
     */
    public <T extends DBusSignal> AutoCloseable addSigHandler(Class<T> _type, String _source, DBusInterface _object,
            DBusSigHandler<T> _handler) throws DBusException {
        validateSignal(_type, _source);

        String objectpath = getImportedObjects().get(_object).getObjectPath();
        if (objectpath.length() > MAX_NAME_LENGTH || !OBJECT_REGEX_PATTERN.matcher(objectpath).matches()) {
            throw new DBusException("Invalid object path: " + objectpath);
        }
        addSigHandler(new DBusMatchRule(_type, _source, objectpath), (DBusSigHandler<? extends DBusSignal>) _handler);
        return new AutoCloseable() {
            @Override
            public void close() throws DBusException {
                removeSigHandler(_type, _source, _object, _handler);
            }
        };
    }

    /**
     * Checks if given type is a DBusSignal and matches the required rules.
     *
     * @param <T> type of class
     * @param _type class
     * @param _source
     * @throws DBusException when validation fails
     */
    private <T extends DBusSignal> void validateSignal(Class<T> _type, String _source) throws DBusException {
        if (!DBusSignal.class.isAssignableFrom(_type)) {
            throw new ClassCastException("Not A DBus Signal");
        }
        if (BUSNAME_REGEX.matcher(_source).matches()) {
            throw new DBusException(
                    "Cannot watch for signals based on well known bus name as source, only unique names.");
        }
        if (_source.length() > MAX_NAME_LENGTH || !CONNID_REGEX.matcher(_source).matches()) {
            throw new DBusException("Invalid bus name: " + _source);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends DBusSignal> AutoCloseable addSigHandler(DBusMatchRule _rule, DBusSigHandler<T> _handler)
            throws DBusException {

        Objects.requireNonNull(_rule, "Match rule cannot be null");
        Objects.requireNonNull(_handler, "Handler cannot be null");

        AtomicBoolean addMatch = new AtomicBoolean(false); // flag to perform action if this is a new signal key

        Queue<DBusSigHandler<? extends DBusSignal>> dbusSignalList =
            getHandledSignals().computeIfAbsent(_rule, v -> {
                Queue<DBusSigHandler<? extends DBusSignal>> signalList  = new ConcurrentLinkedQueue<>();
                addMatch.set(true);
                return signalList;
            });

        // add handler to signal list
        dbusSignalList.add(_handler);

        // add match rule if this rule is new
        if (addMatch.get()) {
            try {
                dbus.AddMatch(_rule.toString());
            } catch (DBusExecutionException _ex) {
                logger.debug("Cannot add match rule: " + _rule.toString(), _ex);
                throw new DBusException("Cannot add match rule.", _ex);
            }
        }
        return new AutoCloseable() {
            @Override
            public void close() throws DBusException {
                removeSigHandler(_rule, _handler);
            }
        };
    }

    /**
     * Disconnect from the Bus.
     * If this is a shared connection, it only disconnects when the last reference to the bus has called disconnect.
     * If this is not a shared connection, disconnect will close the connection instantly.
     */
    @Override
    public synchronized void disconnect() {
        if (!isConnected()) { // already disconnected
            return;
        }

        // if this is a shared connection, keep track of disconnect calls
        if (shared) {

            synchronized (CONNECTIONS) {
                DBusConnection connection = CONNECTIONS.get(getAddress().toString());
                if (connection != null) {
                    if (connection.getConcurrentConnections().get() <= 1) { // one left, this should be ourselves
                        CONNECTIONS.remove(getAddress().toString());

                        super.disconnect();

                    } else {
                        logger.debug("Still {} connections left, decreasing connection counter", connection.getConcurrentConnections().get() - 1);
                        Optional.ofNullable(getDisconnectCallback()).ifPresent(cb -> cb.requestedDisconnect(connection.getConcurrentConnections().get()));
                        connection.getConcurrentConnections().decrementAndGet();
                    }
                }
            }

        } else { // this is a standalone non-shared session, disconnect directly using super's implementation
            IDisconnectAction beforeDisconnectAction = () -> {

                // get all busnames from the list which matches the usual pattern
                // this is required as the list also contains internal names like ":1.11"
                // it is also required to put the results in a new list, otherwise we would get a
                // concurrent modification exception later (calling releaseBusName() will modify the busnames List)
                synchronized (busnames) {
                    List<String> lBusNames = busnames.stream()
                        .filter(busName -> busName != null && !(busName.length() > MAX_NAME_LENGTH || !BUSNAME_REGEX.matcher(busName).matches()))
                        .collect(Collectors.toList());

                    lBusNames.forEach(busName -> {
                            try {
                                releaseBusName(busName);

                            } catch (DBusException _ex) {
                                logger.error("Error while releasing busName '" + busName + "'.", _ex);
                            }

                        });
                }

                // remove all exported objects before disconnecting
                Map<String, ExportedObject> exportedObjects = getExportedObjects();
                synchronized (exportedObjects) {
                    List<String> exportedKeys = exportedObjects.keySet().stream().filter(f -> f != null).collect(Collectors.toList());
                    for (String key : exportedKeys) {
                        unExportObject(key);
                    }
                }

            };

            super.disconnect(beforeDisconnectAction, null);
        }
    }

    /**
     * Same as disconnect.
     */
    @Override
    public void close() throws IOException {
        disconnect();
    }

    @Override
    public String getMachineId() {
        return machineId;
    }

    @Override
    public void removeGenericSigHandler(DBusMatchRule _rule, DBusSigHandler<DBusSignal> _handler) throws DBusException {
        Queue<DBusSigHandler<DBusSignal>> genericSignalsList = getGenericHandledSignals().get(_rule);
        if (null != genericSignalsList) {
            genericSignalsList.remove(_handler);
            if (genericSignalsList.isEmpty()) {
                getGenericHandledSignals().remove(_rule);
                try {
                    dbus.RemoveMatch(_rule.toString());
                } catch (NotConnected _ex) {
                    logger.debug("No connection.", _ex);
                } catch (DBusExecutionException _ex) {
                    logger.debug("", _ex);
                    throw new DBusException(_ex);
                }
            }
        }
    }

    @Override
    public AutoCloseable addGenericSigHandler(DBusMatchRule _rule, DBusSigHandler<DBusSignal> _handler) throws DBusException {
        AtomicBoolean addMatch = new AtomicBoolean(false); // flag to perform action if this is a new signal key

        Queue<DBusSigHandler<DBusSignal>> genericSignalsList =
                getGenericHandledSignals().computeIfAbsent(_rule, v -> {
                    Queue<DBusSigHandler<DBusSignal>> signalsList = new ConcurrentLinkedQueue<>();
                    addMatch.set(true);

                    return signalsList;
                });

        genericSignalsList.add(_handler);

        if (addMatch.get()) {
            try {
                dbus.AddMatch(_rule.toString());
            } catch (DBusExecutionException _ex) {
                logger.debug("", _ex);
                throw new DBusException(_ex.getMessage());
            }
        }
        return new AutoCloseable() {
            @Override
            public void close() throws DBusException {
                removeGenericSigHandler(_rule, _handler);
            }
        };
    }

    private final class SigHandler implements DBusSigHandler<DBusSignal> {
        @Override
        public void handle(DBusSignal _signal) {
            if (_signal instanceof DBus.NameAcquired na) {
                synchronized (busnames) {
                    busnames.add(na.name);
                }
            }
        }
    }

    public enum DBusBusType {
        /**
         * System Bus
         */
        SYSTEM,
        /**
         * Session Bus
         */
        SESSION;
    }
}
