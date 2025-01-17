package org.freedesktop.dbus.connections;

import org.freedesktop.dbus.DBusAsyncReply;
import org.freedesktop.dbus.DBusMatchRule;
import org.freedesktop.dbus.RemoteInvocationHandler;
import org.freedesktop.dbus.RemoteObject;
import org.freedesktop.dbus.connections.base.ConnectionMessageHandler;
import org.freedesktop.dbus.connections.base.IncomingMessageThread;
import org.freedesktop.dbus.connections.config.ReceivingServiceConfig;
import org.freedesktop.dbus.connections.config.TransportConfig;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.CallbackHandler;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.messages.ExportedObject;
import org.freedesktop.dbus.messages.MethodCall;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Handles a connection to DBus.
 */
public abstract non-sealed class AbstractConnection extends ConnectionMessageHandler {

    public static final boolean      FLOAT_SUPPORT          = null != System.getenv("DBUS_JAVA_FLOATS");
    public static final Pattern      BUSNAME_REGEX          = Pattern.compile("^[-_a-zA-Z][-_a-zA-Z0-9]*(\\.[-_a-zA-Z][-_a-zA-Z0-9]*)*$");
    public static final Pattern      CONNID_REGEX           = Pattern.compile("^:[0-9]*\\.[0-9]*$");
    public static final Pattern      OBJECT_REGEX_PATTERN   = Pattern.compile("^/([-_a-zA-Z0-9]+(/[-_a-zA-Z0-9]+)*)?$");
    public static final Pattern      DOLLAR_PATTERN         = Pattern.compile("[$]");

    public static final int          MAX_ARRAY_LENGTH       = 67108864;
    public static final int          MAX_NAME_LENGTH        = 255;

    private boolean                                weakreferences       = false;

    protected AbstractConnection(TransportConfig _transportConfig, ReceivingServiceConfig _rsCfg) throws DBusException {
        super(_transportConfig, _rsCfg);
    }

    @Override
    protected IncomingMessageThread createReaderThread(BusAddress _busAddress) {
        return new IncomingMessageThread(this, _busAddress);
    }

    /**
     * Remove a match rule with the given {@link DBusSigHandler}.
     * The rule will only be removed from DBus if no other additional handlers are registered to the same rule.
     *
     * @param _rule rule to remove
     * @param _handler handler to remove
     *
     * @param <T> signal type
     *
     * @throws DBusException on error
     */
    protected abstract <T extends DBusSignal> void removeSigHandler(DBusMatchRule _rule, DBusSigHandler<T> _handler) throws DBusException;

    /**
     * Add a signal handler with the given {@link DBusMatchRule} to DBus.
     * The rule will be added to DBus if it was not added before.
     * If the rule was already added, the signal handler is added to the internal map receiving
     * the same signal as the first (and additional) handlers for this rule.
     *
     * @param _rule rule to add
     * @param _handler handler to use
     *
     * @param <T> signal type
     * @return closeable that removes signal handler
     *
     * @throws DBusException on error
     */
    protected abstract <T extends DBusSignal> AutoCloseable addSigHandler(DBusMatchRule _rule, DBusSigHandler<T> _handler) throws DBusException;

    /**
     * Remove a generic signal handler with the given {@link DBusMatchRule}.
     * The rule will only be removed from DBus if no other additional handlers are registered to the same rule.
     *
     * @param _rule rule to remove
     * @param _handler handler to remove
     * @throws DBusException on error
     */
    protected abstract void removeGenericSigHandler(DBusMatchRule _rule, DBusSigHandler<DBusSignal> _handler) throws DBusException;

    /**
     * Adds a {@link DBusMatchRule} to with a generic signal handler.
     * Generic signal handlers allow receiving different signals with the same handler.
     * If the rule was already added, the signal handler is added to the internal map receiving
     * the same signal as the first (and additional) handlers for this rule.
     *
     * @param _rule rule to add
     * @param _handler handler to use
     * @return closeable that removes signal handler
     * @throws DBusException on error
     */
    protected abstract AutoCloseable addGenericSigHandler(DBusMatchRule _rule, DBusSigHandler<DBusSignal> _handler) throws DBusException;

    /**
     * If given type is null, will try to find suitable types by examining the given ifaces.
     * If a non-null type is given, returns the given type.
     *
     * @param <T> any DBusInterface compatible object
     * @param _type type or null
     * @param _ifaces interfaces to examining when type is null
     *
     * @return List
     */
    protected <T extends DBusInterface> List<Class<?>> findMatchingTypes(Class<T> _type, List<String> _ifaces) {
        List<Class<?>> ifcs = new ArrayList<>();
        if (_type == null) {
            for (String iface : _ifaces) {

                getLogger().debug("Trying interface {}", iface);
                int j = 0;
                while (j >= 0) {
                    try {
                        Class<?> ifclass = Class.forName(iface);
                        if (!ifcs.contains(ifclass)) {
                            ifcs.add(ifclass);
                        }
                        break;
                    } catch (Exception _ex) {
                        getLogger().trace("No class found for {}", iface, _ex);
                    }
                    j = iface.lastIndexOf('.');
                    char[] cs = iface.toCharArray();
                    if (j >= 0) {
                        cs[j] = '$';
                        iface = String.valueOf(cs);
                    }
                }
            }
        } else {
            ifcs.add(_type);
        }
        return ifcs;
    }

    /**
     * If set to true the bus will not hold a strong reference to exported objects. If they go out of scope they will
     * automatically be unexported from the bus. The default is to hold a strong reference, which means objects must be
     * explicitly unexported before they will be garbage collected.
     *
     * @param _weakreferences reference
     */
    public void setWeakReferences(boolean _weakreferences) {
        this.weakreferences = _weakreferences;
    }

    /**
     * Export an object so that its methods can be called on DBus.
     *
     * @param _objectPath
     *            The path to the object we are exposing. MUST be in slash-notation, like "/org/freedesktop/Local", and
     *            SHOULD end with a capitalised term. Only one object may be exposed on each path at any one time, but
     *            an object may be exposed on several paths at once.
     * @param _object
     *            The object to export.
     * @throws DBusException
     *             If the objectpath is already exporting an object. or if objectpath is incorrectly formatted,
     */
    public void exportObject(String _objectPath, DBusInterface _object) throws DBusException {
        if (null == _objectPath || _objectPath.isEmpty()) {
            throw new DBusException("Must Specify an Object Path");
        }
        if (_objectPath.length() > MAX_NAME_LENGTH || !(OBJECT_REGEX_PATTERN.matcher(_objectPath).matches())) {
            throw new DBusException("Invalid object path: " + _objectPath);
        }
        synchronized (getExportedObjects()) {
            if (null != getExportedObjects().get(_objectPath)) {
                throw new DBusException("Object already exported");
            }
            ExportedObject eo = new ExportedObject(_object, weakreferences);
            getExportedObjects().put(_objectPath, eo);
            synchronized (getObjectTree()) {
                getObjectTree().add(_objectPath, eo, eo.getIntrospectiondata());
            }
        }
    }

    /**
     * Export an object so that its methods can be called on DBus. The path to the object will be taken from the
     * {@link DBusInterface#getObjectPath()} method, make sure it is implemented and returns immutable value.
     * If you want export object with multiple paths, please use {@link AbstractConnection#exportObject(String, DBusInterface)}.
     *
     * @param _object
     *            The object to export.
     * @throws DBusException
     *             If the object path is already exporting an object or if object path is incorrectly formatted.
     */
    public void exportObject(DBusInterface _object) throws DBusException {
        Objects.requireNonNull(_object, "object must not be null");
        exportObject(_object.getObjectPath(), _object);
    }

    /**
     * Export an object as a fallback object. This object will have it's methods invoked for all paths starting with
     * this object path.
     *
     * @param _objectPrefix
     *            The path below which the fallback handles calls. MUST be in slash-notation, like
     *            "/org/freedesktop/Local",
     * @param _object
     *            The object to export.
     * @throws DBusException
     *             If the objectpath is incorrectly formatted,
     */
    public void addFallback(String _objectPrefix, DBusInterface _object) throws DBusException {
        if (_objectPrefix == null || _objectPrefix.isEmpty()) {
            throw new DBusException("Must Specify an Object Path");
        }
        if (_objectPrefix.length() > MAX_NAME_LENGTH || !OBJECT_REGEX_PATTERN.matcher(_objectPrefix).matches()) {
            throw new DBusException("Invalid object path: " + _objectPrefix);
        }
        ExportedObject eo = new ExportedObject(_object, weakreferences);
        getFallbackContainer().add(_objectPrefix, eo);
    }

    /**
     * Remove a fallback
     *
     * @param _objectprefix
     *            The prefix to remove the fallback for.
     */
    public void removeFallback(String _objectprefix) {
        getFallbackContainer().remove(_objectprefix);
    }

    /**
     * Remove a Signal Handler. Stops listening for this signal.
     *
     * @param <T>
     *            class extending {@link DBusSignal}
     * @param _type
     *            The signal to watch for.
     * @param _handler
     *            the handler
     * @throws DBusException
     *             If listening for the signal on the bus failed.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusSignal.
     */
    public <T extends DBusSignal> void removeSigHandler(Class<T> _type, DBusSigHandler<T> _handler) throws DBusException {
        if (!DBusSignal.class.isAssignableFrom(_type)) {
            throw new ClassCastException("Not A DBus Signal");
        }

        removeSigHandler(new DBusMatchRule(_type), _handler);
    }

    /**
     * Remove a Signal Handler. Stops listening for this signal.
     *
     * @param <T>
     *            class extending {@link DBusSignal}
     * @param _type
     *            The signal to watch for.
     * @param _object
     *            The object emitting the signal.
     * @param _handler
     *            the handler
     * @throws DBusException
     *             If listening for the signal on the bus failed.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusSignal.
     */
    public <T extends DBusSignal> void removeSigHandler(Class<T> _type, DBusInterface _object, DBusSigHandler<T> _handler)
            throws DBusException {
        if (!DBusSignal.class.isAssignableFrom(_type)) {
            throw new ClassCastException("Not A DBus Signal");
        }
        String objectpath = getImportedObjects().get(_object).getObjectPath();
        if (objectpath.length() > MAX_NAME_LENGTH || !OBJECT_REGEX_PATTERN.matcher(objectpath).matches()) {
            throw new DBusException("Invalid object path: " + objectpath);
        }
        removeSigHandler(new DBusMatchRule(_type, null, objectpath), _handler);
    }

    /**
     * Add a Signal Handler. Adds a signal handler to call when a signal is received which matches the specified type
     * and name.
     *
     * @param <T>
     *            class extending {@link DBusSignal}
     * @param _type
     *            The signal to watch for.
     * @param _handler
     *            The handler to call when a signal is received.
     * @return closeable that removes signal handler
     * @throws DBusException
     *             If listening for the signal on the bus failed.
     * @throws ClassCastException
     *             If type is not a sub-type of DBusSignal.
     */
    public <T extends DBusSignal> AutoCloseable addSigHandler(Class<T> _type, DBusSigHandler<T> _handler) throws DBusException {
        if (!DBusSignal.class.isAssignableFrom(_type)) {
            throw new ClassCastException("Not A DBus Signal");
        }
        return addSigHandler(new DBusMatchRule(_type), _handler);
    }

    /**
     * Add a Signal Handler. Adds a signal handler to call when a signal is received which matches the specified type,
     * name and object.
     *
     * @param <T>
     *            class extending {@link DBusSignal}
     * @param _type
     *            The signal to watch for.
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
    public <T extends DBusSignal> AutoCloseable addSigHandler(Class<T> _type, DBusInterface _object, DBusSigHandler<T> _handler)
            throws DBusException {
        if (!DBusSignal.class.isAssignableFrom(_type)) {
            throw new ClassCastException("Not A DBus Signal");
        }
        RemoteObject rObj = getImportedObjects().get(_object);
        if (rObj == null) {
            throw new DBusException("Not an object exported or imported by this connection");
        }
        String objectpath = rObj.getObjectPath();
        if (objectpath.length() > MAX_NAME_LENGTH || !OBJECT_REGEX_PATTERN.matcher(objectpath).matches()) {
            throw new DBusException("Invalid object path: " + objectpath);
        }
        return addSigHandler(new DBusMatchRule(_type, null, objectpath), _handler);
    }

    protected <T extends DBusSignal> void addSigHandlerWithoutMatch(Class<? extends DBusSignal> _signal, DBusSigHandler<T> _handler) throws DBusException {
        DBusMatchRule rule = new DBusMatchRule(_signal);
        synchronized (getHandledSignals()) {
            Queue<DBusSigHandler<? extends DBusSignal>> v = getHandledSignals().get(rule);
            if (null == v) {
                v = new ConcurrentLinkedQueue<>();
                v.add(_handler);
                getHandledSignals().put(rule, v);
            } else {
                v.add(_handler);
            }
        }
    }

    /**
     * Call a method asynchronously and set a callback. This handler will be called in a separate thread.
     *
     * @param <A>
     *            whatever
     * @param _object
     *            The remote object on which to call the method.
     * @param _m
     *            The name of the method on the interface to call.
     * @param _callback
     *            The callback handler.
     * @param _parameters
     *            The parameters to call the method with.
     */
    public <A> void callWithCallback(DBusInterface _object, String _m, CallbackHandler<A> _callback,
            Object... _parameters) {
        getLogger().trace("callWithCallback({}, {}, {})", _object, _m, _callback);
        Class<?>[] types = createTypesArray(_parameters);
        RemoteObject ro = getImportedObjects().get(_object);

        try {
            Method me;
            if (null == ro.getInterface()) {
                me = _object.getClass().getMethod(_m, types);
            } else {
                me = ro.getInterface().getMethod(_m, types);
            }
            RemoteInvocationHandler.executeRemoteMethod(ro, me, this, RemoteInvocationHandler.CALL_TYPE_CALLBACK,
                    _callback, _parameters);
        } catch (DBusExecutionException _ex) {
            getLogger().debug("", _ex);
            throw _ex;
        } catch (Exception _ex) {
            getLogger().debug("", _ex);
            throw new DBusExecutionException(_ex.getMessage());
        }
    }

    /**
     * Call a method asynchronously and get a handle with which to get the reply.
     *
     * @param _object
     *            The remote object on which to call the method.
     * @param _method
     *            The name of the method on the interface to call.
     * @param _parameters
     *            The parameters to call the method with.
     * @return A handle to the call.
     */
    public DBusAsyncReply<?> callMethodAsync(DBusInterface _object, String _method, Object... _parameters) {
        Class<?>[] types = createTypesArray(_parameters);
        RemoteObject ro = getImportedObjects().get(_object);

        try {
            Method me;
            if (null == ro.getInterface()) {
                me = _object.getClass().getMethod(_method, types);
            } else {
                me = ro.getInterface().getMethod(_method, types);
            }
            return (DBusAsyncReply<?>) RemoteInvocationHandler.executeRemoteMethod(ro, me, this,
                    RemoteInvocationHandler.CALL_TYPE_ASYNC, null, _parameters);
        } catch (DBusExecutionException _ex) {
            getLogger().debug("", _ex);
            throw _ex;
        } catch (Exception _ex) {
            getLogger().debug("", _ex);
            throw new DBusExecutionException(_ex.getMessage());
        }
    }

    private static Class<?>[] createTypesArray(Object... _parameters) {
        if (_parameters == null) {
            return null;
        }
        return Arrays.stream(_parameters)
                .filter(p -> p != null) // do no try to convert null values to concrete class
                .map(p -> {
                    if (List.class.isAssignableFrom(p.getClass())) { // turn possible List subclasses (e.g. ArrayList) to interface class List
                        return List.class;
                    } else if (Map.class.isAssignableFrom(p.getClass())) { // do the same for Map subclasses
                        return Map.class;
                    } else if (Set.class.isAssignableFrom(p.getClass())) { // and also for Set subclasses
                        return Set.class;
                    } else {
                        return p.getClass();
                    }
                })
                .toArray(Class[]::new);
    }

    public void queueCallback(MethodCall _call, Method _method, CallbackHandler<?> _callback) {
        getCallbackManager().queueCallback(_call, _method, _callback, this);
    }

    public boolean isFileDescriptorSupported() {
        return getTransport().isFileDescriptorSupported();
    }

}
