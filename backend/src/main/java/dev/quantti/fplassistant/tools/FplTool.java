package dev.quantti.fplassistant.tools;

/**
 * Marker for chat tool beans. {@code ChatService} registers every implementation it finds, so
 * adding a tool class is registration-complete by itself — a hand-maintained list fails
 * silently when an entry is forgotten (the model just never sees the tool).
 */
public interface FplTool {
}
