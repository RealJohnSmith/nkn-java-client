package jsmith.nknsdk.network.session;

import com.google.protobuf.ByteString;
import jsmith.nknsdk.network.ClientMessageWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 *
 */
public class Session {

    private static final Logger LOG = LoggerFactory.getLogger(Session.class);

    boolean isEstablished;
    boolean isClosing;
    boolean isClosed;

    final String remoteIdentifier;
    final ByteString sessionId;

    List<String> prefixes;
    int ownMulticlients;
    int mtu, winSize;

    final Object lock = new Object();

    private final SessionInputStream is;
    private final SessionOutputStream os;

    Session(SessionHandler handler, List<String> prefixes, int ownMulticlients, String remoteIdentifier, ByteString sessionId, int mtu, int winSize) {
        this.prefixes = prefixes;
        this.ownMulticlients = ownMulticlients;
        this.remoteIdentifier = remoteIdentifier;
        this.sessionId = sessionId;

        this.mtu = mtu;
        this.winSize = winSize;

        sentBytesIntegral.put(0, 0);

        os = new SessionOutputStream(this, handler);
        is = new SessionInputStream(this);
    }

    Runnable onSessionEstablishedCb = null;
    boolean onSessionEstablishedCalled = false;
    public void onSessionEstablished(Runnable onSessionEstablished) {
        this.onSessionEstablishedCb = onSessionEstablished;
        if (isEstablished && !onSessionEstablishedCalled && onSessionEstablished != null) {
            onSessionEstablishedCalled = true;
            onSessionEstablished.run();
        }
    }

    public SessionInputStream getInputStream() {
        // TODO if is not established or is closing or closed, throw an error
        return is;
    }
    public SessionOutputStream getOutputStream() {
        // TODO if is not established or is closing or closed, throw an error
        return os;
    }


    // Outbound
    private int latestConfirmedSeqId = 0;
    int latestSentSeqId = 0;
    final BlockingQueue<DataChunk> sendQ = new ArrayBlockingQueue<>(
            Math.max(SessionHandler.MAX_WIN_SIZE / SessionHandler.MAX_MTU, ClientMessageWorker.MAX_CONNECTION_WINSIZE * SessionHandler.MAX_MULTICLIENTS) + 16
    );
    final HashMap<DataChunk, SentLog> sentQ = new HashMap<>();
    final HashMap<Integer, Integer> sentBytesIntegral = new HashMap<>();
    final BlockingQueue<DataChunk> resendQ = new PriorityBlockingQueue<>(
            Math.max(SessionHandler.MAX_WIN_SIZE / SessionHandler.MAX_MTU, ClientMessageWorker.MAX_CONNECTION_WINSIZE * SessionHandler.MAX_MULTICLIENTS) + 32,
            Comparator.comparingInt(j -> j.sequenceId)
    );

    // Acks
    final ArrayList<AckBundle> pendingAcks = new ArrayList<>();


    void onReceivedAck(int startSeq, int count) {
        if (startSeq == latestConfirmedSeqId + 1) latestConfirmedSeqId = startSeq + count - 1;

        synchronized (sentQ) {
            sentQ.entrySet().removeIf(entry -> {
                boolean acked = entry.getKey().sequenceId >= startSeq && entry.getKey().sequenceId < startSeq + count;
                if (acked) {
                    entry.getValue().sentBy.onWinsizeAckReceived(remoteIdentifier, (int) (System.currentTimeMillis() - entry.getValue().sentAt));
                }
                return acked;
            });
            resendQ.removeIf(entry -> entry.sequenceId >= startSeq && entry.sequenceId < startSeq + count);

            if (sentQ.isEmpty()) {
                latestConfirmedSeqId = latestSentSeqId;
            } else {
                sentQ.keySet().stream().min(Comparator.comparingInt(dc -> dc.sequenceId)).ifPresent(dc -> latestConfirmedSeqId = dc.sequenceId - 1);
            }

            int start = sentBytesIntegral.get(latestConfirmedSeqId);
            final Iterator<Map.Entry<Integer, Integer>> iterator = sentBytesIntegral.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<Integer, Integer> entry = iterator.next();
                if (entry.getKey() < latestConfirmedSeqId) {
                    iterator.remove();
                } else {
                    entry.setValue(entry.getValue() - start);
                }
            }
        }
    }

    void onReceivedChunk(int sequenceId, ByteString data, ClientMessageWorker from) {
        LOG.debug("Received chunk, seq {}", sequenceId);
        is.onReceivedDataChunk(sequenceId, data);

        synchronized (pendingAcks) {
            // Check for appends
            int appendedI = -1;
            boolean within = false;
            for (int i = 0; i < pendingAcks.size(); i++) {
                AckBundle ack = pendingAcks.get(i);
                if (ack.worker != from) continue;

                if (ack.startSeq + ack.count == sequenceId) {
                    ack.count += 1;
                    appendedI = i;
                    break;
                }
                if (ack.startSeq >= sequenceId && ack.startSeq + ack.count < sequenceId) {
                    within = true;
                    break;
                }
            }
            // Check for prepends
            if (!within) {
                int mergedI = -1;
                boolean prepended = false;
                for (int i = 0; i < pendingAcks.size(); i++) {
                    AckBundle ack = pendingAcks.get(i);
                    if (ack.worker != from) continue;

                    if (ack.startSeq - 1 == sequenceId) {
                        ack.startSeq -= 1;
                        ack.count += 1;
                        prepended = true;
                        if (appendedI != -1) {
                            AckBundle newAck = pendingAcks.get(appendedI);
                            newAck.count += ack.count - 1;
                            mergedI = i;
                        }
                        break;
                    }
                }
                if (mergedI != -1) pendingAcks.remove(mergedI);

                if (appendedI == -1 && !prepended) {
                    pendingAcks.add(new AckBundle(from, sequenceId, 1));
                }
            }
        }
    }

    public void close() {
        if (isClosing) return;
        if (!isEstablished) {
            isClosing = true;
            isClosed = true;
            return;
        }
        // TODO
    }

    static class AckBundle {
        final ClientMessageWorker worker;
        int startSeq;
        int count;
        AckBundle(ClientMessageWorker w, int startSeq, int count) {
            this.worker = w;
            this.startSeq = startSeq;
            this.count = count;
        }
    }

    static class DataChunk {
        final int sequenceId;
        final ByteString data;
        DataChunk(int sequenceId, ByteString data) {
            this.sequenceId = sequenceId;
            this.data = data;
        }
    }

    static class SentLog {
        final long sentAt;
        final ClientMessageWorker sentBy;
        SentLog(long sentAt, ClientMessageWorker sentBy) {
            this.sentAt = sentAt;
            this.sentBy = sentBy;
        }
    }
}