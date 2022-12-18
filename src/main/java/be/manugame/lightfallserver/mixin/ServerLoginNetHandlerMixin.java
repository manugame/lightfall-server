package be.manugame.lightfallserver.mixin;

import be.manugame.lightfallserver.NetworkManagerBridge;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.SignatureValidator;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.apache.commons.lang3.Validate;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PrivateKey;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.server.network.ServerLoginPacketListenerImpl.isValidUsername;


@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginNetHandlerMixin {

    // @formatter:off
    @Shadow private ServerLoginPacketListenerImpl.State state;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public Connection connection;
    @Shadow @Final private static AtomicInteger UNIQUE_THREAD_ID;
    @Shadow private GameProfile gameProfile;
    @Shadow @Final private static Logger LOGGER;
    @Shadow protected abstract GameProfile createFakeProfile(GameProfile original);
    @Shadow public abstract void disconnect(Component reason);
    @Shadow public abstract String getUserName();
    @Shadow private ServerPlayer delayedAcceptPlayer;
    @Shadow @Final private byte[] nonce;
    // @formatter:on

    @Shadow @Nullable private ProfilePublicKey.Data profilePublicKeyData;

    @Shadow
    @Nullable
    protected static ProfilePublicKey validatePublicKey(@org.jetbrains.annotations.Nullable ProfilePublicKey.Data p_240244_, UUID p_240245_, SignatureValidator p_240246_, boolean p_240247_) throws ProfilePublicKey.ValidationException {
        return null;
    }

    public void disconnect(final String s) {
        this.disconnect(Component.literal(s));
    }

    /**
     * @author IzzelAliz
     * @reason
     */
    @Overwrite
    public void handleAcceptedLogin() {
        ProfilePublicKey profilepublickey = null;
        if (!this.gameProfile.isComplete()) {
            this.initUUID();
        } else {
            try {
                SignatureValidator signaturevalidator = this.server.getServiceSignatureValidator();
                profilepublickey = this.validatePublicKey(this.profilePublicKeyData, this.gameProfile.getId(), signaturevalidator, this.server.enforceSecureProfile());
            } catch (ProfilePublicKey.ValidationException profilepublickey$validationexception) {
                LOGGER.error("Failed to validate profile key: {}", (Object)profilepublickey$validationexception.getMessage());
                if (!this.connection.isMemoryConnection()) {
                    this.disconnect(profilepublickey$validationexception.getComponent());
                    return;
                }
            }
        }
        /*
        if (!this.loginGameProfile.isComplete()) {
            this.loginGameProfile = this.getOfflineProfile(this.loginGameProfile);
        }
        */

        if (!this.server.usesAuthentication()) {
            // this.gameProfile = this.createFakeProfile(this.gameProfile); // Spigot - Moved to initUUID
            // Spigot end
        }

        Component component1 = this.server.getPlayerList().canPlayerLogin(this.connection.getRemoteAddress(), this.gameProfile);
        if (component1 != null) {
            this.disconnect(component1);
        } else {
            this.state = ServerLoginPacketListenerImpl.State.ACCEPTED;
            if (this.server.getCompressionThreshold() >= 0 && !this.connection.isMemoryConnection()) {
                this.connection.send(new ClientboundLoginCompressionPacket(this.server.getCompressionThreshold()), PacketSendListener.thenRun(() -> {
                    this.connection.setupCompression(this.server.getCompressionThreshold(), true);
                }));
            }

            this.connection.send(new ClientboundGameProfilePacket(this.gameProfile));
            ServerPlayer serverplayerentity = this.server.getPlayerList().getPlayerForLogin(this.gameProfile, profilepublickey);
            try {
                if (serverplayerentity != null) {
                    this.state = ServerLoginPacketListenerImpl.State.DELAY_ACCEPT;
                    this.delayedAcceptPlayer = serverplayerentity;
                } else {
                    this.server.getPlayerList().placeNewPlayer(this.connection, serverplayerentity);
                }
            } catch (Exception exception) {
                LOGGER.error("Couldn't place player in world", exception);
                var chatmessage = Component.translatable("multiplayer.disconnect.invalid_player_data");

                this.connection.send(new ClientboundDisconnectPacket(chatmessage));
                this.connection.disconnect(chatmessage);
            }
        }
    }

    /**
     * @author IzzelAliz
     * @reason
     */
    @Overwrite
    public void handleHello(ServerboundHelloPacket packetIn) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet");
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet");
        Validate.validState(isValidUsername(packetIn.name()), "Invalid characters in username");
        GameProfile gameprofile = this.server.getSingleplayerProfile();
        if (gameprofile != null && packetIn.name().equalsIgnoreCase(gameprofile.getName())) {
            this.gameProfile = gameprofile;
            this.state = ServerLoginPacketListenerImpl.State.NEGOTIATING; // FORGE: continue NEGOTIATING, we move to READY_TO_ACCEPT after Forge is ready
        } else {
            this.gameProfile = new GameProfile(null, packetIn.name());
            if (this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
                this.state = ServerLoginPacketListenerImpl.State.KEY;
                this.connection.send(new ClientboundHelloPacket("", this.server.getKeyPair().getPublic().getEncoded(), this.nonce));
            } else {
                class Handler extends Thread {

                    Handler() {
                        super(SidedThreadGroups.SERVER, "User Authenticator #" + UNIQUE_THREAD_ID.incrementAndGet());
                    }

                    @Override
                    public void run() {
                        try {
                            initUUID();
                            arclight$preLogin();
                        } catch (Exception ex) {
                            disconnect("Failed to verify username!");
                            LOGGER.warn("Exception verifying {} ", gameProfile.getName(), ex);
                        }
                    }
                }
                new Handler().start();
            }
        }
    }

    public void initUUID() {
        UUID uuid;
        if (((NetworkManagerBridge) this.connection).bridge$getSpoofedUUID() != null) {
            uuid = ((NetworkManagerBridge) this.connection).bridge$getSpoofedUUID();
        } else {
            uuid = UUIDUtil.createOfflinePlayerUUID(this.gameProfile.getName());
        }
        this.gameProfile = new GameProfile(uuid, this.gameProfile.getName());
        if (((NetworkManagerBridge) this.connection).bridge$getSpoofedProfile() != null) {
            Property[] spoofedProfile;
            for (int length = (spoofedProfile = ((NetworkManagerBridge) this.connection).bridge$getSpoofedProfile()).length, i = 0; i < length; ++i) {
                final Property property = spoofedProfile[i];
                this.gameProfile.getProperties().put(property.getName(), property);
            }
        }
    }

    /**
     * @author IzzelAliz
     * @reason
     */
    @Overwrite
    public void handleKey(ServerboundKeyPacket packetIn) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.KEY, "Unexpected key packet");

        final String s;
        try {
            PrivateKey privatekey = this.server.getKeyPair().getPrivate();
            if (!packetIn.isNonceValid(this.nonce, privatekey)) {
                throw new IllegalStateException("Protocol error");
            }

            SecretKey secretKey = packetIn.getSecretKey(privatekey);
            Cipher cipher = Crypt.getCipher(2, secretKey);
            Cipher cipher1 = Crypt.getCipher(1, secretKey);
            s = (new BigInteger(Crypt.digestData("", this.server.getKeyPair().getPublic(), secretKey))).toString(16);
            this.state = ServerLoginPacketListenerImpl.State.AUTHENTICATING;
            this.connection.setEncryptionKey(cipher, cipher1);
        } catch (CryptException cryptexception) {
            throw new IllegalStateException("Protocol error", cryptexception);
        }

        class Handler extends Thread {

            Handler(int i) {
                super(SidedThreadGroups.SERVER, "User Authenticator #" + i);
            }

            public void run() {
                GameProfile gameprofile = gameProfile;

                try {
                    gameProfile = server.getSessionService().hasJoinedServer(new GameProfile(null, gameprofile.getName()), s, this.getAddress());
                    if (gameProfile != null) {
                        if (!connection.isConnected()) {
                            return;
                        }
                        arclight$preLogin();
                    } else if (server.isSingleplayer()) {
                        LOGGER.warn("Failed to verify username but will let them in anyway!");
                        gameProfile = createFakeProfile(gameprofile);
                        state = ServerLoginPacketListenerImpl.State.NEGOTIATING;
                    } else {
                        disconnect(Component.translatable("multiplayer.disconnect.unverified_username"));
                        LOGGER.error("Username '{}' tried to join with an invalid session", gameprofile.getName());
                    }
                } catch (Exception var3) {
                    if (server.isSingleplayer()) {
                        LOGGER.warn("Authentication servers are down but will let them in anyway!");
                        gameProfile = createFakeProfile(gameprofile);
                        state = ServerLoginPacketListenerImpl.State.NEGOTIATING;
                    } else {
                        disconnect(Component.translatable("multiplayer.disconnect.authservers_down"));
                        LOGGER.error("Couldn't verify username because servers are unavailable");
                    }
                }

            }

            @Nullable
            private InetAddress getAddress() {
                SocketAddress socketaddress = connection.getRemoteAddress();
                return server.getPreventProxyConnections() && socketaddress instanceof InetSocketAddress ? ((InetSocketAddress) socketaddress).getAddress() : null;
            }
        }
        Thread thread = new Handler(UNIQUE_THREAD_ID.incrementAndGet());
        thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        thread.start();
    }

    void arclight$preLogin() throws Exception {
        LOGGER.info("UUID of player {} is {}", gameProfile.getName(), gameProfile.getId());
        state = ServerLoginPacketListenerImpl.State.NEGOTIATING;
    }
}
