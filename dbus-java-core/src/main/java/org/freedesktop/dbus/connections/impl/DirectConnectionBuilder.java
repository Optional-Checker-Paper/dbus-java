package org.freedesktop.dbus.connections.impl;

import org.freedesktop.dbus.connections.BusAddress;
import org.freedesktop.dbus.connections.config.ReceivingServiceConfig;
import org.freedesktop.dbus.connections.config.TransportConfig;
import org.freedesktop.dbus.exceptions.DBusException;

/**
 * Builder to create a new DirectConnection.
 *
 * @author hypfvieh
 * @version 4.1.0 - 2022-02-04
 */
public final class DirectConnectionBuilder extends BaseConnectionBuilder<DirectConnectionBuilder, DirectConnection> {

    private DirectConnectionBuilder(BusAddress _address) {
        super(DirectConnectionBuilder.class, _address);
    }

    /**
     * Use the given address to create the connection (e.g. used for remote TCP connected DBus daemons).
     *
     * @param _address address to use
     * @return this
     */
    public static DirectConnectionBuilder forAddress(String _address) {
        BusAddress busAddress = BusAddress.of(_address);
        DirectConnectionBuilder instance = new DirectConnectionBuilder(busAddress);
        return instance;
    }

    /**
     * Create the new {@link DBusConnection}.
     *
     * @return {@link DBusConnection}
     * @throws DBusException when DBusConnection could not be opened
     */
    @Override
    public DirectConnection build() throws DBusException {
        ReceivingServiceConfig rsCfg = buildThreadConfig();
        TransportConfig transportCfg = buildTransportConfig();

        DirectConnection c = new DirectConnection(transportCfg, rsCfg);
        c.setDisconnectCallback(getDisconnectCallback());
        c.setWeakReferences(isWeakReference());

        return c;
    }

}
