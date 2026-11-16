package dev.foragingplanner.mc;

import java.util.ArrayDeque;
import java.util.Deque;

/** Trees per minute: tree gifts in the last 60 seconds, and the average of the session. */
public final class Tpm {
	private static final Deque<Long> GIFTS = new ArrayDeque<>();

	private Tpm() {}

	public static void gift() {
		long now = System.currentTimeMillis();
		GIFTS.addLast(now);
		while (!GIFTS.isEmpty() && now - GIFTS.peekFirst() > 60_000) GIFTS.removeFirst();
		if (GIFTS.size() > Progress.get().bestTpm) {
			Progress.get().bestTpm = GIFTS.size();
			Progress.changed();
		}
	}

	/** Trees in the last minute. */
	public static int lastMinute() {
		long now = System.currentTimeMillis();
		while (!GIFTS.isEmpty() && now - GIFTS.peekFirst() > 60_000) GIFTS.removeFirst();
		return GIFTS.size();
	}

	/** Average trees per minute this session. */
	public static double session() {
		var s = JournalStore.journal.current;
		return s.elapsedMs < 60_000 ? 0 : s.value("gifts") * 60_000.0 / s.elapsedMs;
	}
}
