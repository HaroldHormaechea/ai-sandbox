package com.aisandbox.server.mux;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * UC-100 — a minimal {@link WebSocketSession} test double for the {@code /v1/mux}
 * transport tests. Records every outbound frame (text + binary) in emission
 * order, exposes a settable inbound {@link Flux}, and captures the terminal
 * {@link CloseStatus}. Mirrors the proven shape of
 * {@code MultiQuestionEchoOrderingTest.OrderingSession} but generalised for the
 * mux handler + writer (both channels: text and binary).
 */
public final class FakeMuxSession implements WebSocketSession {

    private final URI uri;
    private final HandshakeInfo handshakeInfo;
    private final Map<String, Object> attrs = new ConcurrentHashMap<>();

    /** Every outbound frame the writer emitted, in order. */
    public final List<WebSocketMessage> sent = new CopyOnWriteArrayList<>();

    /** Inbound frames delivered to {@code handle()} via {@link #receive()}. */
    public volatile Flux<WebSocketMessage> incoming = Flux.never();

    public volatile CloseStatus closedWith;

    public FakeMuxSession() {
        this(URI.create("/v1/mux"), new HttpHeaders());
    }

    public FakeMuxSession(URI uri, HttpHeaders headers) {
        this.uri = uri;
        this.handshakeInfo = new HandshakeInfo(uri, headers, Mono.empty(), "ai-sandbox.mux.v1");
    }

    /** Convenience: seed the resolved identity so the handler skips registry lookup. */
    public FakeMuxSession withAttr(String key, Object value) {
        attrs.put(key, value);
        return this;
    }

    // ── outbound recording ──

    /** Text payloads of every recorded outbound frame. */
    public List<String> sentText() {
        return sent.stream()
                .filter(m -> m.getType() == WebSocketMessage.Type.TEXT)
                .map(WebSocketMessage::getPayloadAsText)
                .toList();
    }

    /** Raw bytes of every recorded outbound BINARY frame. */
    public List<byte[]> sentBinary() {
        return sent.stream()
                .filter(m -> m.getType() == WebSocketMessage.Type.BINARY)
                .map(m -> {
                    java.nio.ByteBuffer bb = m.getPayload().asByteBuffer();
                    byte[] a = new byte[bb.remaining()];
                    bb.get(a);
                    return a;
                })
                .toList();
    }

    @Override
    public Mono<Void> send(Publisher<WebSocketMessage> messages) {
        return Flux.from(messages).doOnNext(sent::add).then();
    }

    @Override
    public Flux<WebSocketMessage> receive() {
        return incoming;
    }

    // ── boilerplate ──

    @Override
    public String getId() {
        return "fake-mux-session";
    }

    @Override
    public HandshakeInfo getHandshakeInfo() {
        return handshakeInfo;
    }

    @Override
    public DataBufferFactory bufferFactory() {
        return DefaultDataBufferFactory.sharedInstance;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attrs;
    }

    @Override
    public boolean isOpen() {
        return closedWith == null;
    }

    @Override
    public Mono<Void> close(CloseStatus status) {
        this.closedWith = status;
        return Mono.empty();
    }

    @Override
    public Mono<CloseStatus> closeStatus() {
        return Mono.justOrEmpty(closedWith);
    }

    @Override
    public WebSocketMessage textMessage(String payload) {
        return new WebSocketMessage(
                WebSocketMessage.Type.TEXT, bufferFactory().wrap(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public WebSocketMessage binaryMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
        return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory()));
    }

    @Override
    public WebSocketMessage pingMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
        return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory()));
    }

    @Override
    public WebSocketMessage pongMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
        return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory()));
    }
}
