package be.manugame.lightfallserver;

import com.mojang.authlib.properties.Property;

import java.net.SocketAddress;
import java.util.UUID;

public interface NetworkManagerBridge {
    UUID bridge$getSpoofedUUID();

    void bridge$setSpoofedUUID(UUID spoofedUUID);

    Property[] bridge$getSpoofedProfile();

    void bridge$setSpoofedProfile(Property[] spoofedProfile);

    SocketAddress bridge$getRawAddress();

    String bridge$getHostname();

    void bridge$setHostname(String hostname);
}
