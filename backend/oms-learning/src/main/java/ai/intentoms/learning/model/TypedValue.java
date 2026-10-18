package ai.intentoms.learning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * A learned fact's value (requirements doc §6.2, {@code SegmentFact.value}), bounded by its {@link FactKey}'s
 * declared range rather than left as free-form JSON — a learner can only write a value shape its fact key allows.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TypedValue.NumberValue.class, name = "NumberValue"),
    @JsonSubTypes.Type(value = TypedValue.BoolValue.class, name = "BoolValue"),
    @JsonSubTypes.Type(value = TypedValue.TextValue.class, name = "TextValue"),
    @JsonSubTypes.Type(value = TypedValue.ListValue.class, name = "ListValue")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface TypedValue {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NumberValue(double value) implements TypedValue {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BoolValue(boolean value) implements TypedValue {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TextValue(String value) implements TypedValue {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ListValue(List<String> values) implements TypedValue {}
}
