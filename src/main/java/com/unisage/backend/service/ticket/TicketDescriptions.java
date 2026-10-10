package com.unisage.backend.service.ticket;

/**
 * A ticket description may end with a staff-only section (the calculation trace of an
 * {@code AI_CALCULATION_WRONG} ticket - SPEC-calculation-node §7.2: the trace is readable by staff
 * only). Everything from {@link #STAFF_ONLY_MARKER} on is cut from the ticket owner's own view.
 */
public final class TicketDescriptions {

    public static final String STAFF_ONLY_MARKER = "\n\n----- Dành cho bộ phận hỗ trợ -----\n";

    private TicketDescriptions() {}

    public static String forViewer(String description, boolean staffView) {
        if (staffView || description == null) {
            return description;
        }
        int marker = description.indexOf(STAFF_ONLY_MARKER);
        return marker < 0 ? description : description.substring(0, marker);
    }
}
