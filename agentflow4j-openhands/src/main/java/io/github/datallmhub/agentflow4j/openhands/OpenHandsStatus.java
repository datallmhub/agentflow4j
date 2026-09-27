package io.github.datallmhub.agentflow4j.openhands;

/**
 * The states an OpenHands conversation goes through, as returned by the V1 API.
 * Unknown values from a newer server map to {@link #UNKNOWN} rather than
 * failing the run.
 */
public enum OpenHandsStatus {

    /** The start task is still preparing the sandbox or the repository. */
    STARTING,
    /** The agent is ready but idle. */
    IDLE,
    /** The agent is working. */
    RUNNING,
    /** Paused; OpenHands resumes it on its own. */
    PAUSED,
    /** The agent asks a human to confirm an action in the OpenHands UI. */
    WAITING_FOR_CONFIRMATION,
    /** The task is done. */
    FINISHED,
    /** The agent or its sandbox failed. */
    ERROR,
    /** The agent cannot make progress without help. */
    STUCK,
    /** A state this version does not know about. */
    UNKNOWN;

    static OpenHandsStatus of(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            // Start-task statuses: the sandbox and the repository are being prepared.
            case "WAITING_FOR_SANDBOX", "PREPARING_REPOSITORY", "SETTING_UP_SKILLS", "STARTING", "WORKING" -> STARTING;
            case "READY", "IDLE" -> IDLE;
            case "RUNNING" -> RUNNING;
            case "PAUSED" -> PAUSED;
            case "WAITING_FOR_CONFIRMATION" -> WAITING_FOR_CONFIRMATION;
            case "FINISHED" -> FINISHED;
            case "ERROR", "MISSING" -> ERROR;
            case "STUCK" -> STUCK;
            case "DELETING" -> ERROR;
            default -> UNKNOWN;
        };
    }

    /** No further change is expected without someone stepping in. */
    public boolean isTerminal() {
        return this == FINISHED || this == ERROR || this == STUCK || this == WAITING_FOR_CONFIRMATION;
    }
}
