package jsmith.nknsdk.network;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import jsmith.nknsdk.client.NKNClient;
import jsmith.nknsdk.client.NKNClientException;
import jsmith.nknsdk.network.proto.Messages;
import jsmith.nknsdk.network.proto.Payloads;
import jsmith.nknsdk.utils.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 *
 */
public class ClientMessages extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(ClientMessages.class);

    private final ClientTunnel ct;
    private boolean running = true;

    private final int myId;
    public ClientMessages(ClientTunnel ct, int myId) {
        this.ct = ct;
        this.myId = myId;

        setName("ClientMsg-" + myId);
        setDaemon(true);
    }

    private final Object jobLock = new Object();
    private final ArrayList<MessageJob> jobs = new ArrayList<>();
    private final ArrayList<MessageJob> waitingForReply = new ArrayList<>();

    private boolean noAck = false;
    public void setNoAutomaticACKs(boolean noAck) {
        this.noAck = noAck;
    }

    public void run() {
        while (!scheduledStop.get() || (!jobs.isEmpty() && ct.shouldReconnect())) {
            long nextWake = -1;
            synchronized (jobLock) {
                final long now = System.currentTimeMillis();
                final Iterator<MessageJob> iterator = waitingForReply.iterator();

                jobI: while (iterator.hasNext()) {
                    final MessageJob j = iterator.next();


                    for (int i = 0; i < j.receivedAck.size(); i++) {
                        if (j.receivedAck.get(i)) {
                            j.promise.get(i).complete(j.ack.get(i));

                            if (j.receivedAck.size() > 1) {

                                j.promise.remove(i);
                                j.ack.remove(i);
                                j.receivedAck.remove(i);
                                j.destination.remove(i);

                                i--;
                            } else {
                                iterator.remove();
                                continue jobI;
                            }
                        }
                    }

                    if (j.timeoutAt != -1) {
                        if (j.timeoutAt <= now) {
                            j.promise.forEach(p -> p.completeExceptionally(new NKNClientException.MessageAckTimeout(j.messageID)));
                            iterator.remove();
                        } else {
                            if (nextWake == -1) {
                                nextWake = j.timeoutAt;
                            } else {
                                nextWake = Math.min(nextWake, j.timeoutAt);
                            }
                        }
                    } else {
                        if (nextWake == -1) {
                            nextWake = now + j.timeoutIn;
                        } else {
                            nextWake = Math.min(nextWake, now + j.timeoutIn);
                        }
                    }
                }
            }

            MessageJob j = null;
            try {
                ct.messageHold.await();

                synchronized (jobLock) {
                    if (!jobs.isEmpty()) {
                        j = jobs.remove(0);
                    }
                }
                if (j != null) {
                    j.timeoutAt = System.currentTimeMillis() + j.timeoutIn;
                    waitingForReply.add(j);
                    ct.ws.sendPacket(j.payload);
                    if (nextWake == -1) {
                        nextWake = j.timeoutAt;
                    } else {
                        nextWake = Math.min(nextWake, j.timeoutAt);
                    }
                }
            } catch (InterruptedException ignored) {}

            synchronized (jobLock) {
                if (jobs.isEmpty()) {
                    try {
                        if (!scheduledStop.get()) {
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

    private AtomicBoolean scheduledStop = new AtomicBoolean(false);
    public void close() {
        if (!running) throw new IllegalStateException("Client is not (yet) running, cannot close");

        synchronized (jobLock) {
            scheduledStop.set(true);
            jobLock.notify();
        }
        try {
            join();
        } catch (InterruptedException ignored) {}
        ct.ws.close();
    }
    public boolean isScheduledStop() {
        return scheduledStop.get();
    }

    private Function<NKNClient.ReceivedMessage, Object> onMessageL = null;
    public void onMessage(Function<NKNClient.ReceivedMessage, Object> listener) {
        onMessageL = listener;
    }


    public void onInboundMessage(String from, Payloads.Payload message) {
        final Payloads.PayloadType type = message.getType();
        final ByteString replyTo = message.getReplyToPid();
        final ByteString messageID = message.getPid();

        boolean isReplyTo = false;

        synchronized (jobLock) {
            for (MessageJob j : waitingForReply) {
                if (j.messageID.equals(replyTo)) {
                    final int indexOf = j.destination.indexOf(from);
                    if (type == Payloads.PayloadType.TEXT) {
                        try {
                            j.ack.set(indexOf,
                                    new NKNClient.ReceivedMessage(
                                        from,
                                        messageID,
                                        Payloads.PayloadType.TEXT,
                                        Payloads.TextData.parseFrom(message.getData()).getText()
                                ));
                        } catch (InvalidProtocolBufferException e) {
                            LOG.warn("Received packet is of type TEXT but does not contain valid text data");
                        }
                    } else if (type == Payloads.PayloadType.BINARY) {
                        j.ack.set(indexOf,
                                new NKNClient.ReceivedMessage(
                                    from,
                                    messageID,
                                    Payloads.PayloadType.BINARY,
                                    message.getData()
                            ));
                    } else if (type == Payloads.PayloadType.ACK) {
                        j.ack.set(indexOf,
                                new NKNClient.ReceivedMessage(
                                        from,
                                        messageID,
                                        Payloads.PayloadType.ACK,
                                        null
                                ));
                    }
                    j.receivedAck.set(indexOf, true);
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
                sendMessageAsync(Collections.singletonList(from), messageID, ackMessage);
            }
        }

    }

    public List<CompletableFuture<NKNClient.ReceivedMessage>> sendMessageAsync(List<String> destination, ByteString replyTo, Object message) throws NKNClientException.UnknownObjectType {
        if (message instanceof String) {
            return sendMessageAsync(destination, replyTo, Payloads.PayloadType.TEXT, Payloads.TextData.newBuilder().setText((String) message).build().toByteString());
        } else if (message instanceof ByteString) {
            return sendMessageAsync(destination, replyTo, Payloads.PayloadType.BINARY, (ByteString) message);
        } else if (message instanceof byte[]) {
            return sendMessageAsync(destination, replyTo, Payloads.PayloadType.BINARY, ByteString.copyFrom((byte[]) message));
        } else {
            LOG.error("Cannot serialize '{}' to NKN protobuf message", message.getClass());
            throw new NKNClientException.UnknownObjectType("Cannot serialize '" + message.getClass() + "' to NKN message");
        }
    }

    public List<CompletableFuture<NKNClient.ReceivedMessage>> sendMessageAsync(List<String> destination, ByteString replyTo, Payloads.PayloadType type, ByteString message) {
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

    private List<CompletableFuture<NKNClient.ReceivedMessage>> sendOutboundMessage(List<String> destination, ByteString messageID, ByteString payload) {
        if (destination.size() == 0) throw new IllegalArgumentException("At least one address is required for multicast");

        final Messages.ClientToNodeMessage binMsg = Messages.ClientToNodeMessage.newBuilder()
                .setDest(destination.get(0))
                .setPayload(payload)
                .addAllDests(destination.subList(1, destination.size()))
                .setMaxHoldingSeconds(0)
                .build();

        if (!running) throw new IllegalStateException("Client is not running, cannot send messages.");

        final ArrayList<CompletableFuture<NKNClient.ReceivedMessage>> promises = new ArrayList<>();
        for (String ignored : destination) {
            promises.add(new CompletableFuture<>());
        }

        final MessageJob j = new MessageJob(new ArrayList<>(destination), messageID, binMsg.toByteString(), new ArrayList<>(promises), ConnectionProvider.messageAckTimeoutMS());

        LOG.debug("Queueing new MessageJob");
        synchronized (jobLock) {
            jobs.add(j);
            jobLock.notify();
        }

        return promises;
    }

    public void sendAckMessage(String destination, ByteString replyTo) {
        final Payloads.Payload payload = Payloads.Payload.newBuilder()
                .setType(Payloads.PayloadType.ACK)
                .setPid(ByteString.copyFrom(Crypto.nextRandom4B()))
                .setReplyToPid(replyTo)
                .setNoAck(true)
                .build();

        final Messages.ClientToNodeMessage binMsg = Messages.ClientToNodeMessage.newBuilder()
                .setDest(destination)
                .setPayload(payload.toByteString())
                .setMaxHoldingSeconds(0)
                .build();

        ct.ws.sendPacket(binMsg.toByteString());
    }


    private static class MessageJob {

        private final List<String> destination;
        private final ByteString messageID, payload;
        private final List<CompletableFuture<NKNClient.ReceivedMessage>> promise;
        private final long timeoutIn;
        private long timeoutAt = -1;

        private final List<Boolean> receivedAck = new ArrayList<>();
        private final List<NKNClient.ReceivedMessage> ack = new ArrayList<>();

        MessageJob(List<String> destination, ByteString messageID, ByteString payload, List<CompletableFuture<NKNClient.ReceivedMessage>> promise, long timeoutIn) {
            this.destination = destination;
            this.messageID = messageID;
            this.payload = payload;
            this.promise = promise;
            this.timeoutIn = timeoutIn;

            for (String ignored : destination) {
                receivedAck.add(false);
                ack.add(null);
            }
        }

    }
}