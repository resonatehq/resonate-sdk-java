package io.resonatehq.resonate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.resonatehq.resonate.Errors.AlreadyRegisteredError;
import io.resonatehq.resonate.Errors.ApplicationError;
import io.resonatehq.resonate.Errors.Base64DecodeError;
import io.resonatehq.resonate.Errors.DecodingError;
import io.resonatehq.resonate.Errors.FunctionNotFoundError;
import io.resonatehq.resonate.Errors.HttpError;
import io.resonatehq.resonate.Errors.PlatformError;
import io.resonatehq.resonate.Errors.ResonateError;
import io.resonatehq.resonate.Errors.ResonateTimeoutError;
import io.resonatehq.resonate.Errors.SerializationError;
import io.resonatehq.resonate.Errors.ServerError;
import io.resonatehq.resonate.Errors.StoppedError;
import io.resonatehq.resonate.Errors.Suspended;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Java analogue of {@code tests/test_error_pickle.py}.
 *
 * <p>Python errors cross the durable-promise boundary as a base64 {@code __py_pickle} of the
 * original object, so a same-runtime awaiter recovers the <b>exact type and attributes</b> only if
 * the class round-trips through {@code pickle}. Java's wire-recovery analogue is {@link
 * java.io.Serializable}: {@link Throwable} is {@code Serializable}, so every {@link Errors} type is
 * too. These tests are the regression guard — construct one of every error type, round-trip it
 * through {@link ObjectOutputStream}/{@link ObjectInputStream}, and assert the type, message, and
 * public accessors all come back intact.
 *
 * <p>The double round-trip mirrors Python's — proving in-process reconstruction is stable (Python
 * rebuilds via {@code cls(*self.args)}). Cross-version wire stability is out of scope here: when a
 * codec ships, errors crossing to/from the Python side will use a structured envelope, not Java's
 * native serialization (which cannot interop with {@code pickle}).
 */
class ErrorSerializationTest {

    // One representative instance of every error type the SDK defines. The wrapper errors carry a
    // plain IllegalArgumentException -- a serializable, importable nested exception (the analogue of
    // Python's ValueError) -- so the round-trip exercises the error itself, not its payload.
    private static List<Throwable> errors() {
        return List.of(
                new ResonateError("top-level"),
                new FunctionNotFoundError("foo", 2),
                new AlreadyRegisteredError("bar"),
                new ServerError(500, "boom"),
                new StoppedError(),
                new DecodingError("bad bytes"),
                new SerializationError(new IllegalArgumentException("nope")),
                new HttpError(new IllegalArgumentException("net down")),
                new Base64DecodeError(new IllegalArgumentException("bad b64")),
                new PlatformError(List.of(new ServerError(500, "boom"), new ResonateTimeoutError())),
                new Suspended(),
                new ApplicationError("app boom"),
                new ResonateTimeoutError());
    }

    @Test
    void roundTripPreservesTypeAndMessage() throws Exception {
        for (Throwable err : errors()) {
            Throwable restored = roundTrip(err);
            assertEquals(
                    err.getClass(),
                    restored.getClass(),
                    "type for " + err.getClass().getSimpleName());
            assertEquals(
                    err.getMessage(),
                    restored.getMessage(),
                    "message for " + err.getClass().getSimpleName());
            // A second round-trip proves reconstruction is stable.
            Throwable again = roundTrip(restored);
            assertEquals(err.getClass(), again.getClass());
            assertEquals(err.getMessage(), again.getMessage());
        }
    }

    @Test
    void roundTripPreservesAttributes() throws Exception {
        FunctionNotFoundError original = new FunctionNotFoundError("foo", 2);
        FunctionNotFoundError restored = (FunctionNotFoundError) roundTrip(original);
        // The round-trip yields a fresh instance, not the original reference.
        assertNotSame(original, restored);
        assertEquals("foo", restored.name());
        assertEquals(2, restored.version());
    }

    @Test
    void roundTripPreservesWrapperCauseChain() throws Exception {
        // The wrapped cause is stored both as a field and as the JDK cause (the same object).
        // Java serialization preserves that shared reference, so error() == getCause() survives.
        SerializationError restored =
                (SerializationError) roundTrip(new SerializationError(new IllegalArgumentException("nope")));
        assertEquals("serialization error: nope", restored.getMessage());
        assertInstanceOf(IllegalArgumentException.class, restored.error());
        assertSame(restored.error(), restored.getCause());
    }

    @Test
    void platformErrorCausesAndCauseSurviveRoundTrip() throws Exception {
        PlatformError restored = (PlatformError)
                roundTrip(new PlatformError(List.of(new ServerError(1, "first"), new ResonateTimeoutError())));
        assertEquals(
                List.of(ServerError.class, ResonateTimeoutError.class),
                restored.causes().stream().map(Object::getClass).toList());
        assertInstanceOf(ServerError.class, restored.cause());
        assertEquals(1, ((ServerError) restored.cause()).code());
        // cause() is the first element, and the JDK cause chains to the same primary cause.
        assertSame(restored.causes().get(0), restored.cause());
        assertSame(restored.cause(), restored.getCause());
    }

    private static Throwable roundTrip(Throwable error) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(error);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            // Returns Throwable; callers downcast to the concrete type. A reference downcast is a
            // checked cast, so no @SuppressWarnings("unchecked") is needed.
            return (Throwable) in.readObject();
        }
    }
}
