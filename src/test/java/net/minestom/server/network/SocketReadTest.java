package net.minestom.server.network;

import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.listener.preplay.LoginListener;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientPluginMessagePacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.registry.Registries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SocketReadTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void complete(boolean compressed) throws DataFormatException {
        var packet = new ClientPluginMessagePacket("channel", new byte[2000]);

        var buffer = PacketVanilla.PACKET_POOL.get();
        PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, packet, compressed ? 256 : 0);

        var readResult = PacketReading.readClients(buffer, ConnectionState.PLAY, compressed);
        if (!(readResult instanceof PacketReading.Result.Success<ClientPacket>(
                List<PacketReading.ParsedPacket<ClientPacket>> packets1
        ))) {
            throw new AssertionError("Expected a success result, got " + readResult);
        }
        List<ClientPacket> packets = packets1.stream().map(PacketReading.ParsedPacket::packet).toList();
        assertEquals(List.of(packet), packets);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void completeTwo(boolean compressed) throws DataFormatException {
        var packet = new ClientPluginMessagePacket("channel", new byte[2000]);

        var buffer = PacketVanilla.PACKET_POOL.get();
        PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, packet, compressed ? 256 : 0);
        PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, packet, compressed ? 256 : 0);

        var readResult = PacketReading.readClients(buffer, ConnectionState.PLAY, compressed);
        if (!(readResult instanceof PacketReading.Result.Success<ClientPacket>(
                List<PacketReading.ParsedPacket<ClientPacket>> packets1
        ))) {
            throw new AssertionError("Expected a success result, got " + readResult);
        }
        List<ClientPacket> packets = packets1.stream().map(PacketReading.ParsedPacket::packet).toList();
        assertEquals(List.of(packet, packet), packets);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void insufficientLength(boolean compressed) throws DataFormatException {
        // Write a complete packet then the next packet length without any payload

        var packet = new ClientPluginMessagePacket("channel", new byte[2000]);

        var buffer = PacketVanilla.PACKET_POOL.get();
        PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, packet, compressed ? 256 : 0);
        buffer.write(NetworkBuffer.VAR_INT, 200); // incomplete 200 bytes packet

        var readResult = PacketReading.readClients(buffer, ConnectionState.PLAY, compressed);
        if (!(readResult instanceof PacketReading.Result.Success<ClientPacket>(
                List<PacketReading.ParsedPacket<ClientPacket>> packets1
        ))) {
            throw new AssertionError("Expected a success result, got " + readResult);
        }
        List<ClientPacket> packets = packets1.stream().map(PacketReading.ParsedPacket::packet).toList();
        assertEquals(List.of(packet), packets);

        readResult = PacketReading.readClients(buffer, ConnectionState.PLAY, compressed);
        if (!(readResult instanceof PacketReading.Result.Empty<ClientPacket>)) {
            throw new AssertionError("Expected an empty result, got " + readResult);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void incomplete(boolean compressed) throws DataFormatException {
        // Write a complete packet and incomplete var-int length for the next packet

        var packet = new ClientPluginMessagePacket("channel", new byte[2000]);

        var buffer = PacketVanilla.PACKET_POOL.get();
        PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, packet, compressed ? 256 : 0);
        buffer.write(NetworkBuffer.BYTE, (byte) -85); // incomplete var-int length

        var readResult = PacketReading.readClients(buffer, ConnectionState.PLAY, compressed);
        if (!(readResult instanceof PacketReading.Result.Success<ClientPacket>(
                List<PacketReading.ParsedPacket<ClientPacket>> packets1
        ))) {
            throw new AssertionError("Expected a success result, got " + readResult);
        }
        List<ClientPacket> packets = packets1.stream().map(PacketReading.ParsedPacket::packet).toList();
        assertEquals(1, buffer.readableBytes());

        assertEquals(List.of(packet), packets);

        // Try to read the next packet
        readResult = PacketReading.readClients(buffer, ConnectionState.PLAY, compressed);
        if (!(readResult instanceof PacketReading.Result.Empty<ClientPacket>)) {
            throw new AssertionError("Expected an empty result, got " + readResult);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void resize(boolean compressed) throws DataFormatException {
        // Write a complete packet that is larger than the buffer capacity

        var packet = new ClientPluginMessagePacket("channel", new byte[2000]);

        var buffer = PacketVanilla.PACKET_POOL.get();
        PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, packet, compressed ? 256 : 0);
        final long packetLength = buffer.writeIndex();
        buffer = buffer.copy(0, packetLength / 2).index(0, packetLength / 2);

        var readResult = PacketReading.readClients(buffer, ConnectionState.PLAY, compressed);
        if (!(readResult instanceof PacketReading.Result.Failure<ClientPacket>(long requiredCapacity))) {
            throw new AssertionError("Expected a failure result, got " + readResult);
        }
        assertEquals(packetLength, requiredCapacity);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void resizeHeader(boolean compressed) throws DataFormatException {
        // Write a buffer where you cannot read the packet length

        var buffer = NetworkBuffer.staticBuffer(1);
        buffer.write(NetworkBuffer.BYTE, (byte) -85); // incomplete var-int length

        var readResult = PacketReading.readClients(buffer, ConnectionState.PLAY, compressed);
        if (!(readResult instanceof PacketReading.Result.Failure<ClientPacket>(long requiredCapacity))) {
            throw new AssertionError("Expected a failure result, got " + readResult);
        }
        // 5 = max var-int size
        assertEquals(5, requiredCapacity);
    }

    @Test
    public void compressedReadInheritsSourceBufferRegistries() throws DataFormatException {
        // Encode a framed packet large enough to actually compress (threshold = 256), keeping
        // the pool untouched by the test setup so the read is the only relevant consumer.
        final var packet = new ClientPluginMessagePacket("ch", new byte[2000]);
        final var encoded = NetworkBuffer.resizableBuffer();
        PacketWriting.writeFramedPacket(encoded, ConnectionState.PLAY, packet, 256);
        final long length = encoded.writeIndex();
        final byte[] framed = new byte[(int) length];
        encoded.copyTo(0, framed, 0, length);

        // Drain the pool so any buffer we inspect afterwards must have been used by the read.
        while (PacketVanilla.PACKET_POOL.count() > 0) PacketVanilla.PACKET_POOL.get();

        final Registries sourceRegistries = Registries.vanilla();
        final var source = NetworkBuffer.wrap(framed, 0, framed.length, sourceRegistries);
        PacketReading.readClients(source, ConnectionState.PLAY, true);

        final NetworkBuffer pooled = PacketVanilla.PACKET_POOL.get();
        try {
            assertSame(sourceRegistries, pooled.registries(),
                    "Decompressed pool buffer must inherit the source buffer's registries");
        } finally {
            PacketVanilla.PACKET_POOL.add(pooled);
        }
    }

    @Test
    public void truncatedVarIntPayloadDoesNotConsumeNextFrameByte() {
        final var buffer = NetworkBuffer.resizableBuffer();
        buffer.write(NetworkBuffer.VAR_INT, 2);
        buffer.write(NetworkBuffer.VAR_INT, 0);
        buffer.write(NetworkBuffer.BYTE, (byte) 0x80);
        buffer.write(NetworkBuffer.VAR_INT, 1);
        buffer.write(NetworkBuffer.VAR_INT, 0);

        assertThrows(RuntimeException.class,
                () -> PacketReading.readClient(buffer, ConnectionState.PLAY, false),
                "Truncated payload must not be completed from the next frame byte");
    }

    @Test
    public void forgedLoginAcknowledgedCreatesPlayerInOfflineMode() throws Exception {
        MinecraftServer.init();
        MinecraftServer.setCompressionThreshold(0);

        final var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress("127.0.0.1", 0));
        final int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        try (final var clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
             final var acceptedChannel = serverChannel.accept()) {

            final var connection = new PlayerSocketConnection(
                    acceptedChannel, acceptedChannel.getRemoteAddress(),
                    Thread.currentThread(), Thread.currentThread());

            final var packetParser = PacketVanilla.CLIENT_PACKET_PARSER;
            assertEquals(0, MinecraftServer.getConnectionManager().getOnlinePlayerCount());

            // ---- send handshake + login start ----
            final var handshake = NetworkBuffer.resizableBuffer();
            PacketWriting.writeFramedPacket(handshake, ConnectionState.HANDSHAKE,
                    new ClientHandshakePacket(776, "", port, ClientHandshakePacket.Intent.LOGIN), 0);
            final var loginStart = NetworkBuffer.resizableBuffer();
            PacketWriting.writeFramedPacket(loginStart, ConnectionState.LOGIN,
                    new ClientLoginStartPacket("ExploitPlayer", java.util.UUID.randomUUID()), 0);

            final byte[] preamble = new byte[(int) (handshake.writeIndex() + loginStart.writeIndex())];
            handshake.copyTo(0, preamble, 0, handshake.writeIndex());
            loginStart.copyTo(0, preamble, (int) handshake.writeIndex(), loginStart.writeIndex());
            clientChannel.write(java.nio.ByteBuffer.wrap(preamble));

            connection.read(packetParser);

            // ---- wait for enterConfig to set gameProfile ----
            final long deadline = System.currentTimeMillis() + 5000;
            while (connection.gameProfile() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertNotNull(connection.gameProfile(), "gameProfile should be set after login start");

            // ---- send forged LoginAcknowledged: 00 03 ----
            // Frame 1 length=0, packet ID 0x00 stolen byte is
            // not relevant; VarInt for packet ID crosses boundary
            // and reads 0x03 = ClientLoginAcknowledgedPacket in LOGIN.
            final byte[] exploit = {(byte) 0x00, (byte) 0x03};
            clientChannel.write(java.nio.ByteBuffer.wrap(exploit));

            assertThrows(IndexOutOfBoundsException.class,
                    () -> connection.read(packetParser),
                    "Truncated VarInt should not be completed from the next frame byte");

            assertNull(MinecraftServer.getConnectionManager().getPlayer(connection),
                    "forged LoginAcknowledged should NOT create a Player");
        } finally {
            serverChannel.close();
        }
    }

    private static int getVarIntSize(int input) {
        return (input & 0xFFFFFF80) == 0
                ? 1 : (input & 0xFFFFC000) == 0
                ? 2 : (input & 0xFFE00000) == 0
                ? 3 : (input & 0xF0000000) == 0
                ? 4 : 5;
    }
}
