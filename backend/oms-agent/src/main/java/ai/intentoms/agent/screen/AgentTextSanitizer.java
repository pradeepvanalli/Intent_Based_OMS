package ai.intentoms.agent.screen;

/**
 * Normalises agent-supplied free text before it is stored (GR-08, FR-AGT-05): strips control characters (tabs and line
 * breaks survive), collapses CR/LF to LF and truncates to {@value #MAX_CHARS} characters. The text stays data; it is never
 * merged into a prompt.
 */
public final class AgentTextSanitizer {
    public static final int MAX_CHARS = 2_000;

    private AgentTextSanitizer() {}

    public static String sanitize(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_CHARS));
        for (int i = 0; i < text.length() && sb.length() < MAX_CHARS; i++) {
            char c = text.charAt(i);
            if (c == '\r') { if (i + 1 < text.length() && text.charAt(i + 1) == '\n') continue; sb.append('\n'); continue; }
            if (c == '\n' || c == '\t') { sb.append(c); continue; }
            if (Character.isISOControl(c) || (c >= '​' && c <= '‏') || c == '﻿') continue;
            sb.append(c);
        }
        return sb.toString().strip();
    }
}
