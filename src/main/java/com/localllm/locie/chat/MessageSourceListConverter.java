package com.localllm.locie.chat;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores a reply's sources as text, one per line, so a citation needs no second table and no join
 * and survives the indexed document being removed. Each line is {@code ordinal \t score \t path}:
 * the path goes last because it can contain spaces or tabs, while the two numbers cannot.
 */
@Converter
public class MessageSourceListConverter implements AttributeConverter<List<MessageSource>, String> {

    @Override
    public String convertToDatabaseColumn(List<MessageSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (MessageSource source : sources) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(source.ordinal()).append('\t')
                    .append(source.score()).append('\t')
                    .append(source.path());
        }
        return text.toString();
    }

    @Override
    public List<MessageSource> convertToEntityAttribute(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<MessageSource> sources = new ArrayList<>();
        for (String line : stored.split("\n")) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            try {
                sources.add(new MessageSource(
                        parts[2], Integer.parseInt(parts[0]), Double.parseDouble(parts[1])));
            } catch (NumberFormatException ex) {
                // A line this build cannot read is skipped rather than breaking the conversation.
            }
        }
        return List.copyOf(sources);
    }
}
