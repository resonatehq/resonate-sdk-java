package io.resonatehq.resonate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.resonatehq.resonate.Codec.NoopEncryptor;
import io.resonatehq.resonate.Errors.ServerError;
import io.resonatehq.resonate.Network.LocalNetwork;
import io.resonatehq.resonate.Send.ScheduleSearchResult;
import io.resonatehq.resonate.Send.Sender;
import io.resonatehq.resonate.Types.ScheduleRecord;
import io.resonatehq.resonate.Types.Value;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code resonate-sdk-py/tests/test_schedules.py}.
 *
 * <p>The {@link Schedules} client is built directly over a real {@link Sender} + {@link Transport} +
 * {@link LocalNetwork} with a {@code Codec(NoopEncryptor())}, just like {@code Resonate.local()}. A
 * delete of a missing schedule surfaces a {@link ServerError} (wrapped in a {@link
 * CompletionException} on join).
 */
class SchedulesTest {

    private static Schedules local() {
        LocalNetwork net = new LocalNetwork();
        Sender sender = new Sender(new Transport(net), null);
        Codec codec = new Codec(new NoopEncryptor());
        return new Schedules(sender, codec);
    }

    @Test
    void createGetDeleteRoundtrip() {
        Schedules schedules = local();

        ScheduleRecord created = schedules
                .create(
                        "unit-s1",
                        "*/5 * * * *",
                        "unit-s1.{{.timestamp}}",
                        60_000,
                        new Value(),
                        Map.of("resonate:target", "poll://any@default"))
                .join();
        assertEquals("unit-s1", created.id());
        assertEquals("*/5 * * * *", created.cron());

        ScheduleRecord fetched = schedules.get("unit-s1").join();
        assertEquals("unit-s1", fetched.id());

        schedules.delete("unit-s1").join();
    }

    @Test
    void createPassesPromiseTagsThrough() {
        Schedules schedules = local();

        ScheduleRecord created = schedules
                .create(
                        "unit-s-tags",
                        "*/5 * * * *",
                        "unit-s-tags.{{.timestamp}}",
                        60_000,
                        new Value(),
                        Map.of("resonate:target", "poll://any@default", "custom", "x"))
                .join();
        assertEquals(Map.of("resonate:target", "poll://any@default", "custom", "x"), created.promiseTags());

        ScheduleRecord fetched = schedules.get("unit-s-tags").join();
        assertEquals("poll://any@default", fetched.promiseTags().get("resonate:target"));
    }

    @Test
    void deleteMissingReturnsServerError() {
        Schedules schedules = local();
        CompletionException exc = assertThrows(
                CompletionException.class,
                () -> schedules.delete("no-such-schedule").join());
        assertInstanceOf(ServerError.class, exc.getCause());
    }

    @Test
    void searchReturnsRecord() {
        Schedules schedules = local();

        schedules
                .create(
                        "unit-s-search",
                        "* * * * *",
                        "unit-s-search.{{.timestamp}}",
                        60_000,
                        new Value(),
                        Map.of("resonate:target", "poll://any@default"))
                .join();

        ScheduleSearchResult result = schedules.search(null, 100, null).join();
        assertTrue(result.schedules().stream().anyMatch(s -> s.id().equals("unit-s-search")));
    }

    @Test
    void createWithoutResonateTargetIsRejected() {
        Schedules schedules = local();

        // A schedule.create whose promiseTags lack resonate:target is rejected by the server;
        // LocalNetwork must reject it too, so the low-level create overload cannot pass tests
        // against a request a real server returns 400 for.
        CompletionException exc = assertThrows(CompletionException.class, () -> schedules
                .create("unit-s-notarget", "*/5 * * * *", "unit-s-notarget.{{.timestamp}}", 60_000, new Value())
                .join());
        ServerError err = assertInstanceOf(ServerError.class, exc.getCause());
        assertEquals(400, err.code());
    }
}
