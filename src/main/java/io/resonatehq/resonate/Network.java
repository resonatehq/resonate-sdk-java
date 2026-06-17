package io.resonatehq.resonate;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The transport abstraction for all server communication, mirroring the {@code Network} Protocol
 * from {@code resonate.network} in the Python SDK.
 *
 * <p>All communication between Resonate and the server (local or remote) flows through it as JSON
 * strings. Methods raise on error.
 *
 * <p>Python declares this as a {@code typing.Protocol} with two implementations ({@code
 * LocalNetwork} for an in-process server simulation and {@code HttpNetwork} for a real server). Java
 * has no structural typing, so it is a nominal {@code interface}; implementations are still to be
 * ported. {@link Transport} is the only consumer and depends on this interface alone.
 *
 * <p><b>Async.</b> Python's {@code async def send} / {@code start} / {@code stop} become {@link
 * CompletableFuture}-returning methods — the idiomatic Java analogue of an awaitable. The
 * synchronous accessors ({@link #pid()}, {@link #group()}, {@link #unicast()}, {@link #anycast()},
 * {@link #targetResolver(String)}) and the callback registration {@link #recv(Consumer)} mirror
 * their plain (non-async) Python counterparts.
 */
public interface Network {

    /** The process id this network instance is bound to. */
    String pid();

    /** The process group this network instance participates in. */
    String group();

    /** The unicast address that targets this specific process. */
    String unicast();

    /** The anycast address that targets any process in the group. */
    String anycast();

    /** Start the network (open connections, begin the in-process server, ...). */
    CompletableFuture<Void> start();

    /** Stop the network and release its resources. */
    CompletableFuture<Void> stop();

    /** Send an already-serialized request, completing with the raw response string. */
    CompletableFuture<String> send(String req);

    /** Register a callback invoked with each raw incoming message string. */
    void recv(Consumer<String> callback);

    /** Resolve a logical target into a concrete address. */
    String targetResolver(String target);
}
