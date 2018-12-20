package jsmith.nknclient.network;

import com.darkyen.dave.WebbException;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import jsmith.nknclient.Const;
import jsmith.nknclient.client.Identity;
import jsmith.nknclient.client.NKNClient;
import jsmith.nknclient.client.NKNClientError;
import jsmith.nknclient.network.proto.Messages;
import jsmith.nknclient.network.proto.Payloads;
import jsmith.nknclient.utils.Crypto;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 *
 */
public class ClientApi extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(ClientApi.class);

    private int retries = Const.RETRIES;
    private boolean running = false;

    private InetSocketAddress[] routingNodesRpc;
    private InetSocketAddress directNodeWS = null;
    private WsApi ws = null;

    private final Identity identity;

    public ClientApi(Identity identity, InetSocketAddress[] routingNodesRpc) {
        this.routingNodesRpc = routingNodesRpc;
        this.identity = identity;
    }


    @Override
    public void start() {
        if (running) throw new NKNClientError("Client is already running, cannot start again");

        boolean success = false;

        // Choose one node using round robin
        int routingNodeIdx = (int)(Math.random() * routingNodesRpc.length);
        InetSocketAddress routingNodeRpc = routingNodesRpc[routingNodeIdx];

        while (retries >= 0) {
            if (!routingNode(routingNodeRpc) || !establishWsConnection()) {
                retries --;
                if (retries >= 0) {
                    routingNodeIdx ++;
                    if (routingNodeIdx >= routingNodesRpc.length) routingNodeIdx -= routingNodesRpc.length;
                    routingNodeRpc = routingNodesRpc[routingNodeIdx];
                }
            } else {
                success = true;
                break;
            }
        }

        if (!success) throw new NKNClientError("Failed to connect to network");
        running = true;

        setDaemon(true);
        super.start();
    }

    public void close() {
        if (!running) throw new NKNClientError("Client is not (yet) running, cannot close");

        try {
            ws.closeBlocking();
        } catch (InterruptedException ignored) {}
        synchronized (jobLock) {
            stop = true;
            jobLock.notify();
        }
        try {
            join();
        } catch (InterruptedException ignored) {}
    }

    private final Object closeLock = new Object();
    private boolean routingNode(InetSocketAddress routingNode) {
        try {

            final JSONObject parameters = new JSONObject();
            parameters.put("address", identity.getFullIdentifier());

            LOG.debug("Client is connecting to routingNode node:", routingNode);

            final String wsAddr = HttpApi.rpcCall(routingNode, "getwsaddr", parameters);

            if (wsAddr != null) {
                try {
                    final String[] parts = wsAddr.split(":");
                    directNodeWS = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    LOG.error("Failed to reconstruct node address from string '{}'", wsAddr);
                    throw new NKNClientError("Could not initialize connection. Caused by illegal format of ws address");
                }
            } else {
                LOG.error("Did not receive valid rpc result, containing node address");
                throw new NKNClientError("Could not initialize connection. Did not receive valid response from server");
            }

            return true;
        } catch (WebbException e) {
            LOG.warn("RPC Request failed, remaining retries: {}", retries);
            return false;
        }
    }

    private boolean establishWsConnection() {
        LOG.debug("Client is connecting to node ws:", directNodeWS);
        final boolean[] success = {true};
        ws = new WsApi(directNodeWS);

        ws.setJsonMessageListener(json -> {
            switch (json.get("Action").toString()) {
                case "setClient": {
                    if (json.has("Error") && (int)json.get("Error") != 0) {
                        LOG.warn("WS connection failed, remaining retries: {}", retries);
                        ws.close();
                        success[0] = false;
                        synchronized (closeLock) {
                            closeLock.notify();
                        }
                    } else {
                        synchronized (closeLock) {
                            closeLock.notify();
                        }
                    }
                    break;
                }
                case "updateSigChainBlockHash": {
                    // TODO // if ((int)json.get("Error") == 0) onMessageUpdateSigChainBlockHash(json.get("Result").toString());
                    break;
                }
                default:
                    LOG.warn("Got unknown message (action='{}'), ignoring", json.get("Action").toString());
            }
        });

        ws.setProtobufMessageListener(bytes -> {
            try {
                final Messages.InboundMessage msg = Messages.InboundMessage.parseFrom(bytes);

                final String from = msg.getSrc();
                final Payloads.Payload payload = Payloads.Payload.parseFrom(msg.getPayload());

                switch (payload.getType()) {
                    case ACK:
                    case TEXT:
                    case BINARY:
                        handleInboundMessage(from, payload);
                        break;

                    default:
                        LOG.warn("Got invalid payload type {}, ignoring", payload.getType());
                        break;
                }

            } catch (InvalidProtocolBufferException e) {
                LOG.warn("Got invalid binary message, ignoring");
                e.printStackTrace();
            }
        });

        ws.setOpenListener( ignored -> {
                    final JSONObject setClientReq = new JSONObject();
                    setClientReq.put("Action", "setClient");
                    setClientReq.put("Addr", identity.getFullIdentifier());

                    ws.sendPacket(setClientReq);
                }
        );
        ws.connect();

        synchronized (closeLock) {
            try {
                closeLock.wait();
            } catch (InterruptedException ignored) {}
        }
        return success[0];
    }


    private boolean noAck = false;
    public void setNoAutomaticACKs(boolean noAck) {
        this.noAck = noAck;
    }

    private final Object jobLock = new Object();
    private final ArrayList<MessageJob> jobs = new ArrayList<>();
    private final ArrayList<MessageJob> waitingForReply = new ArrayList<>();
    private boolean stop = false;

    @Override
    public void run() {
        while (!stop || !jobs.isEmpty()) {
            long nextWake = -1;
            synchronized (jobLock) {
                final long now = System.currentTimeMillis();
                final Iterator<MessageJob> iterator = waitingForReply.iterator();
                while (iterator.hasNext()) {
                    final MessageJob j = iterator.next();

                    if (j.receivedAck) {
                        j.promise.complete(j.ack);
                        iterator.remove();
                    } else if (j.timeoutAt >= now) {
                        j.promise.completeExceptionally(new NKNClientError.MessageAckTimeout(j.messageID));
                        iterator.remove();
                    } else {
                        if (nextWake == -1) {
                            nextWake = j.timeoutAt;
                        } else {
                            nextWake = Math.min(nextWake, j.timeoutAt);
                        }
                    }
                }
            }

            MessageJob j = null;
            synchronized (jobLock) {
                if (!jobs.isEmpty()) {
                    j = jobs.remove(0);
                }
            }
            if (j != null) {
                waitingForReply.add(j);
                ws.sendPacket(j.payload);
                if (nextWake == -1) {
                    nextWake = j.timeoutAt;
                } else {
                    nextWake = Math.min(nextWake, j.timeoutAt);
                }
            }

            synchronized (jobLock) {
                if (jobs.isEmpty()) {
                    try {
                        if (!stop) {
                            if (nextWake == -1) {
                                jobLock.wait();
                            } else {
                                jobLock.wait(Math.max(0, nextWake - System.currentTimeMillis()));
                            }
                        }
                    } catch (InterruptedException ignored) {}
                }
            }
        }

        running = false;
    }


    private Function<NKNClient.ReceivedMessage, Object> onMessageL = null;
    public void onMessage(Function<NKNClient.ReceivedMessage, Object> listener) {
        onMessageL = listener;
    }


    private void handleInboundMessage(String from, Payloads.Payload message) {
        final Payloads.PayloadType type = message.getType();
        final ByteString replyTo = message.getReplyToPid();
        final ByteString messageID = message.getPid();

        boolean isReplyTo = false;

        synchronized (jobLock) {
            for (MessageJob j : waitingForReply) {
                if (j.messageID.equals(replyTo)) {
                    if (type == Payloads.PayloadType.TEXT) {
                        try {
                            j.ack = new NKNClient.ReceivedMessage(
                                    from,
                                    messageID,
                                    Payloads.PayloadType.TEXT,
                                    Payloads.TextData.parseFrom(message.getData()).getText()
                            );
                        } catch (InvalidProtocolBufferException e) {
                            LOG.warn("Received packet is of type TEXT but does not contain valid text data");
                        }
                    } else if (type == Payloads.PayloadType.BINARY) {
                        j.ack = new NKNClient.ReceivedMessage(
                                from,
                                messageID,
                                Payloads.PayloadType.BINARY,
                                message.getData()
                        );
                    }
                    j.receivedAck = true;
                    isReplyTo = true;
                }
            }

            jobLock.notify();
        }

        Object ackMessage = null;
        if (!isReplyTo) {

            if (type == Payloads.PayloadType.TEXT) {
                try {
                    if (onMessageL != null) {
                        ackMessage = onMessageL.apply(new NKNClient.ReceivedMessage(from, messageID, type, Payloads.TextData.parseFrom(message.getData()).getText()));
                    }
                } catch (InvalidProtocolBufferException e) {
                    LOG.warn("Received packet is of type TEXT but does not contain valid text data");
                }
            } else if (type == Payloads.PayloadType.BINARY) {
                if (onMessageL != null) {
                    ackMessage = onMessageL.apply(new NKNClient.ReceivedMessage(from, messageID, type, message.getData()));
                }
            }
        }

        if (type != Payloads.PayloadType.ACK) {
            if (ackMessage == null) {
                if (!message.getNoAck()) {
                    sendAckMessage(from, messageID);
                }
            } else {
                sendMessage(from, messageID, ackMessage);
            }
        }

    }

    public CompletableFuture<NKNClient.ReceivedMessage> sendMessage(String destination, ByteString replyTo, Object message) {
        if (message instanceof String) {
            return sendMessage(destination, replyTo, Payloads.PayloadType.TEXT, Payloads.TextData.newBuilder().setText((String) message).build().toByteString());
        } else if (message instanceof ByteString) {
            return sendMessage(destination, replyTo, Payloads.PayloadType.BINARY, (ByteString) message);
        } else if (message instanceof byte[]) {
            return sendMessage(destination, replyTo, Payloads.PayloadType.BINARY, ByteString.copyFrom((byte[]) message));
        } else {
            LOG.error("Cannot serialize '{}' to NKN protobuf message", message.getClass());
            throw new NKNClientError.UnknownObjectType("Cannot serialize '" + message.getClass() + "' to NKN message");
        }
    }

    public CompletableFuture<NKNClient.ReceivedMessage> sendMessage(String destination, ByteString replyTo, Payloads.PayloadType type, ByteString message) {
        final ByteString messageID = ByteString.copyFrom(Crypto.nextRandom4B());
        final ByteString replyToMessageID = replyTo == null ? ByteString.copyFrom(new byte[0]) : replyTo;


        final Payloads.Payload payload = Payloads.Payload.newBuilder()
                .setType(type)
                .setPid(messageID)
                .setReplyToPid(replyToMessageID)
                .setData(message)
                .setNoAck(noAck)
                .build();


        return sendOutboundMessage(destination, messageID, payload.toByteString());
    }

    private CompletableFuture<NKNClient.ReceivedMessage> sendOutboundMessage(String destination, ByteString messageID, ByteString payload) {

        final Messages.OutboundMessage binMsg = Messages.OutboundMessage.newBuilder()
                .setDest(destination)
                .setPayload(payload)
                // .addDests() // TODO multicast
                .setMaxHoldingSeconds(0)
                .build();

        final CompletableFuture<NKNClient.ReceivedMessage> promise = new CompletableFuture<>();

        final MessageJob j = new MessageJob(destination, messageID, binMsg.toByteString(), promise, System.currentTimeMillis() + Const.MESSAGE_ACK_TIMEOUT_MS);

        LOG.debug("Queueing new MessageJob");
        synchronized (jobLock) {
            jobs.add(j);
            jobLock.notify();
        }

        return promise;
    }

    public void sendAckMessage(String destination, ByteString replyTo) {
        final Payloads.Payload payload = Payloads.Payload.newBuilder()
                .setType(Payloads.PayloadType.ACK)
                .setPid(ByteString.copyFrom(Crypto.nextRandom4B()))
                .setReplyToPid(replyTo)
                .setNoAck(true)
                .build();

        final Messages.OutboundMessage binMsg = Messages.OutboundMessage.newBuilder()
                .setDest(destination)
                .setPayload(payload.toByteString())
                .setMaxHoldingSeconds(0)
                .build();

        ws.sendPacket(binMsg.toByteString());
    }


    private static class MessageJob {

        private final String destination;
        private final ByteString messageID, payload;
        private final CompletableFuture<NKNClient.ReceivedMessage> promise;
        private final long timeoutAt;

        private boolean receivedAck = false;
        private NKNClient.ReceivedMessage ack = null;

        MessageJob(String destination, ByteString messageID, ByteString payload, CompletableFuture<NKNClient.ReceivedMessage> promise, long timeoutAt) {
            this.destination = destination;
            this.messageID = messageID;
            this.payload = payload;
            this.promise = promise;
            this.timeoutAt = timeoutAt;
        }

    }
}