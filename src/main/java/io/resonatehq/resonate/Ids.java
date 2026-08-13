package io.resonatehq.resonate;

import io.resonatehq.resonate.Errors.InvalidIdError;

/**
 * The promise id format, in one place.
 *
 * <p>The server treats a promise id as {@code <origin>:<lineage>}: the <b>origin</b> is everything
 * before the first {@code :}, and the lineage segments below it are {@code .}-separated:
 *
 * <pre>{@code root -> root:1 -> root:1.1 -> root:1.1.1}</pre>
 *
 * <p>The origin is load-bearing. {@code promise.register_callback} and {@code task.suspend} require
 * an awaiter and its awaited promise to share one, it selects the origin-state partition a request
 * is routed to, and {@code promise.create} rejects an id that does not extend the {@code
 * resonate:origin} / {@code resonate:branch} / {@code resonate:parent} it declares. So the SDK
 * mints ids with {@link #joinId} and reads them back with {@link #originOf}, both of which mirror
 * the server's own rules.
 *
 * <p>A root id is supplied by the caller and becomes the origin of its whole lineage, so {@link
 * #validateRootId} keeps both separators out of it, exactly as the server does for the origin tag
 * itself.
 */
public final class Ids {

    /** Separates the origin from the lineage below it. A bare root joins its first segment with this. */
    static final String ORIGIN_SEP = ":";

    /** Separates lineage segments below the origin. */
    static final String LINEAGE_SEP = ".";

    private Ids() {}

    /**
     * Append a lineage {@code segment} to {@code ancestor}.
     *
     * <p>A bare root joins its <i>first</i> segment with {@code :}; an ancestor that already carries
     * lineage joins deeper segments with {@code .}, keeping the whole subtree under one origin:
     *
     * <pre>{@code
     * joinId("root", "1")     -> "root:1"
     * joinId("root:1", "2")   -> "root:1.2"
     * joinId("root:1.2", "3") -> "root:1.2.3"
     * }</pre>
     *
     * <p>This is exactly the separator rule the server's {@code resonate:branch} / {@code
     * resonate:parent} validation applies.
     */
    public static String joinId(String ancestor, String segment) {
        String sep = ancestor.contains(ORIGIN_SEP) ? LINEAGE_SEP : ORIGIN_SEP;
        return ancestor + sep + segment;
    }

    /**
     * The lineage origin of {@code id}: everything before the first {@code :}.
     *
     * <p>Mirrors the server's {@code origin()}. An id with no lineage below it (a root) is its own
     * origin.
     */
    public static String originOf(String id) {
        int sep = id.indexOf(ORIGIN_SEP);
        return sep == -1 ? id : id.substring(0, sep);
    }

    /**
     * Validate a caller-supplied root id ({@code run} / {@code rpc} / {@code schedule}), returning it.
     *
     * <p>Both separators are <b>reserved</b>: a root becomes the origin of its whole lineage, and the
     * server rejects an origin containing either one outright ({@code dot_in_origin} / {@code
     * colon_in_origin}). {@code .} because it separates lineage segments; {@code :} because the origin
     * is everything before an id's <i>first</i> {@code :}, so an origin holding one could never be
     * split back out of any id.
     *
     * @throws InvalidIdError here, at the call site that named the workflow, rather than surfacing
     *     later as an opaque 400 from a background create.
     */
    public static String validateRootId(String id) {
        if (id == null || id.isEmpty()) {
            throw new InvalidIdError(id, "id must not be empty");
        }
        if (id.indexOf('\0') != -1) {
            throw new InvalidIdError(id, "id must not contain null bytes");
        }
        for (String sep : new String[] {LINEAGE_SEP, ORIGIN_SEP}) {
            if (id.contains(sep)) {
                throw new InvalidIdError(
                        id,
                        "id must not contain '%s': it is reserved as a lineage separator in the ids the SDK mints below this one"
                                .formatted(sep));
            }
        }
        return id;
    }
}
