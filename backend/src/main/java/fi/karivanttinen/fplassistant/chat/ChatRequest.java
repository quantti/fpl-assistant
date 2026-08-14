package fi.karivanttinen.fplassistant.chat;

import java.util.List;

public record ChatRequest(List<MessageDto> messages) {

    public record MessageDto(String role, String content) {}
}
