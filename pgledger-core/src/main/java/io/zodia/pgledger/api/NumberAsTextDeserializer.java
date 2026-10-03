package io.zodia.pgledger.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Binds JSON scalars to their exact source text. Numbers keep the digits as
 * sent (no float round trip), strings pass through, null stays null. Used for
 * balance type fields that accept a code or a numeric id, so one parse is enough.
 */
public final class NumberAsTextDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        String text = parser.getValueAsString();
        if (text == null) {
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
        return text;
    }
}
